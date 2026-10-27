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
  host=(java -jar target/lmfiles-0.1.0-all.jar)
  [[ -f target/lmfiles-0.1.0-all.jar ]]
else
  binary=target/lmfiles
  [[ ! -x target/lmfiles.exe ]] || binary=target/lmfiles.exe
  [[ -x "$binary" ]] || { echo "native binary missing; run scripts/build-native.sh" >&2; exit 2; }
  host=("$binary")
fi

schema_file=schema/lmfiles.dsl.json
schema_tmp=$(mktemp)
trap 'rm -f "$schema_tmp"' EXIT
"${host[@]}" --dsl-schema --json > "$schema_tmp"
cmp -s "$schema_tmp" "$schema_file" || { echo "generated schema is stale" >&2; exit 1; }
python3 scripts/verify-deps.py
"${host[@]}" --dsl-signatures > "$schema_tmp.signatures"
lmscript --dsl-signatures "$schema_file" > "$schema_tmp.expected"
diff -u "$schema_tmp.expected" "$schema_tmp.signatures"
"${host[@]}" --version | grep -q '^lmfiles 0\.1\.0$'
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

# Fresh processes cover same-DB reopen, commit killpoints, lock ownership and
# optional network syscall denial. The harness fails on missing request/lock APIs.
run 240 python3 scripts/test-processes.py --command "$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' "${host[@]}")"

# Invalid memory configuration must fail before a host operation is attempted.
if LMFILES_MAX_MEMORY_MB=invalid "${host[@]}" --version >/dev/null 2>"$fixture/memory.err"; then
  echo "invalid memory budget was accepted" >&2; exit 1
fi
grep -q 'LMFILES_MAX_MEMORY_MB' "$fixture/memory.err"

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
python3 - "$fixture" <<'PYMEMORY'
import json,sys
from pathlib import Path
work=Path(sys.argv[1]);schema=(Path.cwd()/'schema/lmfiles.dsl.json').as_posix()
program='let p = _lmcode_ schema='+json.dumps(schema)+';\nlet values = str_list();\nfor (i in range(0,10000)) { values.add("'+'x'*128+'"); }\nend _lmcode_\nsavedsl(p,'+json.dumps((work/'memory.dslb').as_posix())+');\n'
(work/'memory.lms').write_text(program,encoding='utf-8')
PYMEMORY
run 30 lmscript "$fixture/memory.lms"
if LMFILES_MAX_MEMORY_MB=1 "${host[@]}" --dsl "$fixture/memory.dslb" >"$fixture/memory.json" 2>"$fixture/memory.err"; then
  echo 'runaway allocation was accepted' >&2; exit 1
fi
grep -q 'LMFILES_MAX_MEMORY_MB' "$fixture/memory.err"
grep -q 'false' "$fixture/memory.json"

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
