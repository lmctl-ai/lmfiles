#!/usr/bin/env python3
"""Verify the pinned Maven runtime bytes after resolution."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
lock=json.loads((root/'deps.lock').read_text())['h2']
jar=Path.home()/'.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest()==lock['sha256'], 'H2 jar differs from deps.lock'
import xml.etree.ElementTree as ET
pom=ET.parse(root/'pom.xml').getroot();ns={'m':'http://maven.apache.org/POM/4.0.0'}
h2=[d for d in pom.findall('m:dependencies/m:dependency',ns) if d.findtext('m:artifactId',namespaces=ns)=='h2'][0]
assert h2.findtext('m:version',namespaces=ns)==lock['coordinate'].split(':')[-1], 'H2 pin drift'
print('H2 2.3.232 bytes and POM pin verified')
