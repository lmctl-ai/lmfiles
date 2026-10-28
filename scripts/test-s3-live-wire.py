#!/usr/bin/env python3
"""Loopback S3 wire fixture supervisor for the lmaws live-object driver.

This file only binds 127.0.0.1. It never resolves AWS credentials or contacts a
non-loopback endpoint. When the real live supervisor exists, it launches that
driver against this fixture and records every PUT for teardown assertions.
"""
import argparse, base64, hashlib, json, os, pathlib, socket, subprocess, threading
from email.utils import format_datetime
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit
from xml.sax.saxutils import escape
import xml.etree.ElementTree as ET

NS = "http://s3.amazonaws.com/doc/2006-03-01/"

class Model:
    def __init__(self, bucket, run, work):
        self.bucket, self.run, self.work = bucket, run, work
        self.exists = False; self.tag = None; self.objects = {}
        self.calls = []; self.puts = []; self.lock = threading.Lock()
    def now(self): return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

class Handler(BaseHTTPRequestHandler):
    server_version = "lmfiles-s3-fixture/1"
    def log_message(self, *_): pass
    def reply(self, status=200, body=b"", headers=None):
        if isinstance(body, str): body = body.encode()
        hs = {"x-amz-request-id":"lmfiles-fixture", "Content-Type":"application/xml"}; hs.update(headers or {})
        self.send_response(status)
        for k, v in hs.items(): self.send_header(k, str(v))
        content_length = hs.pop("Content-Length", len(body)); self.send_header("Content-Length", str(content_length))
        self.end_headers()
        if self.command != "HEAD":
            try: self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError): pass
    def error(self, code, status=403): return self.reply(status, f"<Error><Code>{code}</Code></Error>")
    def parsed(self):
        u = urlsplit(self.path); parts = unquote(u.path).strip("/").split("/", 1)
        bucket = parts[0] if parts and parts[0] else ""; key = parts[1] if len(parts) > 1 else ""
        q = parse_qs(u.query, keep_blank_values=True); m = self.server.model
        if bucket != m.bucket: return self.error("NoSuchBucket", 404), None, None, None
        m.calls.append((self.command, key, q)); return None, m, key, q
    def require_profile(self, m):
        if self.headers.get("X-Lmaws-Test-Profile") != "lmaws": self.error("AccessDenied"); return False
        return True
    def read_body(self):
        length = int(self.headers.get("Content-Length", "0")); body = self.rfile.read(length)
        if "aws-chunked" not in self.headers.get("Content-Encoding", ""):
            return body
        decoded = bytearray(); remaining = body
        while True:
            if b"\r\n" not in remaining: raise ValueError("malformed aws-chunked body")
            line, remaining = remaining.split(b"\r\n", 1); size = int(line.split(b";", 1)[0], 16)
            if len(remaining) < size + 2: raise ValueError("truncated aws-chunked body")
            if size == 0: break
            decoded.extend(remaining[:size]); remaining = remaining[size+2:]
        expected = self.headers.get("x-amz-decoded-content-length")
        if expected is not None and len(decoded) != int(expected): raise ValueError("decoded length mismatch")
        return bytes(decoded)
    def do_GET(self):
        done, m, key, q = self.parsed()
        if done: return
        if "tagging" in q:
            if not m.exists: return self.error("NoSuchBucket", 404)
            if m.tag is None: return self.error("NoSuchTagSet", 404)
            return self.reply(200, f"<Tagging xmlns='{NS}'><TagSet><Tag><Key>lmaws_test</Key><Value>{escape(m.tag)}</Value></Tag></TagSet></Tagging>")
        if not m.exists: return self.error("NoSuchBucket", 404)
        if q.get("list-type") != ["2"]: return self.error("InvalidRequest", 400)
        prefix = q.get("prefix", [""])[0]; offset = int(q.get("continuation-token", ["0"])[0]); limit = min(100, int(q.get("max-keys", ["1000"])[0]))
        names = sorted(k for k in m.objects if k.startswith(prefix)); selected = names[offset:offset+limit]; truncated = offset + len(selected) < len(names)
        body = f"<ListBucketResult xmlns='{NS}'><IsTruncated>{str(truncated).lower()}</IsTruncated>"
        if truncated: body += f"<NextContinuationToken>{offset+len(selected)}</NextContinuationToken>"
        for name in selected:
            o = m.objects[name]; body += f"<Contents><Key>{escape(name)}</Key><Size>{len(o['body'])}</Size><ETag>{o['etag']}</ETag><LastModified>{o['modified']}</LastModified><StorageClass>STANDARD</StorageClass></Contents>"
        return self.reply(200, body + "</ListBucketResult>")
    def do_PUT(self):
        done, m, key, q = self.parsed()
        if done or not self.require_profile(m): return
        try: body = self.read_body()
        except ValueError: return self.error("IncompleteBody", 400)
        if "tagging" in q:
            if not m.exists or key: return self.error("NoSuchBucket", 404)
            try: m.tag = ET.fromstring(body).find(".//{*}Value").text
            except Exception: return self.error("MalformedXML", 400)
            return self.reply(200)
        if not key:
            if m.exists: return self.error("BucketAlreadyOwnedByYou", 409)
            m.exists = True; return self.reply(200)
        if not m.exists or m.tag != m.run: return self.error("AccessDenied", 403)
        if self.headers.get("x-amz-sdk-checksum-algorithm") != "SHA256": return self.error("InvalidRequest", 400)
        checksum = base64.b64encode(hashlib.sha256(body).digest()).decode()
        if self.headers.get("x-amz-checksum-sha256") != checksum: return self.error("BadDigest", 400)
        if self.headers.get("If-None-Match") == "*" and key in m.objects: return self.error("PreconditionFailed", 412)
        etag = '"' + hashlib.md5(body).hexdigest() + '"'; modified = m.now()
        metadata = {k[11:].lower(): v for k, v in self.headers.items() if k.lower().startswith("x-amz-meta-")}
        m.objects[key] = {"body":body,"etag":etag,"checksum":checksum,"modified":modified,"type":self.headers.get("Content-Type", ""),"cache":self.headers.get("Cache-Control", ""),"metadata":metadata}
        m.puts.append({"key":key,"bytes":len(body),"sha256":hashlib.sha256(body).hexdigest(),"modified":modified})
        return self.reply(200, headers={"ETag":etag,"x-amz-checksum-sha256":checksum})
    def do_HEAD(self):
        done, m, key, q = self.parsed()
        if done: return
        if not m.exists: return self.error("NoSuchBucket", 404)
        if key not in m.objects: return self.error("NoSuchKey", 404)
        if self.headers.get("x-amz-checksum-mode") != "ENABLED": return self.error("InvalidRequest", 400)
        o = m.objects[key]; stamp = datetime.fromisoformat(o["modified"].replace("Z", "+00:00")); return self.reply(200, headers={"Content-Length":len(o["body"]),"ETag":o["etag"],"Last-Modified":format_datetime(stamp, usegmt=True),"Content-Type":o["type"],"Cache-Control":o["cache"],"x-amz-checksum-sha256":o["checksum"],"x-amz-checksum-type":"FULL_OBJECT", **{"x-amz-meta-"+k:v for k,v in o["metadata"].items()}})
    def do_POST(self):
        done, m, key, q = self.parsed()
        if done or not self.require_profile(m): return
        if key or "delete" not in q or m.tag != m.run: return self.error("AccessDenied", 403)
        body = self.rfile.read(int(self.headers.get("Content-Length", "0"))); names = [x.text for x in ET.fromstring(body).findall(".//{*}Key")]
        out = f"<DeleteResult xmlns='{NS}'" + ">"
        for name in names: m.objects.pop(name, None); out += f"<Deleted><Key>{escape(name)}</Key></Deleted>"
        return self.reply(200, out + "</DeleteResult>")
    def do_DELETE(self):
        done, m, key, q = self.parsed()
        if done or not self.require_profile(m): return
        if key or q or m.tag != m.run: return self.error("AccessDenied", 403)
        if m.objects: return self.error("BucketNotEmpty", 409)
        m.exists = False; m.tag = None; return self.reply(204, b"")

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--run-id", required=True); ap.add_argument("--work-dir", type=pathlib.Path, required=True); ap.add_argument("--fixture-endpoint"); ap.add_argument("--quick", action="store_true"); ns = ap.parse_args()
    if not ns.run_id.islower() or not ns.run_id.replace("_", "").replace("-", "").isalnum(): raise SystemExit("invalid run id")
    ns.work_dir.mkdir(parents=True, exist_ok=True); bucket = "lmaws-lmfiles-" + ns.run_id; model = Model(bucket, ns.run_id, ns.work_dir)
    port = int(urlsplit(ns.fixture_endpoint).port) if ns.fixture_endpoint else 0
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler); server.model = model; threading.Thread(target=server.serve_forever, daemon=True).start()
    driver = pathlib.Path(__file__).with_name("test-s3-live.py")
    endpoint = f"http://127.0.0.1:{server.server_port}"; env = dict(os.environ, CI="true", LMAWS_ENDPOINT=endpoint, LMFILES_S3_ALLOW_FIXTURE="1", AWS_PROFILE="lmaws", AWS_DEFAULT_PROFILE="lmaws", AWS_REGION="us-east-1", AWS_DEFAULT_REGION="us-east-1", LMAWS_LIVE="1", LMAWS_MAX_COST_USD="0.05", LMAWS_LEDGER_DIR=str(ns.work_dir / "ledger"), LMFILES_S3_SUPERVISED="1", LMFILES_S3_HELPER=str(pathlib.Path(__file__).with_name("s3-live-oracle.py")), LMFILES_S3_SUPERVISOR=str(driver))
    try:
        if not driver.exists(): print(json.dumps({"status":"fixture-ready","endpoint":endpoint,"bucket":bucket,"run_id":ns.run_id})); return
        cmd = ["python3", str(driver), "--run-id", ns.run_id, "--work-dir", str(ns.work_dir), "--fixture-endpoint", endpoint]
        if ns.quick: cmd += ["--rounds","1"]
        result = subprocess.run(cmd, env=env, cwd=driver.parents[1], text=True); result.check_returncode()
        assert not model.exists and not model.objects,'fixture resources survived teardown'
        public=json.loads((ns.work_dir/'.lmfiles-local/s3-live/public-results.json').read_text())
        assert public['status']=='pass' and public['zero_leftovers_verified'] and public['listing_rebuild_verified']
        assert public['request_counts']['PutObject']==len(model.puts)+1,'PUT ledger must match successful wire PUTs plus412' 
        (ns.work_dir / "wire-puts.json").write_text(json.dumps(model.puts, indent=2) + "\n")
        print(json.dumps({"status":"ok","endpoint":endpoint,"bucket":bucket,"puts":len(model.puts),"requests":len(model.calls)}))
    finally: server.shutdown(); server.server_close()

if __name__ == "__main__": main()
