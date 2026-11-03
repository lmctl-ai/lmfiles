#!/usr/bin/env python3
"""Compare current run_lmcode results with pre-adoption CLI observations."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

host = json.loads(sys.argv[1])
compiler = os.environ.get("LMSCRIPT_BIN", "lmscript")
schema = str((Path.cwd() / "schema/lmfiles.dsl.json").resolve())
fixture = Path("tests/protocol-parity-old.json")
identity = '{"id":"same-input"}'
fields = '{"content_hash":"0123456789abcdef","size":"5"}'
empty_json = json.dumps("{}")
observation = '{"hash":"fedcba9876543210","size":"5","mtime":"1700000000","present":true,"algorithm":"SHA-256"}'
stage = '{"stage":"fetched","namespace":"parity","status":"ok","source_revision":"1","timestamp":"2026-01-02T03:04:05Z"}'
alias = '{"kind":"path","namespace":"parity","value":"same-path"}'

with tempfile.TemporaryDirectory(prefix="lmfiles-parity-") as tmp:
    root = Path(tmp)
    paths = {name: str(root / f"{name}.mv") for name in ("doctor", "upsert", "observe", "stage", "alias")}
    old_root = root / "old"
    old_root.mkdir()
    old_paths = {name: str(old_root / f"{name}.mv") for name in paths}
    host_command = json.dumps(host + ["--lmcode", "$DSLFILE"])

    source = root / "parity.lms"
    source.write_text(
        f'let p = _lmcode_ schema={json.dumps(schema)};\n'
        f'let d=registry.open({json.dumps(paths["doctor"])},"parity","{{}}"); $return.doctor=d.doctor("{{}}"); d.close();\n'
        f'let u=registry.open({json.dumps(paths["upsert"])},"parity","{{}}"); $return.upsert=u.item_upsert({json.dumps(identity)},{json.dumps(fields)}); u.close();\n'
        f'let o=registry.open({json.dumps(paths["observe"])},"parity","{{}}"); o.item_upsert({json.dumps(identity)},{empty_json}); $return.observe=o.observe("1",{json.dumps(observation)}); o.close();\n'
        f'let s=registry.open({json.dumps(paths["stage"])},"parity","{{}}"); s.item_upsert({json.dumps(identity)},{empty_json}); $return.stage=s.stage_record("1",{json.dumps(stage)}); s.close();\n'
        f'let a=registry.open({json.dumps(paths["alias"])},"parity","{{}}"); a.item_upsert({json.dumps(identity)},{empty_json}); $return.alias=a.alias_add("1",{json.dumps(alias)}); a.close();\n'
        'end _lmcode_\n'
        f'let result=run_lmcode(p,{{command:{host_command}}}); assert(result.ok,json_stringify(result));\n'
        f'write_json({json.dumps(str(root / "new.json"))},result.result);\n', encoding="utf-8")
    result = subprocess.run([compiler, str(source)], capture_output=True, text=True)
    assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
    current = json.loads((root / "new.json").read_text(encoding="utf-8"))
    current = {key: json.loads(value) if isinstance(value, str) else value for key, value in current.items()}

    baseline_command = os.environ.get("LMFILES_OLD_HOST")
    if baseline_command:
        old_host = json.loads(baseline_command)
        def old_request(name, operation, *args):
            result = subprocess.run(old_host + ["--request", old_paths[name], operation, *args],
                                    capture_output=True, text=True)
            assert result.returncode == 0, (operation, result.returncode, result.stdout, result.stderr)
            return json.loads(result.stdout)
        expected = {
            "doctor": old_request("doctor", "doctor"),
            "upsert": old_request("upsert", "item_upsert", identity, fields),
        }
        old_request("observe", "item_upsert", identity, "{}")
        expected["observe"] = old_request("observe", "observe", "1", observation)
        old_request("stage", "item_upsert", identity, "{}")
        expected["stage"] = old_request("stage", "stage_record", "1", stage)
        old_request("alias", "item_upsert", identity, "{}")
        expected["alias"] = old_request("alias", "alias_add", "1", alias)
        if fixture.exists():
            assert json.loads(fixture.read_text(encoding="utf-8")) == expected
        else:
            fixture.parent.mkdir(parents=True, exist_ok=True)
            fixture.write_text(json.dumps(expected, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    expected = json.loads(fixture.read_text(encoding="utf-8"))
    assert current == expected, {"expected": expected, "current": current}
    print("PASS pre-adoption parity: doctor, item_upsert, observe, stage_record, alias_add")
