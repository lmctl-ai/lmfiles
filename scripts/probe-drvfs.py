#!/usr/bin/env python3
"""Characterize the mounted E: volume used by the optional scale campaign.

This probe is deliberately small and self-cleaning.  It writes only below a
fresh ``/mnt/e/lmfiles-scale-probe-*`` directory and never treats a passing
result as evidence that the volume is suitable for lmfiles durability.
"""
from __future__ import annotations

import errno
import fcntl
import json
import os
from pathlib import Path
import subprocess
import signal
import sys
import time
import uuid


ROOT = Path("/mnt/e")


def main() -> int:
    if not ROOT.is_dir():
        print(json.dumps({"status": "unavailable", "reason": "/mnt/e is not mounted"}))
        return 0
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt
    previous_handlers = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGINT, signal.SIGTERM)}
    run_id = uuid.uuid4().hex[:12]
    work = ROOT / f"lmfiles-scale-probe-{run_id}"
    result: dict[str, object] = {"mount": "/mnt/e", "work": "dedicated", "status": "pass"}
    try:
        work.mkdir()
        # fsync return semantics.
        sync_path = work / "fsync.bin"
        try:
            with open(sync_path, "wb", buffering=0) as stream:
                stream.write(b"probe")
                os.fsync(stream.fileno())
            result["fsync"] = "returned"
        except OSError as exc:
            result["fsync"] = f"error:{exc.errno}"
            result["status"] = "limited"

        # A second process must be unable to acquire the lock while held here.
        lock_path = work / "lock.bin"
        with open(lock_path, "w+b", buffering=0) as stream:
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX)
            child = subprocess.run(
                [sys.executable, "-c", "import sys; from pathlib import Path; "
                 "import fcntl, errno; p=Path(sys.argv[1]); "
                 "f=p.open('r+b', buffering=0); "
                 "\ntry: fcntl.flock(f.fileno(), fcntl.LOCK_EX|fcntl.LOCK_NB)\nexcept OSError as e: sys.exit(0 if e.errno in (errno.EACCES, errno.EAGAIN) else 2)\nelse: sys.exit(1)", str(lock_path)],
                check=False, timeout=5,
            )
            result["flock_exclusion"] = "excluded" if child.returncode == 0 else f"unexpected:{child.returncode}"
            if child.returncode != 0:
                result["status"] = "limited"
            fcntl.flock(stream.fileno(), fcntl.LOCK_UN)

        # A concurrent reader should observe complete old/new payloads, never
        # a missing or partially replaced file.  os.replace is the operation
        # under test; the reader runs in a separate process to exercise the
        # mount's cross-process visibility.
        rename_path = work / "rename.bin"
        rename_path.write_bytes(b"old" * 1024)
        reader_code = ("import pathlib,sys; p=pathlib.Path(sys.argv[1]); "
                       "ok={b'old'*1024,b'new'*1024}; "
                       "\nfor _ in range(3000):\n"
                       " try: data=p.read_bytes()\n"
                       " except FileNotFoundError: sys.exit(2)\n"
                       " if data not in ok: sys.exit(1)\n"
                       "sys.exit(0)")
        reader = subprocess.Popen([sys.executable, "-c", reader_code, str(rename_path)])
        replacements = 0
        try:
            for index in range(400):
                rename_tmp = work / f"rename-{index}.tmp"
                rename_tmp.write_bytes((b"new" if index % 2 else b"old") * 1024)
                os.replace(rename_tmp, rename_path)
                replacements += 1
            reader_code_result = reader.wait(timeout=8)
        finally:
            if reader.poll() is None:
                reader.kill()
                reader.wait(timeout=5)
        result["rename_visibility"] = "atomic" if reader_code_result == 0 else f"unexpected:{reader_code_result}"
        result["rename_iterations"] = replacements
        if reader_code_result != 0:
            result["status"] = "limited"

        # Kill a writer after it has emitted complete JSONL records and a
        # torn tail.  This is a characterization only: a recoverable prefix
        # does not prove fsync durability.
        partial_path = work / "partial.bin"
        ready_path = work / "partial.ready"
        writer_code = ("import json,os,sys,time; p=sys.argv[1]; ready=sys.argv[2]; "
                       "f=open(p,'wb',buffering=0); "
                       "[f.write((json.dumps({'seq':i,'payload':'A'*64})+'\\n').encode()) for i in range(20)]; "
                       "f.flush(); os.fsync(f.fileno()); f.write(b'{\"seq\":20,\"payload\":\"AAAA'); f.flush(); "
                       "open(ready,'wb').close(); time.sleep(30)")
        writer = subprocess.Popen(
            [sys.executable, "-c", writer_code, str(partial_path), str(ready_path)]
        )
        try:
            deadline = time.monotonic() + 5
            while not ready_path.exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            if not ready_path.exists():
                writer.kill()
                writer.wait(timeout=5)
                raise RuntimeError("writer did not reach deterministic mid-write point")
            writer.kill()
            writer.wait(timeout=5)
            data = partial_path.read_bytes() if partial_path.exists() else b""
            lines = data.splitlines()
            complete = 0
            for line in lines:
                try:
                    json.loads(line)
                    complete += 1
                except json.JSONDecodeError:
                    break
            recoverable = writer.returncode == -9 and complete == 20 and lines[-1] != b""
        finally:
            if writer.poll() is None:
                writer.kill()
                writer.wait(timeout=5)
        result["kill_midwrite"] = "jsonl-prefix-and-torn-tail" if recoverable else f"unexpected:{writer.returncode}"
        result["kill_midwrite_complete_records"] = complete if 'complete' in locals() else 0
        if not recoverable:
            result["status"] = "limited"
    finally:
        for sig, handler in previous_handlers.items():
            signal.signal(sig, handler)
        # The path is uniquely generated and never follows links.
        if work.exists() and not work.is_symlink():
            for child in work.iterdir():
                if child.is_file() or child.is_symlink():
                    child.unlink()
            work.rmdir()
    result["cleanup"] = "removed"
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
