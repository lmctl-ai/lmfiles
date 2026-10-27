#!/usr/bin/env python3
"""Exercise the real single-script fake job in an empty directory."""
import json,os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
env=os.environ.copy();env.update(LMAWS_LIVE='0',AWS_EC2_METADATA_DISABLED='true')
with tempfile.TemporaryDirectory(prefix='lmfiles-publish-') as temp:
    work=Path(temp)
    scenarios=[('normal',2,2,0),('normal',0,0,0),('uncertain',1,0,1),('changed',1,1,0),('changed',0,0,0),('normal',1,1,0)]
    for mode,selected,confirmed,uncertain in scenarios:
        r=subprocess.run(['lmscript',str(root/'examples/publish_ttl.lms'),mode],cwd=work,env=env,text=True,capture_output=True,timeout=180)
        assert r.returncode==0,(mode,r.stdout,r.stderr)
        report=json.loads((work/'.lmfiles-local/publish-ttl/report.json').read_text())
        assert (report['selected'],report['confirmed'],report['unconfirmed'])==(selected,confirmed,uncertain),report
        assert report['real_requests']==0
        lines=(work/'.lmfiles-local/publish-ttl/unconfirmed.jsonl').read_text().splitlines()
        assert len(lines)==uncertain
        assert (work/'images/publish_ttl.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
    assert (work/'.lmfiles-local/publish-ttl/fake-bucket/law-a.html').read_text()=='Synthetic law A, original text.\n'
print(json.dumps({'ok':True,'publication_scenarios':len(scenarios),'real_cloud_requests':0}))
