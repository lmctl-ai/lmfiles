#!/usr/bin/env python3
"""Unit tests for the scale runner's safety boundary; no large disk use."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from unittest import mock

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("test_scale", HERE / "test-scale.py")
scale = importlib.util.module_from_spec(spec)
assert spec.loader
spec.loader.exec_module(scale)


class ScaleGuardTests(unittest.TestCase):
    def test_relative_executable_resolves_before_fixture_cwd(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);program=base/"tool";program.write_text("#!/bin/sh\nexit 0\n");program.chmod(0o755)
            import os
            previous=Path.cwd()
            try:
                os.chdir(base)
                resolved=scale.resolve_command(["./tool"],base)
            finally:os.chdir(previous)
            self.assertEqual(resolved[0],str(program.resolve()))

    def test_low_free_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-test-") as td:
            guard = scale.DiskGuard(Path(td), read_usage=lambda: (scale.HARD_MIN_FREE - 1, 0))
            with self.assertRaises(scale.GuardViolation):
                guard.check("build", "before")

    def test_directory_cap_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-test-") as td:
            guard = scale.DiskGuard(Path(td), read_usage=lambda: (scale.HARD_MIN_FREE, scale.HARD_MAX_DIR_BYTES + 1))
            with self.assertRaises(scale.GuardViolation):
                guard.check("build", "monitor")

    def test_limits_cannot_be_lowered(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(ValueError):
                scale.DiskGuard(Path(td), min_free=1)
            with self.assertRaises(ValueError):
                scale.DiskGuard(Path(td), max_dir_bytes=scale.HARD_MAX_DIR_BYTES + 1)

    def test_healthy_child_and_jsonl_capture(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-test-") as td:
            work, log = Path(td) / "work", Path(td) / "progress.jsonl"
            work.mkdir()
            child = Path(td) / "child.py"
            child.write_text("import json; print(json.dumps({'event':'progress'}), flush=True); print(json.dumps({'event':'result','status':'ok'}), flush=True)\n")
            guard = scale.DiskGuard(work, read_usage=lambda: (scale.HARD_MIN_FREE, 0))
            run = scale.ManagedChild([sys.executable, str(child)], work, guard, timeout=5, stdout_log=log)
            result = run.run("build", 1)
            self.assertEqual(result["returncode"], 0)
            self.assertEqual(json.loads(log.read_text().splitlines()[-1])["event"], "result")
            self.assertTrue(any(r["event"] == "before" for r in run.readings))

    def test_violation_kills_process_group(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-test-") as td:
            work = Path(td) / "work"; work.mkdir()
            child = Path(td) / "child.py"
            child.write_text("import time; print('started', flush=True); time.sleep(30)\n")
            reads = iter([(scale.HARD_MIN_FREE, 0), (scale.HARD_MIN_FREE - 1, 0)])
            guard = scale.DiskGuard(work, read_usage=lambda: next(reads, (scale.HARD_MIN_FREE - 1, 0)))
            run = scale.ManagedChild([sys.executable, str(child)], work, guard, timeout=10, sample_seconds=.01)
            with self.assertRaises(scale.GuardViolation):
                run.run("build", 1)
            self.assertIsNotNone(run._proc)
            self.assertIsNotNone(run._proc.poll())

    def test_cleanup_on_interrupt_path_is_safe(self):
        work = Path(tempfile.mkdtemp(prefix="lmfiles-scale-test-"))
        try:
            self.assertTrue(work.is_dir())
        finally:
            import shutil
            shutil.rmtree(work, ignore_errors=True)
        self.assertFalse(work.exists())

    def test_sparse_file_counts_logical_size_and_ignores_symlink(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-test-") as td:
            work = Path(td)
            sparse = work / "sparse"
            with sparse.open("wb") as stream:
                stream.seek(scale.HARD_MAX_DIR_BYTES + 1)
                stream.write(b"x")
            link = work / "link"
            link.symlink_to(sparse)
            self.assertGreater(scale.directory_bytes(work), scale.HARD_MAX_DIR_BYTES)

    def test_timeout_kills_child(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-test-") as td:
            work = Path(td) / "work"; work.mkdir()
            child = Path(td) / "child.py"
            child.write_text("import time; time.sleep(30)\n")
            guard = scale.DiskGuard(work, read_usage=lambda: (scale.HARD_MIN_FREE, 0))
            run = scale.ManagedChild([sys.executable, str(child)], work, guard, timeout=.1, sample_seconds=.01)
            with self.assertRaises(TimeoutError):
                run.run("build", 1)
            self.assertIsNotNone(run._proc.poll())

    def test_monitor_read_error_fails_closed(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-test-") as td:
            work = Path(td) / "work"; work.mkdir()
            child = Path(td) / "child.py"
            child.write_text("import time; time.sleep(30)\n")
            readings = iter([(scale.HARD_MIN_FREE, 0)])
            def broken():
                try:
                    return next(readings)
                except StopIteration:
                    raise OSError("synthetic stat failure")
            guard = scale.DiskGuard(work, read_usage=broken)
            run = scale.ManagedChild([sys.executable, str(child)], work, guard, timeout=5, sample_seconds=.01)
            with self.assertRaises(OSError):
                run.run("build", 1)
            self.assertIsNotNone(run._proc.poll())

    def test_crash_kill_ready_protocol(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-test-") as td:
            work = Path(td) / "work"; work.mkdir()
            child = Path(td) / "child.py"
            child.write_text("import json,time,pathlib; pathlib.Path('.lmfiles-scale-marker').touch(); print(json.dumps({'event':'kill_ready'}), flush=True); time.sleep(30)\n")
            guard = scale.DiskGuard(work, read_usage=lambda: (scale.HARD_MIN_FREE, 0))
            run = scale.ManagedChild([sys.executable, str(child)], work, guard, timeout=5, sample_seconds=.01)
            result = run.run("crash", 1)
            self.assertEqual(result["returncode"], -9)
            self.assertEqual(result["status"], "ok")

    def test_monitor_runtime_error_fails_closed(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-test-") as td:
            work = Path(td) / "work"; work.mkdir()
            child = Path(td) / "child.py"
            child.write_text("import time; time.sleep(30)\n")
            reads = iter([(scale.HARD_MIN_FREE, 0)])
            def broken():
                if next(reads, None) is not None:
                    return scale.HARD_MIN_FREE, 0
                raise RuntimeError("synthetic monitor failure")
            guard = scale.DiskGuard(work, read_usage=broken)
            run = scale.ManagedChild([sys.executable, str(child)], work, guard, timeout=5, sample_seconds=.01)
            with self.assertRaises(OSError):
                run.run("build", 1)
            self.assertIsNotNone(run._proc.poll())

    def test_marker_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-test-") as td:
            work = Path(td) / "work"; work.mkdir()
            target = Path(td) / "outside-marker"; target.write_text("marker")
            child = Path(td) / "child.py"
            child.write_text("import json, pathlib; pathlib.Path('.lmfiles-scale-marker').symlink_to(" + repr(str(target)) + "); print(json.dumps({'event':'result','status':'ok'}), flush=True)\n")
            guard = scale.DiskGuard(work, read_usage=lambda: (scale.HARD_MIN_FREE, 0))
            run = scale.ManagedChild([sys.executable, str(child)], work, guard, timeout=5)
            with self.assertRaises(scale.PhaseFailure):
                run.run("crash_prepare", 1)

    def test_final_result_survives_progress_cap(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-test-") as td:
            work = Path(td) / "work"; work.mkdir()
            child = Path(td) / "child.py"
            child.write_text("import json; [print(json.dumps({'event':'progress','i':i}), flush=True) for i in range(1101)]; print(json.dumps({'event':'result','status':'ok','count':1101}), flush=True)\n")
            guard = scale.DiskGuard(work, read_usage=lambda: (scale.HARD_MIN_FREE, 0))
            run = scale.ManagedChild([sys.executable, str(child)], work, guard, timeout=5)
            with redirect_stdout(StringIO()):
                result = run.run("build", 1)
            self.assertEqual(result["worker_result"]["count"], 1101)

    def _campaign_command(self, directory: Path, *, fail_phase: str | None = None) -> list[str]:
        worker = directory / "worker.py"
        worker.write_text(
            "import json,sys,pathlib\n"
            "a=sys.argv; work=pathlib.Path(a[a.index('--scale-phase')+1]); phase=a[a.index('--scale-phase')+2]\n"
            f"if {fail_phase!r} is not None and phase == {fail_phase!r}: raise SystemExit(7)\n"
            "if phase == 'crash': print(json.dumps({'event':'kill_ready'}), flush=True); import time; time.sleep(30)\n"
            "print(json.dumps({'event':'result','status':'ok','phase':phase}), flush=True)\n"
        )
        return [sys.executable, str(worker)]

    def test_campaign_success_cleans_fixture(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-campaign-") as td:
            root = Path(td); work = Path(tempfile.mkdtemp(prefix="lmfiles-scale-")); report = root / "report.json"
            ns = type("Args", (), {"steps": [1], "timeout": 5, "progress_log": None})()
            command = self._campaign_command(root)
            guard_type = scale.DiskGuard
            with mock.patch.object(scale, "DiskGuard", lambda p: guard_type(p, read_usage=lambda: (60_000_000_000, 0))), redirect_stdout(StringIO()):
                self.assertEqual(scale.campaign(ns, command, report, work), 0)
            self.assertFalse(work.exists()); self.assertTrue(json.loads(report.read_text())["cleanup_verified"])

    def test_campaign_child_failure_cleans_fixture(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-campaign-") as td:
            root = Path(td); work = Path(tempfile.mkdtemp(prefix="lmfiles-scale-")); report = root / "report.json"
            ns = type("Args", (), {"steps": [1], "timeout": 5, "progress_log": None})()
            command = self._campaign_command(root, fail_phase="verify")
            guard_type = scale.DiskGuard
            with mock.patch.object(scale, "DiskGuard", lambda p: guard_type(p, read_usage=lambda: (60_000_000_000, 0))), redirect_stdout(StringIO()):
                self.assertEqual(scale.campaign(ns, command, report, work), 1)
            self.assertFalse(work.exists()); self.assertEqual(json.loads(report.read_text())["state"], "failed")

    def test_campaign_marker_write_failure_still_cleans_fixture(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-scale-campaign-") as td:
            root = Path(td); work = Path(tempfile.mkdtemp(prefix="lmfiles-scale-")); report = root / "report.json"
            ns = type("Args", (), {"steps": [1], "timeout": 5, "progress_log": None})()
            command = self._campaign_command(root)
            original = scale.Path.write_text
            def fail_marker(path, *args, **kwargs):
                if path.name == ".lmfiles-scale-marker": raise OSError("synthetic marker failure")
                return original(path, *args, **kwargs)
            with mock.patch.object(scale.Path, "write_text", fail_marker), redirect_stdout(StringIO()):
                self.assertEqual(scale.campaign(ns, command, report, work), 1)
            self.assertFalse(work.exists())


if __name__ == "__main__":
    unittest.main()
