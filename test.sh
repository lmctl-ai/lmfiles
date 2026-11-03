#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

TIMEOUT_CMD=timeout
command -v timeout >/dev/null 2>&1 || TIMEOUT_CMD=gtimeout
run() { "$TIMEOUT_CMD" "$@"; }

native=0
if [[ "${1:-}" == "--native" ]]; then native=1; shift; fi
[[ $# -eq 0 ]] || { echo "usage: $0 [--native]" >&2; exit 2; }

if (( native == 0 )); then
  [[ "${SKIP_BUILD:-0}" == 1 ]] || run 1800 mvn -q package
  host=(java -jar target/lmfiles-0.1.3-all.jar)
  [[ -f target/lmfiles-0.1.3-all.jar ]]
else
  binary=target/lmfiles
  [[ ! -x target/lmfiles.exe ]] || binary=target/lmfiles.exe
  [[ -x "$binary" ]] || { echo "native binary missing; run scripts/build-native.sh" >&2; exit 2; }
  host=("$binary")
fi
host_json=$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' "${host[@]}")

python3 scripts/check-host-flags.py schema/lmfiles.dsl.json "${host[@]}"

schema_file=schema/lmfiles.dsl.json
schema_tmp=$(mktemp)
trap 'rm -f "$schema_tmp"' EXIT
"${host[@]}" --lmcode-schema --json > "$schema_tmp"
cmp -s "$schema_tmp" "$schema_file" || { echo "generated schema is stale" >&2; exit 1; }
python3 scripts/verify-deps.py
run 30 python3 scripts/test-s3-oracle.py
run 30 python3 scripts/test-s3-live-oracle.py
run 30 python3 scripts/test-s3-live-ledger-audit.py
run 30 python3 scripts/test-scale-guard.py
"${host[@]}" --lmcode-signatures > "$schema_tmp.signatures"
lmscript --lmcode-signatures "$schema_file" > "$schema_tmp.expected"
diff -u "$schema_tmp.expected" "$schema_tmp.signatures"
"${host[@]}" --version | grep -q '^lmfiles 0\.1\.3$'
python3 scripts/test-protocol.py "${host[@]}"
python3 scripts/test-protocol-parity.py "$host_json"
"${host[@]}" --notices | grep -q 'Lattice Systems LLC'

fixture=$(mktemp -d "${TMPDIR:-/tmp}/lmfiles-gate.XXXXXX")
trap 'rm -rf "$fixture"; rm -f "$schema_tmp"' EXIT
"${host[@]}" --self-test "$fixture/self" | tee "$fixture/self.json"
python3 - "$fixture/self.json" <<'PY'
import json, pathlib, sys
row=json.loads(pathlib.Path(sys.argv[1]).read_text().splitlines()[-1])
assert row.get("ok") is True and int(row.get("assertions", 0)) >= 10
PY
"${host[@]}" --journal-test "$fixture/journal" | tee "$fixture/journal.json"
python3 - "$fixture/journal.json" <<'PY'
import json, pathlib, sys
row=json.loads(pathlib.Path(sys.argv[1]).read_text().splitlines()[-1])
assert row.get("ok") is True and int(row.get("assertions", 0)) >= 5
PY

# Deterministic fake-clock, review-fix and format-upgrade regressions run on JVM/native alike.
run 120 "${host[@]}" --lease-test "$fixture/leases"
run 120 "${host[@]}" --review-test "$fixture/review"
run 120 "${host[@]}" --storage-test "$fixture/storage"
run 240 "${host[@]}" --scan-test "$fixture/scan"

# Exercise scan through both the actual lmcode binding and the CLI dispatch.
python3 - "$fixture" "$host_json" <<'PYSCAN'
import json,sys
from pathlib import Path
work=Path(sys.argv[1]);tree=work/'dsl-scan';tree.mkdir();(tree/'input.txt').write_text('synthetic scan input');host=json.loads(sys.argv[2])
schema=str((Path.cwd()/'schema/lmfiles.dsl.json').resolve());command=json.dumps(host+['--lmcode','$DSLFILE'])
body=('let p = _lmcode_ schema='+json.dumps(schema)+';\nlet db=registry.open('+json.dumps(str(work/'dsl-scan.mv'))+',"cli","{}");\nfile_write('+json.dumps(str(work/'scan-result.json'))+',db.scan('+json.dumps(str(tree))+',"{}"));db.close();\nend _lmcode_\n'
      'let result=run_lmcode(p,{command:'+command+'}); assert(result.ok,json_stringify(result));\n')
(work/'scan.lms').write_text(body,encoding='utf-8')
PYSCAN
run 30 lmscript "$fixture/scan.lms"
run 30 "${host[@]}" --request "$fixture/dsl-scan.mv" scan "$fixture/dsl-scan" '{}' > "$fixture/scan-cli.json"
python3 - "$fixture" <<'PYSCANRESULT'
import json,sys
from pathlib import Path
work=Path(sys.argv[1]);first=json.loads((work/'scan-result.json').read_text());again=json.loads((work/'scan-cli.json').read_text())
assert first['files']=='1' and first['hashed']=='1',first
assert again['files']=='1' and again['hashed']=='0',again
PYSCANRESULT

# Fresh processes cover same-DB reopen, commit killpoints, lock ownership and
# optional network syscall denial. The harness fails on missing request/lock APIs.
run 240 python3 scripts/test-processes.py --command "$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' "${host[@]}")"

# Invalid memory configuration must fail before a host operation is attempted.
if LMFILES_MAX_MEMORY_MB=invalid "${host[@]}" --version >"$fixture/memory.json"; then
  echo "invalid memory budget was accepted" >&2; exit 1
fi
python3 - "$fixture/memory.json" <<'PYINVALIDMEMORY'
import json,pathlib,sys
r=json.loads(pathlib.Path(sys.argv[1]).read_text().split('LMCODE_RETURN_BEGIN\n',1)[1].split('\nLMCODE_RETURN_END',1)[0]);assert r.get('phase')=='cli' and 'LMFILES_MAX_MEMORY_MB' in r.get('message',''),r
PYINVALIDMEMORY

if (( native == 1 )) && [[ "$(uname -s)" == Linux ]] && command -v readelf >/dev/null 2>&1; then
  readelf -d "$binary" | awk '/NEEDED/{gsub(/[\[\]]/,"",$5);print $5}' > "$fixture/needed"
  while read -r lib; do
    case "$lib" in
      libc.so.6|libm.so.6|libpthread.so.0|libdl.so.2|librt.so.1|ld-linux*.so.*) ;;
      *) echo "unexpected dynamic dependency: $lib" >&2; exit 1;;
    esac
  done < "$fixture/needed"
fi

# The actual VM allocation budget must stop a growing list, not only validate env.
python3 - "$fixture" "$host_json" <<'PYMEMORY'
import json,sys
from pathlib import Path
work=Path(sys.argv[1]);schema=str((Path.cwd()/'schema/lmfiles.dsl.json').resolve());host=json.loads(sys.argv[2])
def restricted(name, amount):
 path=work/name
 path.write_text('import os,sys,subprocess\n'+f'host={host!r}\n'+f"env=os.environ.copy();env['LMFILES_MAX_MEMORY_MB']='{amount}'\nresult=subprocess.run(host+sys.argv[1:],env=env);raise SystemExit(result.returncode)\n",encoding='utf-8');path.chmod(0o755);return [sys.executable,str(path)]
command=json.dumps(restricted('memory-host-1.py',1)+['--lmcode','$DSLFILE'])
program=('let p = _lmcode_ schema='+json.dumps(schema)+';\nlet values = str_list();\nfor (i in range(0,10000)) { values.add("'+'x'*128+'"); }\nend _lmcode_\n'
         'let result=run_lmcode(p,{command:'+command+'});write_json('+json.dumps(str(work/'memory.json'))+',result);\n')
(work/'memory.lms').write_text(program,encoding='utf-8')
PYMEMORY
LMFILES_MAX_MEMORY_MB=1 run 30 lmscript "$fixture/memory.lms"
python3 - "$fixture/memory.json" <<'PYMEMORYRESULT'
import json,pathlib,sys
r=json.loads(pathlib.Path(sys.argv[1]).read_text());assert not r.get('ok') and 'memory' in r.get('message','').lower(),r
PYMEMORYRESULT

python3 - "$fixture" "$host_json" <<'PYROOT'
import json,sys
from pathlib import Path
work=Path(sys.argv[1]);schema=str((Path.cwd()/'schema/lmfiles.dsl.json').resolve());host=json.loads(sys.argv[2]);command=json.dumps(host+['--lmcode','$DSLFILE'])
source=('let p = _lmcode_ schema='+json.dumps(schema)+';\nregistry.item_upsert("{}","{}");\nend _lmcode_\n'
        'let result=run_lmcode(p,{command:'+command+'});write_json('+json.dumps(str(work/'root.json'))+',result);\n')
(work/'root.lms').write_text(source,encoding='utf-8')
PYROOT
run 30 lmscript "$fixture/root.lms"
python3 - "$fixture/root.json" <<'PYROOTRESULT'
import json,pathlib,sys
r=json.loads(pathlib.Path(sys.argv[1]).read_text());assert not r.get('ok') and 'requires an open Registry handle' in r.get('message','') and 'ClassCastException' not in r.get('message',''),r
PYROOTRESULT

# Per-store caches must also consume the explicitly selected VM memory budget.
python3 - "$fixture" "$host_json" <<'PYCACHES'
import json,sys
from pathlib import Path
work=Path(sys.argv[1]);schema=str((Path.cwd()/'schema/lmfiles.dsl.json').resolve());host=json.loads(sys.argv[2])
wrapper=work/'memory-host-2.py';wrapper.write_text('import os,sys,subprocess\n'+f'host={host!r}\n'+'env=os.environ.copy();env[\'LMFILES_MAX_MEMORY_MB\']=\'2\';result=subprocess.run(host+sys.argv[1:],env=env);raise SystemExit(result.returncode)\n',encoding='utf-8');wrapper.chmod(0o755)
command=json.dumps([sys.executable,str(wrapper),'--lmcode','$DSLFILE'])
program='let p = _lmcode_ schema='+json.dumps(schema)+';\n'
for name in ['a','b']:
 program+='let '+name+' = registry.open('+json.dumps((work/(name+'.mv')).as_posix())+',"budget","{\\\"cache_mb\\\":1}");\n'
program+='end _lmcode_\nlet result=run_lmcode(p,{command:'+command+'});write_json('+json.dumps(str(work/'caches.json'))+',result);\n'
(work/'caches.lms').write_text(program,encoding='utf-8')
PYCACHES
LMFILES_MAX_MEMORY_MB=2 run 30 lmscript "$fixture/caches.lms"
python3 - "$fixture/caches.json" <<'PYCACHESRESULT'
import json,pathlib,sys
r=json.loads(pathlib.Path(sys.argv[1]).read_text());assert not r.get('ok') and 'memory' in r.get('message','').lower(),r
PYCACHESRESULT

if (( native == 1 )) && [[ "$(uname -s)" == Linux ]]; then
  (ulimit -v 6291456; run 120 "${host[@]}" --self-test "$fixture/address-space")
fi
python3 - <<'PYGALLERY'
import re,struct
from pathlib import Path
s=Path('README.md').read_text()
for image in re.findall(r'!\[[^\]]*\]\((docs/images/[^)]+)\)',s):
 data=Path(image).read_bytes();assert data.startswith(b'\x89PNG\r\n\x1a\n') and len(data)<300000,image
for name in re.findall(r'https://raw.githubusercontent.com/lmctl-ai/lmfiles/main/examples/([A-Za-z0-9_]+[.]lms)',s):assert Path('examples',name).is_file(),name
assert 'see source' not in s.lower()
print('gallery captions and PNGs verified')
PYGALLERY
if [[ "${LMFILES_TEST_EXAMPLES:-0}" == 1 ]]; then
  run 180 bash examples/render-all.sh
  run 300 python3 scripts/test-publication.py
else
  echo 'gallery executions skipped: set LMFILES_TEST_EXAMPLES=1 with locally installed lmfiles/lmplot/lmaws'
fi
rm -f "$schema_tmp.signatures" "$schema_tmp.expected"

echo "lmfiles test gate passed ($([[ $native == 1 ]] && echo native || echo managed))"
