#!/usr/bin/env python3
"""Real loopback download and local fake publish: confirmation-gap recovery at each stage."""
import argparse,json,os,pathlib,shutil,subprocess,tempfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--gallery',action='store_true');args=parser.parse_args()
    checks=0
    with tempfile.TemporaryDirectory(prefix='lmfiles-pipeline-') as tmp:
        root=pathlib.Path(tmp)
        def job(work,mode='normal',success=True):
            work.mkdir(exist_ok=True)
            r=subprocess.run(['lmscript',str(ROOT/'examples/pipeline_lifecycle.lms'),mode],cwd=work,capture_output=True,text=True,timeout=120)
            assert (r.returncode==0)==success,(mode,r.stdout,r.stderr)
            return work/'.lmfiles-local/pipeline'
        for stage in ['fetch','clean','publish']:
            work=root/stage
            state=job(work,'fail-'+stage,False)
            assert (state/'plan.jsonl').is_file()
            state=job(work)
            report=json.loads((state/'report.json').read_text())
            # The interrupted stage executes again; prior confirmed stages stay done.
            expected={'fetch':(1,1,1),'clean':(0,1,1),'publish':(0,0,1)}[stage]
            assert tuple(report[k] for k in ['fetched','cleaned','published'])==expected,report
            assert (state/'unconfirmed.jsonl').read_text()==''
            assert (state/'fetched.txt').is_file() and (state/'cleaned.txt').read_bytes()==(state/'fake-s3/law.txt').read_bytes()
            job(work)
            report=json.loads((state/'report.json').read_text())
            assert (report['fetched'],report['cleaned'],report['published'])==(0,0,0),report
            checks+=4
        gallery=root/'gallery';job(gallery)
        if args.gallery:shutil.copyfile(gallery/'images/pipeline_lifecycle.png',ROOT/'docs/images/pipeline_lifecycle.png')
    print(json.dumps({'ok':True,'pipeline_checks':checks,'interrupted_stages':3,'cloud_requests':0}))
if __name__=='__main__':main()
