#!/usr/bin/env python3
"""Verify the pinned Maven runtime bytes after resolution."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
lock=json.loads((root/'deps.lock').read_text())['h2']
jar=Path.home()/'.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest()==lock['sha256'], 'H2 jar differs from deps.lock'
print('H2 2.3.232 bytes verified')
