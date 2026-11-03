#!/usr/bin/env python3
"""Invocation and effect regressions for the current lmcode host surface."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

host = sys.argv[1:]
assert host, "host command is required"
compiler = os.environ.get("LMSCRIPT_BIN") or shutil.which("lmscript.cmd" if os.name == "nt" else "lmscript") or "lmscript"

with tempfile.TemporaryDirectory(prefix="lmfiles-protocol-") as tmp:
    root = Path(tmp)
    missing = root / "must-not-open.lmcode"
    manifest = root / "invocation.json"
    env = dict(os.environ, LMFILES_MAX_MEMORY_MB="256")

    for dry in ("missing", "string"):
        metadata = {"version": 1, "mode": "single", "parts": [
            {"index": 0, "file": str(missing)}
        ]}
        if dry == "string":
            metadata["dry"] = "true"
        manifest.write_text(json.dumps(metadata), encoding="utf-8")
        env["LMCODE_INVOCATION_FILE"] = str(manifest)
        result = subprocess.run([*host, "--lmcode", str(missing)], env=env,
                                capture_output=True, text=True)
        assert result.returncode == 2, (f"{dry} dry must fail before dispatch", result.returncode,
                                        result.stdout, result.stderr)
        assert "\"phase\":\"cli\"" in result.stdout, result.stdout
        assert not missing.exists(), "invalid manifest caused input creation"

    for flag in ("--first", "--last", "--dry-run", "--cli-schema", "--dsl-schema"):
        result = subprocess.run([*host, flag, str(missing)], env=env,
                                capture_output=True, text=True)
        assert result.returncode == 2, (flag, result.returncode, result.stdout, result.stderr)
        assert not missing.exists(), (flag, "retired option reached file input")

    compiler = os.environ.get("LMSCRIPT_BIN", "lmscript")
    schema = Path("schema/lmfiles.dsl.json").resolve()
    host_argv = json.dumps(host)
    wrapper = root / "host-wrapper.py"
    wrapper.write_text(
        "#!/usr/bin/env python3\n"
        "import json, os, subprocess, sys\n"
        "from pathlib import Path\n"
        f"host = {host_argv}\n"
        f"proof = Path({json.dumps(str(root / 'dry-proof.jsonl'))})\n"
        "manifest = Path(os.environ['LMCODE_INVOCATION_FILE'])\n"
        "if os.environ.get('LMFILES_TEST_SET_DRY') == '1':\n"
        "    value = json.loads(manifest.read_text())\n"
        "    value['dry'] = True\n"
        "    manifest.chmod(0o600)\n"
        "    manifest.write_text(json.dumps(value))\n"
        "result = subprocess.run(host + sys.argv[1:], capture_output=True)\n"
        "if os.environ.get('LMFILES_TEST_SET_DRY') == '1':\n"
        "    raw = result.stdout.decode('utf-8')\n"
        "    begin, body = raw.split('LMCODE_RETURN_BEGIN\\n', 1)\n"
        "    payload, end = body.split('\\nLMCODE_RETURN_END\\n', 1)\n"
        "    value = json.loads(payload)\n"
        "    assert value.get('dry') is True, value\n"
        "    with proof.open('a', encoding='utf-8') as stream: stream.write(json.dumps(value) + '\\n')\n"
        "    value.pop('dry')\n"
        "    result = subprocess.CompletedProcess(result.args, result.returncode, (begin + 'LMCODE_RETURN_BEGIN\\n' + json.dumps(value,separators=(',',':')) + '\\nLMCODE_RETURN_END\\n' + end).encode(), result.stderr)\n"
        "sys.stdout.buffer.write(result.stdout); sys.stderr.buffer.write(result.stderr); sys.exit(result.returncode)\n",
        encoding="utf-8")
    wrapper.chmod(0o755)
    wrapper_command = json.dumps([sys.executable, str(wrapper)])
    first = root / "first.sentinel"
    second = root / "second.sentinel"
    first_db = root / "first.mv"
    second_db = root / "second.mv"
    source = root / "protocol.lms"
    source.write_text(
        f'let schema = load_schema(schema={json.dumps(str(schema))});\n'
        'let first = _lmcode_ schema;\n'
        f'file_write({json.dumps(str(first))}, "first");\n'
        f'let db=registry.open({json.dumps(str(first_db))},"protocol","{{}}");\n'
        'db.item_upsert("{\\"id\\":\\"first\\"}", "{}"); db.close();\n'
        'lmcode_return_set("first-result");\n'
        'end _lmcode_\n'
        'let second = _lmcode_ schema;\n'
        f'file_write({json.dumps(str(second))}, "second");\n'
        f'let db=registry.open({json.dumps(str(second_db))},"protocol","{{}}");\n'
        'db.item_upsert("{\\"id\\":\\"second\\"}", "{}"); db.close();\n'
        'lmcode_return_set("second-result");\n'
        'end _lmcode_\n'
        f'let single = run_lmcode(first, {{command: {wrapper_command[:-1]}, "--lmcode", "$DSLFILE"]}});\n'
        'assert(single.ok && single.result == "first-result", "single result");\n'
        f'let builder = lmcode_build({wrapper_command});\n'
        'builder.add(first); builder.add(second);\n'
        'let aggregate = run_lmcode(builder);\n'
        'assert(aggregate.ok && aggregate.parts == 2 && aggregate.result[0] == "first-result" && aggregate.result[1] == "second-result", "ordered builder results");\n'
        'if (env("LMFILES_TEST_SET_DRY", "0") != "1") {\n'
        '  let wide = _lmcode_ schema;\n'
        f'let db=registry.open({json.dumps(str(root / "wide.mv"))},"wide","{{}}");\n'
        '  let cursor=db.query_subset("wide","[]","{}"); $return.page=cursor.next_json(1,20000000); cursor.close(); db.close();\n'
        'end _lmcode_\n'
        f'  let page=run_lmcode(wide, {{command: {wrapper_command[:-1]}, "--lmcode", "$DSLFILE"]}});\n'
        '  assert(page.ok && page.result.page == "[]", "caller-selected result page above 16 MiB");\n'
        '}\n'
        'println("PASS lmcode single and builder invocations");\n', encoding="utf-8")

    dry_env = dict(os.environ, LMFILES_TEST_SET_DRY="1", LMFILES_MAX_MEMORY_MB="256")
    result = subprocess.run([compiler, str(source)], env=dry_env, capture_output=True, text=True)
    assert result.returncode == 0 and "PASS lmcode single and builder invocations" in result.stdout, (result.returncode, result.stdout, result.stderr)
    assert not first.exists() and not second.exists() and not first_db.exists() and not second_db.exists(), "dry invocation called an effectful file or registry callback"
    dry_records = [json.loads(line) for line in (root / "dry-proof.jsonl").read_text(encoding="utf-8").splitlines()]
    assert len(dry_records) == 2 and all(record.get("dry") is True for record in dry_records), dry_records

    result = subprocess.run([compiler, str(source)], env=dict(os.environ, LMFILES_MAX_MEMORY_MB="256"),
                            capture_output=True, text=True)
    assert result.returncode == 0 and "PASS lmcode single and builder invocations" in result.stdout, (result.returncode, result.stdout, result.stderr)
    assert first.read_text(encoding="utf-8") == "first" and second.read_text(encoding="utf-8") == "second"
    assert first_db.exists() and second_db.exists(), "real invocation skipped registry writes"

print("PASS strict manifest, no-effects dry mode, builder, real mode and retired route controls")
