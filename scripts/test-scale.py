#!/usr/bin/env python3
"""Bounded, local scale runner for lmfiles.

Each phase is launched in its own process group and is watched by a hard disk
guard.  The defaults are intentionally conservative; this program never
creates a large fixture by itself and never contacts a network service.
"""
from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from typing import Callable, Iterable

PHASES = ("build", "verify", "update", "claim", "reopen", "crash_prepare", "crash", "recover", "doctor", "compact", "upgrade")
HARD_MIN_FREE = 40_000_000_000
HARD_MAX_DIR_BYTES = 25_000_000_000
DEFAULT_STEPS = (1_000_000, 5_000_000, 20_000_000)
DEFAULT_COMMAND = ["java", "-Xmx1536m", "-jar", "target/lmfiles-0.1.2-all.jar"]


def directory_bytes(path: Path) -> int:
    """Return allocated bytes below *path*, without following symlinks."""
    logical = allocated = 0
    if not path.exists():
        return 0
    for root, dirs, files in os.walk(path, followlinks=False):
        dirs[:] = [d for d in dirs if not (Path(root) / d).is_symlink()]
        for name in files:
            item = Path(root) / name
            try:
                stat = item.stat(follow_symlinks=False)
                logical += stat.st_size
                allocated += int(stat.st_blocks) * 512
            except FileNotFoundError:
                continue
    return max(logical, allocated)


class DiskGuard:
    """Disk policy with injectable readings for unit tests.

    Production callers cannot lower either hard limit.  ``read_usage`` is
    intentionally injectable so policy tests do not need to consume disk.
    """

    def __init__(self, work: Path, *, read_usage: Callable[[], tuple[int, int]] | None = None,
                 min_free: int = HARD_MIN_FREE, max_dir_bytes: int = HARD_MAX_DIR_BYTES):
        if min_free < HARD_MIN_FREE or max_dir_bytes > HARD_MAX_DIR_BYTES:
            raise ValueError("scale guard limits cannot be relaxed")
        self.work = Path(work)
        self.read_usage = read_usage or self._read_usage
        self.min_free = min_free
        self.max_dir_bytes = max_dir_bytes

    def _read_usage(self) -> tuple[int, int]:
        return shutil.disk_usage("/").free, directory_bytes(self.work)

    def reading(self, phase: str, event: str) -> dict:
        free, used = self.read_usage()
        state = "ok" if free >= self.min_free and used <= self.max_dir_bytes else "violation"
        return {"time": time.time(), "phase": phase, "event": event,
                "free_bytes": free, "dir_bytes": used, "state": state}

    def check(self, phase: str, event: str) -> dict:
        item = self.reading(phase, event)
        if item["state"] != "ok":
            raise GuardViolation(item)
        return item


class GuardViolation(RuntimeError):
    def __init__(self, reading: dict):
        super().__init__(f"disk guard violation: free={reading['free_bytes']} dir={reading['dir_bytes']}")
        self.reading = reading


class PhaseFailure(RuntimeError):
    def __init__(self, message: str, result: dict):
        super().__init__(message)
        self.result = result


class ManagedChild:
    """Run one phase, streaming bounded stdout JSONL and killing its group."""

    def __init__(self, command: list[str], work: Path, guard: DiskGuard, *, timeout: float,
                 stdout_log: Path | None = None, sample_seconds: float = .5):
        self.command, self.work, self.guard = command, work, guard
        self.timeout, self.stdout_log, self.sample_seconds = timeout, stdout_log, sample_seconds
        self.readings: list[dict] = []
        self._proc: subprocess.Popen[str] | None = None
        self._stop = threading.Event()
        self._violation: GuardViolation | None = None
        self._timed_out = False
        self._monitor_error: Exception | None = None
        self.records: list[object] = []
        self.phase: str | None = None
        self.count: int | None = None
        self._kill_ready = False
        self.worker_result = None
        self._log_bytes = 0
        self._log_limit = 8 * 1024 * 1024

    def _kill(self) -> None:
        proc = self._proc
        if proc and proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                proc.kill()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                raise RuntimeError("child group did not terminate after SIGKILL")

    def _monitor(self, phase: str) -> None:
        deadline = time.monotonic() + self.timeout
        while not self._stop.wait(self.sample_seconds):
            if time.monotonic() >= deadline:
                self._timed_out = True
                self._kill()
                return
            try:
                self.readings.append(self.guard.check(phase, "monitor"))
            except GuardViolation as exc:
                self._violation = exc
                self._kill()
                return
            except Exception as exc:
                self._monitor_error = OSError("disk monitor failed closed: " + str(exc))
                self._kill()
                return

    def run(self, phase: str, count: int) -> dict:
        self.phase, self.count = phase, count
        self.readings.append(self.guard.check(phase, "before"))
        argv = self.command + ["--scale-phase", str(self.work), phase, str(count)]
        started = time.time()
        env = os.environ.copy()
        if phase == "crash":
            env["LMFILES_KILL_POINT"] = "scale_sigkill"
        self._proc = subprocess.Popen(argv, cwd=self.work, env=env, text=True,
                                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                      start_new_session=True, bufsize=1)
        monitor = threading.Thread(target=self._monitor, args=(phase,), daemon=True)
        monitor.start()
        records: list[object] = self.records
        raw_bytes = 0
        try:
            assert self._proc.stdout is not None
            with self._proc.stdout as source:
                for raw in source:
                    encoded = raw.encode("utf-8", "replace")
                    raw_bytes += len(encoded)
                    bad = re.search(r"hard-disk-guard: free=(\d+) dir=(\d+) phase=(\S+)", raw)
                    if bad:
                        self._violation = GuardViolation(dict(time=time.time(),phase=bad[3],event="worker_guard",free_bytes=int(bad[1]),dir_bytes=int(bad[2]),state="violation"))
                        self._kill()
                    try:
                        event = json.loads(raw)
                    except json.JSONDecodeError:
                        event = {"raw": raw.rstrip("\n")}
                    if isinstance(event, dict) and event.get("event") == "result":
                        self.worker_result = event
                    if len(records) < 1000 and raw_bytes <= self._log_limit:
                        records.append(event)
                    if phase == "crash" and isinstance(event, dict) and event.get("event") == "kill_ready":
                        self._kill_ready = True
                        self._kill()
                    print(raw.rstrip("\n"), flush=True)
                    if self.stdout_log and self._log_bytes < self._log_limit:
                        take = encoded[: self._log_limit - self._log_bytes]
                        with self.stdout_log.open("ab") as sink:
                            sink.write(take)
                        self._log_bytes += len(take)
            code = self._proc.wait()
            if self._timed_out:
                raise TimeoutError(f"phase {phase} exceeded {self.timeout:g}s")
            if self._monitor_error:
                raise self._monitor_error
            if self._violation:
                raise self._violation
            if phase == "crash":
                marker = self.work / ".lmfiles-scale-marker"
                if not self._kill_ready or code != -signal.SIGKILL or (not marker.is_file() or marker.is_symlink()):
                    raise PhaseFailure("crash kill protocol failed", {"phase": phase, "count": count,
                        "returncode": code, "stdout": records, "guard_readings": self.readings})
                return {"phase": phase, "count": count, "returncode": code, "status": "ok",
                        "stdout": records, "guard_readings": self.readings, "seconds": time.time() - started}
            if code != 0:
                result = {"phase": phase, "count": count, "returncode": code,
                          "stdout": records, "guard_readings": self.readings}
                raise PhaseFailure(f"phase {phase} exited {code}", result)
            self.readings.append(self.guard.check(phase, "after"))
            marker = self.work / ".lmfiles-scale-marker"
            if phase in ("crash_prepare", "recover") and (not marker.is_file() or marker.is_symlink()):
                raise PhaseFailure(f"{phase} did not produce required marker", {"phase": phase,
                    "count": count, "returncode": code, "stdout": records,
                    "guard_readings": self.readings})
            if self.worker_result is None:
                raise PhaseFailure("worker returned no final result", {"phase":phase,"count":count,"stdout":records})
            status = self.worker_result.get("status", "invalid_result")
            if status not in ("ok", "skipped_headroom"):
                raise PhaseFailure("worker result failed", self.worker_result)
            return {"phase": phase, "count": count, "returncode": code,
                    "status": status, "worker_result": self.worker_result, "stdout": records, "guard_readings": self.readings,
                    "seconds": time.time() - started}
        finally:
            self._stop.set()
            monitor.join(timeout=2)


def parse_steps(value: str) -> list[int]:
    try:
        result = [int(v) for v in value.split(",")]
    except ValueError as exc:
        raise argparse.ArgumentTypeError("steps must be comma-separated integers") from exc
    if not result or any(v <= 0 for v in result):
        raise argparse.ArgumentTypeError("steps must be positive")
    return result


def resolve_command(command: list[str], base: Path) -> list[str]:
    """Make executable and existing relative path arguments independent of cwd."""
    result = list(command)
    found = shutil.which(result[0])
    if found:
        result[0] = str(Path(found).resolve())
    for index, arg in enumerate(result[1:], 1):
        candidate = (base / arg).resolve()
        if not arg.startswith("-") and candidate.exists():
            result[index] = str(candidate)
    return result


def main(argv: Iterable[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--command", default=json.dumps(DEFAULT_COMMAND), help="JSON argv")
    ap.add_argument("--steps", type=parse_steps, default=list(DEFAULT_STEPS))
    ap.add_argument("--timeout", type=float, default=7200, help="per-phase timeout (1..7200 seconds)")
    ap.add_argument("--report", type=Path, default=Path.cwd() / ".lmfiles-local" / "scale-report.json")
    ap.add_argument("--progress-log", type=Path, help="external bounded child JSONL log")
    ns = ap.parse_args(argv)
    if not 1 <= ns.timeout <= 7200:
        ap.error("--timeout must be between 1 and 7200 seconds")
    try:
        command = json.loads(ns.command)
        if not isinstance(command, list) or not command or not all(isinstance(x, str) for x in command):
            raise ValueError
    except (ValueError, json.JSONDecodeError) as exc:
        ap.error("--command must be a non-empty JSON string array")
    command = resolve_command(command, Path.cwd())
    report = ns.report.resolve()
    report.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="lmfiles-scale-", dir="/tmp") as directory:
        return campaign(ns, command, report, Path(directory))


def campaign(ns, command, report, work):

    if report == work or work in report.parents:
        raise SystemExit("--report must be outside the dedicated /tmp fixture")
    if ns.progress_log and (ns.progress_log.resolve() == work or work in ns.progress_log.resolve().parents):
        raise SystemExit("--progress-log must be outside the dedicated /tmp fixture")
    payload = {"settings": {"command": command, "steps": ns.steps, "timeout": ns.timeout,
                             "min_free_bytes": HARD_MIN_FREE, "max_dir_bytes": HARD_MAX_DIR_BYTES,
                             "monitor_seconds": 0.5, "worker_batch_items": 512,
                             "work": str(work), "phases": list(PHASES)},
               "phases": [], "guard_readings": [], "state": "running"}
    print(json.dumps(payload["settings"], sort_keys=True), flush=True)
    guard = DiskGuard(work)
    current_runner: ManagedChild | None = None
    old_handlers = {}

    def stop_handler(signum, _frame):
        if current_runner is not None:
            current_runner._kill()
        raise KeyboardInterrupt

    for signum in (signal.SIGINT, signal.SIGTERM):
        old_handlers[signum] = signal.signal(signum, stop_handler)
    try:
        (work / ".lmfiles-scale-marker").write_text("lmfiles-scale-fixture-v1\n", encoding="ascii")
        for count in ns.steps:
            for phase in PHASES:
                reading = guard.check(phase, "phase-start")
                current_runner = ManagedChild(command, work, guard, timeout=ns.timeout, stdout_log=ns.progress_log)
                payload["phases"].append(current_runner.run(phase, count))
                payload["guard_readings"].extend(current_runner.readings)
                checkpoint=report.with_suffix(report.suffix+".tmp")
                checkpoint.write_text(json.dumps(payload,indent=2)+"\n",encoding="utf-8")
                checkpoint.replace(report)
        payload["state"] = "ok"
        return 0
    except (GuardViolation, TimeoutError, subprocess.SubprocessError, OSError, PhaseFailure) as exc:
        payload["state"] = "failed"
        payload["error"] = str(exc)
        if isinstance(exc, GuardViolation):
            payload["state"] = "guard_aborted"
            payload["guard_readings"].extend(current_runner.readings if current_runner else [])
            payload["guard_readings"].append(exc.reading)
            payload["phases"].append({"phase": exc.reading.get("phase"), "state": "guard_aborted",
                                      "guard_readings": [exc.reading], "error": str(exc)})
        elif isinstance(exc, PhaseFailure):
            payload["phases"].append(exc.result)
        elif current_runner is not None and current_runner.phase is not None:
            payload["phases"].append({"phase": current_runner.phase, "count": current_runner.count,
                                      "state": "failed", "error": str(exc),
                                      "stdout": current_runner.records,
                                      "guard_readings": current_runner.readings})
        return 1
    except KeyboardInterrupt:
        payload["state"] = "interrupted"
        return 130
    finally:
        if current_runner is not None:
            current_runner._kill()
            payload["guard_readings"].extend(current_runner.readings)
        for signum, handler in old_handlers.items():
            signal.signal(signum, handler)
        try:
            if current_runner is not None and current_runner._proc is not None:
                current_runner._proc.wait(timeout=5)
            readings=payload["guard_readings"]
            payload["minimum_free_bytes"]=min((r["free_bytes"] for r in readings),default=None)
            payload["maximum_dir_bytes"]=max((r["dir_bytes"] for r in readings),default=None)
            shutil.rmtree(work)
            payload["cleanup_verified"] = not work.exists()
            if not payload["cleanup_verified"]:
                raise RuntimeError(f"dedicated fixture cleanup failed: {work}")
        finally:
            report.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
