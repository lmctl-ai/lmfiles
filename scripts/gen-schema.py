#!/usr/bin/env python3
"""Refresh schema/lmfiles.dsl.json from the host's declaration catalog."""
import argparse, json, subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[1]
ap=argparse.ArgumentParser(); ap.add_argument('host', nargs='?', default=str(root/'target/lmfiles'))
ap.add_argument('--check', action='store_true'); args=ap.parse_args()
command=[args.host,'--lmcode-schema','--json']
if args.host.endswith('.jar'): command=['java','-jar',args.host,'--lmcode-schema','--json']
raw=subprocess.check_output(command, timeout=30)
obj=json.loads(raw)
if obj.get('name')!='lmfiles' or obj.get('dsl.schema')!=2: raise SystemExit('unexpected lmfiles schema')
data=json.dumps(obj, separators=(',',':'), ensure_ascii=False)+'\n'
out=root/'schema/lmfiles.dsl.json'
if args.check:
    if not out.exists() or out.read_text(encoding='utf-8')!=data: raise SystemExit('schema/lmfiles.dsl.json is stale')
else:
    out.write_text(data, encoding='utf-8')
