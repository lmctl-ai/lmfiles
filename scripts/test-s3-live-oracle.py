#!/usr/bin/env python3
"""No-network tests for the s3-live deterministic fixture helper."""
from __future__ import annotations
import base64, importlib.util, json, pathlib, tempfile, unittest

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("s3_live_oracle", HERE / "s3-live-oracle.py")
oracle = importlib.util.module_from_spec(spec); assert spec.loader; spec.loader.exec_module(oracle)


class LiveOracleTests(unittest.TestCase):
    def test_prepare_manifest_is_deterministic_and_bounded(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-s3-live-") as td:
            root = pathlib.Path(td); first = oracle.prepare(root)
            self.assertEqual(first["files"], 100)
            manifest = json.loads((oracle.state_dir(root) / "manifest.json").read_text())
            self.assertEqual(len(manifest["entries"]), 100)
            self.assertTrue(all(e["size"] <= 4096 and e["content_type"] == "text/plain" for e in manifest["entries"]))
            self.assertEqual(base64.b64decode(manifest["entries"][0]["body_b64"]).decode().split()[0], "synthetic")

    def test_delta_has_three_changes_two_adds_two_deletes_and_100_files(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-s3-live-") as td:
            root = pathlib.Path(td); oracle.prepare(root); result = oracle.mutate(root, "delta")
            self.assertEqual(result["files"], 100)
            ops = json.loads((oracle.state_dir(root) / "operations.json").read_text())
            self.assertEqual((ops["changed"], ops["adds"], ops["deletes"]), (3, 2, 2))

    def test_generation_resets_fixed_universe_and_soak_is_reproducible(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-s3-live-") as td:
            root = pathlib.Path(td); oracle.prepare(root); oracle.mutate(root, "delta")
            self.assertEqual(oracle.mutate(root, "generation")["files"], 100)
            first = (oracle.state_dir(root) / "modelexpected.json").read_bytes()
            oracle.mutate(root, "soak-2"); second = (oracle.state_dir(root) / "modelexpected.json").read_bytes()
            oracle.mutate(root, "generation"); oracle.mutate(root, "soak-2")
            self.assertEqual(second, (oracle.state_dir(root) / "modelexpected.json").read_bytes())
            self.assertNotEqual(first, second)

    def test_model_expected_is_independent_of_corrupted_tree(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-s3-live-") as td:
            root = pathlib.Path(td); oracle.prepare(root)
            expected = json.loads((oracle.state_dir(root) / "modelexpected.json").read_text())
            (oracle.state_dir(root) / "tree" / "synthetic-000.txt").write_bytes(b"corrupted")
            manifest = oracle.manifest_for(root, oracle.state_dir(root) / "tree", 0)
            row = next(x for x in manifest if x["key"] == "synthetic-000.txt")
            self.assertNotEqual(row["sha256"], expected["expected"]["synthetic-000.txt"]["sha256"])

    def test_generation_and_uncertain_change_hashes(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-s3-live-") as td:
            root = pathlib.Path(td); oracle.prepare(root)
            before = json.loads((oracle.state_dir(root) / "modelexpected.json").read_text())["expected"]
            oracle.mutate(root, "generation")
            generation = json.loads((oracle.state_dir(root) / "modelexpected.json").read_text())["expected"]
            self.assertEqual(sum(before[k]["sha256"] != generation[k]["sha256"] for k in before), 100)
            oracle.mutate(root, "uncertain")
            uncertain = json.loads((oracle.state_dir(root) / "modelexpected.json").read_text())["expected"]
            self.assertEqual(sum(generation[k]["sha256"] != uncertain[k]["sha256"] for k in generation), 1)

    def test_soak_replays_four_operations_without_resetting_bytes(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-s3-live-") as td:
            root = pathlib.Path(td); oracle.prepare(root); oracle.mutate(root, "generation"); oracle.mutate(root, "uncertain")
            before = {p.name: p.read_bytes() for p in (oracle.state_dir(root) / "tree").glob("*.txt")}
            oracle.mutate(root, "soak-1")
            after = {p.name: p.read_bytes() for p in (oracle.state_dir(root) / "tree").glob("*.txt")}
            self.assertLessEqual(len(after), 100)
            self.assertLessEqual(sum(before.get(k) != v for k, v in after.items()), 4)

    def test_sequential_soak_replays_from_fixed_base(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-s3-live-") as td:
            root = pathlib.Path(td) / "seq"; root.mkdir()
            oracle.prepare(root); oracle.mutate(root, "generation"); oracle.mutate(root, "uncertain")
            oracle.mutate(root, "soak-1"); fixed = (oracle.state_dir(root) / "fixedsoak_base.json").read_bytes()
            oracle.mutate(root, "soak-2")
            sequential = (oracle.state_dir(root) / "modelexpected.json").read_bytes()
            fresh = pathlib.Path(td) / "fresh"; fresh.mkdir()
            oracle.prepare(fresh); oracle.mutate(fresh, "generation"); oracle.mutate(fresh, "uncertain"); oracle.mutate(fresh, "soak-2")
            self.assertEqual(fixed, (oracle.state_dir(fresh) / "fixedsoak_base.json").read_bytes())
            self.assertEqual(sequential, (oracle.state_dir(fresh) / "modelexpected.json").read_bytes())

    def test_verify_normalizes_views_and_writes_private_report(self):
        with tempfile.TemporaryDirectory(prefix="lmfiles-s3-live-") as td:
            root = pathlib.Path(td); oracle.prepare(root)
            entries = json.loads((oracle.state_dir(root) / "manifest.json").read_text())["entries"]
            e = entries[0]; digest = e["sha256"]; md5 = e["md5"]
            local = {e["key"]: {k: e[k] for k in ("size", "sha256", "md5", "content_type", "cache_control")}}
            registry = {e["key"]: {"present": True, "status": "ok", "confirmed": True, "size": e["size"], "sha256": digest, "etag": '"'+md5+'"', "version": None, "request_started": "2026-01-01T00:00:00Z", "confirmed_at": "2026-01-01T00:00:02Z", "round": 0, "revision": 1}}
            remote = {e["key"]: {"size": e["size"], "etag": '"'+md5+'"', "version": None, "checksum_sha256": base64.b64encode(bytes.fromhex(digest)).decode(), "last_modified": "2026-01-01T00:00:01Z", "content_type": e["content_type"], "cache_control": e["cache_control"]}}
            work = oracle.state_dir(root)
            (work / "local.json").write_text(json.dumps([dict(key=k, **v) for k,v in local.items()]))
            (work / "registry.json").write_text(json.dumps(registry)); (work / "remote.json").write_text(json.dumps(remote)); (work / "history.json").write_text("[]")
            report = oracle.verify(root)
            self.assertFalse(report["ok"])  # expected model intentionally contains all 100 keys
            self.assertTrue((oracle.state_dir(root) / "verify-report.json").is_file())


if __name__ == "__main__": unittest.main()
