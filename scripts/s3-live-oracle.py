#!/usr/bin/env python3
"""Offline fixture/oracle helper for the s3-live LMScript driver.

This module owns only deterministic local desired state.  It never opens a
registry, creates an AWS client, or performs a network operation.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import tempfile
from typing import Any

from s3_correctness_oracle import Model, SEED, fingerprint, reconcile, remote_sha, timestamp_ms, soak_plan, UNIVERSE

STATE_NAME = ".lmfiles-local/s3-live"
CONTENT_TYPE = "text/plain"
CACHE_CONTROL = "max-age=60"


def state_dir(root: Path) -> Path:
    return Path(root) / STATE_NAME


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, sort_keys=True, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def key_body(key: str, generation: int) -> bytes:
    return f"synthetic source {key} generation {generation}\n".encode()


def manifest_for(root: Path, tree: Path, round_number: int) -> list[dict]:
    entries = []
    for file in sorted(tree.iterdir()):
        if file.is_symlink() or not file.is_file():raise ValueError("non-regular file in private desired tree")
        body = file.read_bytes()
        key = file.name
        fp = fingerprint(body)
        stat = file.stat()
        entry = dict(key=key, path=str(file.relative_to(root)), body_b64=base64.b64encode(body).decode(),
                     size=fp["size"], sha256=fp["sha256"], md5=fp["md5"], round=round_number,
                     content_type=CONTENT_TYPE, cache_control=CACHE_CONTROL, mtime_ns=str(stat.st_mtime_ns))
        entries.append(entry)
    return entries


def write_model_state(root: Path, *, mode: str, round_number: int, state: int,
                      desired: dict[str, bytes], origins: dict[str, int], operations=None) -> dict:
    work = state_dir(root)
    tree = work / "tree"
    tree.mkdir(parents=True, exist_ok=True)
    entries = manifest_for(root, tree, round_number)
    expected = {}
    for key, body in sorted(desired.items()):
        expected[key] = dict(fingerprint(body), round=origins.get(key, round_number),
                             body_b64=base64.b64encode(body).decode(), content_type=CONTENT_TYPE,
                             cache_control=CACHE_CONTROL)
    atomic_json(work / "manifest.json", {"mode": mode, "round": round_number, "entries": entries})
    atomic_json(work / "modelstate.json", {"seed": SEED, "state": state, "round": round_number,
                                            "desired": {k: base64.b64encode(v).decode() for k, v in desired.items()},
                                            "origins": origins})
    atomic_json(work / "modelexpected.json", {"seed": SEED, "state": state, "round": round_number,
                                               "expected": expected})
    if operations is not None:
        atomic_json(work / "operations.json", operations)
    return {"mode": mode, "round": round_number, "files": len(entries), "state": state}


def load_desired(root: Path):
    doc = json.loads((state_dir(root) / "modelstate.json").read_text(encoding="utf-8"))
    return ({k: base64.b64decode(v) for k, v in doc["desired"].items()},
            {k: int(v) for k, v in doc["origins"].items()}, int(doc["state"]))


def prepare(root: Path) -> dict:
    work = state_dir(root)
    tree = work / "tree"
    if tree.exists():
        for old in tree.glob("synthetic-*.txt"):
            old.unlink()
    tree.mkdir(parents=True, exist_ok=True)
    for index in range(100):
        key = f"synthetic-{index:03d}.txt"
        (tree / key).write_bytes(key_body(key, 0))
    desired = {f"synthetic-{i:03d}.txt": key_body(f"synthetic-{i:03d}.txt", 0) for i in range(100)}
    return write_model_state(root, mode="initial", round_number=0, state=SEED, desired=desired,
                             origins={k: 0 for k in desired},
                             operations={"kind": "prepare", "puts": 100, "deletes": 0})


def mutate(root: Path, mode: str) -> dict:
    work, tree = state_dir(root), state_dir(root) / "tree"
    if not tree.is_dir():
        raise SystemExit("prepare must run before mutate")
    desired, origins, state = load_desired(root)
    if mode == "delta":
        for index in range(3):
            key = f"synthetic-{index:03d}.txt"; (tree / key).write_bytes(key_body(key, 1))
        for index in (100, 101):
            key = f"synthetic-{index:03d}.txt"; (tree / key).write_bytes(key_body(key, 1))
        for index in (98, 99):
            (tree / f"synthetic-{index:03d}.txt").unlink()
        for index in range(3):
            key = f"synthetic-{index:03d}.txt"; desired[key] = key_body(key, 1); origins[key] = 1
        for index in (100, 101):
            key = f"synthetic-{index:03d}.txt"; desired[key] = key_body(key, 1); origins[key] = 1
        for index in (98, 99): desired.pop(f"synthetic-{index:03d}.txt", None); origins.pop(f"synthetic-{index:03d}.txt", None)
        return write_model_state(root, mode=mode, round_number=1, state=state, desired=desired, origins=origins,
                                 operations={"kind": mode, "changed": 3, "adds": 2, "deletes": 2})
    if mode == "generation":
        for old in tree.glob("synthetic-*.txt"):
            old.unlink()
        for index in range(100):
            key = f"synthetic-{index:03d}.txt"; (tree / key).write_bytes(key_body(key, 2))
        desired = {f"synthetic-{i:03d}.txt": key_body(f"synthetic-{i:03d}.txt", 2) for i in range(100)}
        origins = {k: 2 for k in desired}
        return write_model_state(root, mode=mode, round_number=2, state=state, desired=desired, origins=origins,
                                 operations={"kind": mode, "reset": 100})
    if mode == "uncertain":
        key = "synthetic-000.txt"; desired[key] = key_body(key, 3); origins[key] = 3; (tree / key).write_bytes(desired[key])
        operations = {"kind": mode, "faults": ["lost_response", "retry", "duplicate_receipt"],
                      "puts": 0, "requires_confirmation": True}
        return write_model_state(root, mode=mode, round_number=3, state=state, desired=desired, origins=origins, operations=operations)
    if mode.startswith("soak-"):
        try: rounds = int(mode[5:])
        except ValueError: raise SystemExit("soak-N requires an integer round count")
        if not 1 <= rounds <= 30: raise SystemExit("soak-N must be 1..30")
        base_path = work / "fixedsoak_base.json"
        if not base_path.exists():
            atomic_json(base_path, {"desired": {k: base64.b64encode(v).decode() for k, v in desired.items()},
                                    "origins": origins, "state": state})
        base = json.loads(base_path.read_text(encoding="utf-8"))
        base_desired = {k: base64.b64decode(v) for k, v in base["desired"].items()}
        base_origins = {k: int(v) for k, v in base["origins"].items()}
        model = Model(SEED, initial=dict(base_desired)); rows = []
        for number in range(11, rounds + 11): rows.append(model.round(number))
        for key in set(desired) - set(model.files): (tree / key).unlink(missing_ok=True)
        for key, body in model.files.items():
            if desired.get(key) != body: (tree / key).write_bytes(body)
        if rows:
            for operation in rows[-1]["operations"]:
                if operation.get("applied") == "touch" and operation.get("key") in model.files:
                    path = tree / operation["key"]
                    stat = path.stat(); os.utime(path, (stat.st_atime, stat.st_mtime + 1.0))
        desired = dict(model.files)
        origins = {k: (model.last_changed[k] if model.last_changed[k] else base_origins.get(k, 2)) for k in desired}
        return write_model_state(root, mode=mode, round_number=rounds + 10, state=model.state, desired=desired, origins=origins,
                                 operations={"kind": mode, "last_round": rows[-1] if rows else None})
    raise SystemExit("mutate mode must be delta, generation, uncertain, or soak-N")


def _load_view(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(value, dict) and isinstance(value.get("rows"), list): value = value["rows"]
    if isinstance(value, list):
        return {str(row["key"]): dict(row) for row in value}
    if not isinstance(value, dict): raise ValueError(f"{path} must contain an object or row list")
    return value


def import_remote(root: Path) -> dict:
    """Convert only the independently captured remote listing into import rows."""
    work = state_dir(root)
    remote = _load_view(work / "remote.json")
    rows = []
    for key in sorted(remote):
        source = remote[key]
        if not isinstance(source, dict):
            raise ValueError(f"remote row {key!r} is not an object")
        digest = remote_sha(source)
        size = source.get("size")
        if type(size) is not int or not 0 <= size <= 16 * 1024 * 1024:
            raise ValueError(f"remote row {key!r} has invalid size")
        etag = str(source.get("etag", "")).strip('"')
        if not etag:
            raise ValueError(f"remote row {key!r} has empty etag")
        timestamp_ms(source.get("last_modified"))
        rows.append({"key": key, "fields": {"content_hash": digest, "size": str(size)},
                     "fingerprint": {"hash": digest, "size": str(size)},
                     "metadata": {"present": True, "key": key, "size": size,
                                  "sha256": digest, "etag": source["etag"], "version": source.get("version"),
                                  "remote_last_modified": source["last_modified"],
                                  "content_type": source.get("content_type"),
                                  "cache_control": source.get("cache_control"),
                                  "provenance": "explicit_s3_listing_head",
                                  "original_upload_times": None, "round": None}})
    result = {"rows": rows, "count": len(rows), "source": "remote.json"}
    atomic_json(work / "import-rows.json", result)
    return result


def verify(root: Path) -> dict:
    work = state_dir(root)
    expected_doc = json.loads((work / "modelexpected.json").read_text(encoding="utf-8"))
    reported_local = _load_view(work / "local.json")
    fresh_entries=manifest_for(root,work/"tree",expected_doc["round"])
    local={e["key"]:{field:e[field] for field in ("size","sha256","md5","content_type","cache_control")} for e in fresh_entries}
    registry = _load_view(work / "registry.json")
    remote = _load_view(work / "remote.json")
    history_path = work / "history.json"
    manifest = json.loads((work / "manifest.json").read_text(encoding="utf-8"))
    manifest_map = {row["key"]: row for row in manifest["entries"]}
    model_errors = []
    for key in sorted(set(local)^set(reported_local)):
        model_errors.append({"key":key,"view":"actual_files_vs_snapshot","field":"key_set"})
    for key in sorted(set(local)&set(reported_local)):
        for field in ("size","sha256","md5"):
            if local[key][field]!=reported_local[key].get(field):model_errors.append({"key":key,"view":"actual_files_vs_snapshot","field":field})
    for key in sorted(set(manifest_map) ^ set(expected_doc["expected"])):
        model_errors.append({"key": key, "view": "manifest_vs_model", "field": "key_set"})
    for key in sorted(set(manifest_map) & set(expected_doc["expected"])):
        for field in ("size", "sha256", "md5"):
            if manifest_map[key].get(field) != expected_doc["expected"][key].get(field):
                model_errors.append({"key": key, "view": "manifest_vs_model", "field": field})
    history = json.loads(history_path.read_text(encoding="utf-8")) if history_path.exists() else []
    result = reconcile(local, registry, remote, history=history, expected=expected_doc["expected"],
                       origins={key: row.get("round") for key, row in expected_doc["expected"].items()})
    report = {"ok": bool(result.get("ok")) and not model_errors, "mode": manifest["mode"],
              "round": expected_doc["round"], "mismatch_count": len(model_errors)+len(result.get("mismatches", [])),
              "observed_max_skew_seconds": result.get("observed_max_skew_seconds"),
              "valid_intervals": result.get("valid_intervals", 0),
              "mismatches": model_errors + result.get("mismatches", [])}
    atomic_json(work / "verify-report.json", report)
    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(); sub = ap.add_subparsers(dest="action", required=True)
    for action in ("prepare", "verify", "import"):
        p = sub.add_parser(action); p.add_argument("root", type=Path)
    p = sub.add_parser("mutate"); p.add_argument("root", type=Path); p.add_argument("mode")
    ns = ap.parse_args(argv)
    result = prepare(ns.root) if ns.action == "prepare" else mutate(ns.root, ns.mode) if ns.action == "mutate" else verify(ns.root) if ns.action == "verify" else import_remote(ns.root)
    print(json.dumps(result, sort_keys=True)); return 0 if result.get("ok", True) else 1


if __name__ == "__main__": raise SystemExit(main())
