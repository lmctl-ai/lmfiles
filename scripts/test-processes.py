#!/usr/bin/env python3
"""Fresh-process durability and safety checks for the lmfiles host.

The command is supplied as JSON so the same harness can test the managed JVM
launcher and a GraalVM native executable without embedding a build layout.
No network endpoint is contacted by this script.
"""
from __future__ import annotations

import argparse
import json
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time


def run(cmd: list[str], *, env: dict[str, str] | None = None, check: bool = True,
        timeout: int = 30) -> subprocess.CompletedProcess[str]:
    merged = os.environ.copy()
    if env:
        merged.update(env)
    return subprocess.run(cmd, env=merged, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout, check=check)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--command", help="JSON array used to launch lmfiles")
    ap.add_argument("command_args", nargs="*", help="alternative executable and arguments")
    ap.add_argument("--keep", action="store_true", help="keep the temporary fixture")
    ns = ap.parse_args()
    if ns.command:
        base = json.loads(ns.command)
        if not isinstance(base, list) or not base or not all(isinstance(x, str) for x in base):
            ap.error("--command must be a non-empty JSON string array")
    elif ns.command_args:
        base = ns.command_args
    else:
        ap.error("supply --command '[...]' or an executable")

    root = Path(tempfile.mkdtemp(prefix="lmfiles-process-"))
    passed = 0
    try:
        def call(*args: str, env: dict[str, str] | None = None, check: bool = True,
                 timeout: int = 30):
            return run(base + list(args), env=env, check=check, timeout=timeout)

        # A self-test is the same deterministic CoreChecks used by managed and native gates.
        selftest = call("--self-test", str(root / "self-test"), timeout=120)
        assert selftest.returncode == 0, selftest.stderr
        report = json.loads(selftest.stdout.strip().splitlines()[-1])
        assert report.get("ok") is True and int(report.get("assertions", 0)) >= 1
        passed += 1

        version = call("--version")
        assert version.stdout.startswith("lmfiles "), version.stdout
        schema = call("--dsl-schema", "--json")
        parsed = json.loads(schema.stdout)
        assert parsed.get("dsl.schema") == 2 and parsed.get("name") == "lmfiles"
        passed += 2

        # Exercise fresh processes against one local DB. This is mandatory: a
        # process harness that silently skips the DB is not a registry test.
        db = root / "state.mv"
        identity = json.dumps({"id": "fixture-1"}, separators=(",", ":"))
        fields = json.dumps({"content_hash": "aa"}, separators=(",", ":"))
        result = json.dumps({"stage": "fetched", "namespace": "fixture", "status": "ok",
                             "source_revision": "1"}, separators=(",", ":"))
        create = call("--request", str(db), "item_upsert", identity, fields)
        created = json.loads(create.stdout)
        assert created.get("status") == "ok" and created.get("item")
        item = created["item"]
        recorded = call("--request", str(db), "stage_record", item, result)
        assert json.loads(recorded.stdout).get("status") == "ok"
        reopened = call("--query", str(db), "query_subset", "cli", json.dumps([item]), "{}")
        assert item in reopened.stdout and '"state":"unknown"' in reopened.stdout, reopened.stdout
        assert json.loads(call("--request", str(db), "doctor").stdout).get("status") == "ok"
        passed += 3

        # A kill at each commit boundary must leave a valid same-DB state. The
        # after-commit point may contain the row; before-commit must not.
        for point, expect_item in (("before_commit", False), ("after_commit", True)):
            killed_db = root / f"kill-{point}.mv"
            call("--request", str(killed_db), "doctor")
            killed = call("--request", str(killed_db), "item_upsert", identity, fields,
                          env={"LMFILES_KILL_POINT": point}, check=False)
            assert killed.returncode == 77, killed.stderr
            check = call("--request", str(killed_db), "doctor")
            assert json.loads(check.stdout).get("status") == "ok"
            rows = call("--query", str(killed_db), "query_subset", "cli", json.dumps(["1"]), "{}").stdout
            if expect_item:
                assert '"state":"unknown"' in rows and '"no-baseline"' in rows, rows
            else:
                assert '"state":"unknown"' in rows and '"no-item"' in rows, rows
            passed += 1

        # Compaction faults preserve a previously acknowledged generation.
        compact_db=root/'compact.mv'
        call('--request',str(compact_db),'item_upsert',identity,fields)
        for point in ('before_compaction','after_compaction'):
            killed=call('--request',str(compact_db),'compact',env={'LMFILES_KILL_POINT':point},check=False)
            assert killed.returncode==77,killed.stderr
            assert json.loads(call('--request',str(compact_db),'doctor').stdout)['items']=='1'
            passed+=1

        # Crash after a durable instruction/result, before DB replay.
        receipt_db=root/'receipt.mv';call('--request',str(receipt_db),'item_upsert',identity,fields)
        plan_path=root/'plan.jsonl';results_path=root/'results.jsonl'
        payload={'verb':'put','item':'1','stage':'published','target':'fixture','source_revision':'1','record_projection':'record'}
        instruction={'v':1,'kind':'instruction','job':'fixture','op':'put-a','payload':payload}
        killed=call('--log-append',str(plan_path),json.dumps(instruction),env={'LMFILES_KILL_POINT':'after_plan_sync'},check=False)
        assert killed.returncode==77 and plan_path.stat().st_size>0,killed.stderr
        passed+=1
        digest=hashlib.sha256(json.dumps(payload,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        result={'v':1,'kind':'result','job':'fixture','op':'put-a','result_id':'result-a','completes_instruction':True,'payload':{'instruction_digest':digest,'confirmed':True,'projection':{'kind':'record','item':'1','stage':'published','target':'fixture','status':'ok','source_revision':'1'}}}
        killed=call('--log-append',str(results_path),json.dumps(result),env={'LMFILES_KILL_POINT':'after_result_sync'},check=False)
        assert killed.returncode==77 and results_path.stat().st_size>0,killed.stderr
        assert json.loads(call('--apply-records',str(receipt_db),str(plan_path),str(results_path)).stdout)['applied']==1
        assert json.loads(call('--apply-records',str(receipt_db),str(plan_path),str(results_path)).stdout)['duplicates']==1
        passed+=1

        # --hold-lock is an intentional test-only CLI operation. It keeps the
        # same Store open, proving the OS lock rather than racing a short request.
        holder_db = root / "lock.mv"
        holder = subprocess.Popen(base + ["--hold-lock", str(holder_db), "1500"],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            assert holder.stdout is not None
            assert holder.stdout.readline().strip() == "ready", holder.stderr.read()
            maps=Path(f'/proc/{holder.pid}/maps')
            if maps.is_file():assert str(holder_db) not in maps.read_text(), 'registry DB was memory-mapped' 
            busy = call("--request", str(holder_db), "doctor", check=False)
            assert busy.returncode != 0 and ("writer-busy" in busy.stderr or "lock" in busy.stderr), busy.stderr
            passed += 1
        finally:
            holder.wait(timeout=5)

        # Network denial is optional evidence: strace is unavailable on macOS
        # and Windows. Never claim this check passed when the tracer is absent.
        tracer = shutil.which("strace")
        if tracer and sys.platform.startswith("linux"):
            traced = subprocess.run([tracer, "-f", "-e", "trace=network", "-qq", *base,
                                     "--self-test", str(root / "trace")], text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=120, check=False)
            assert traced.returncode == 0, traced.stderr
            # libc may contact its OS-local nscd UNIX socket and the JVM probes
            # socket capabilities. Neither is a remote registry operation.
            operations=[line for line in traced.stderr.splitlines() if any(x in line for x in ("connect(", "bind(", "listen(", "sendto(", "sendmsg("))]
            assert all("AF_UNIX" in line for line in operations), traced.stderr
            passed += 1
        else:
            print("network syscall trace skipped: strace unavailable", file=sys.stderr)
        print(json.dumps({"ok": True, "checks": passed, "fixture": str(root)}))
        return 0
    except (AssertionError, OSError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
        print(f"lmfiles process check failed: {exc}", file=sys.stderr)
        return 1
    finally:
        if ns.keep:
            print(f"fixture retained: {root}", file=sys.stderr)
        else:
            shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
