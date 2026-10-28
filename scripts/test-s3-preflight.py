#!/usr/bin/env python3
"""Local-only checks of the released-host capability block; never executes AWS DSL."""
import json,os,pathlib,subprocess,tempfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
def main():
    checks=0
    with tempfile.TemporaryDirectory(prefix='lmfiles-s3-preflight-') as tmp:
        work=pathlib.Path(tmp)
        for profile in ['default','admin']:
            r=subprocess.run(['lmscript',str(ROOT/'examples/upload_s3_100_preflight.lms'),'syntheticrun01'],cwd=work,env=dict(os.environ,AWS_PROFILE=profile),text=True,capture_output=True,timeout=60)
            assert r.returncode!=0 and 'AWS_PROFILE must be exactly lmaws' in r.stderr,(r.stdout,r.stderr)
            assert not (work/'.lmfiles-local/s3-100/preflight.json').exists();checks+=1
        r=subprocess.run(['lmscript',str(ROOT/'examples/upload_s3_100_preflight.lms'),'syntheticrun01'],cwd=work,env=dict(os.environ,AWS_PROFILE='lmaws',LMAWS_MAX_COST_USD='0.05'),text=True,capture_output=True,timeout=60)
        assert r.returncode==2 and 'BLOCKED' in r.stdout,(r.stdout,r.stderr);checks+=1
        report=json.loads((work/'.lmfiles-local/s3-100/preflight.json').read_text())
        assert report['host_version']=='0.1.22' and len(report['missing_api'])==6;checks+=1
        assert report['aws_requests']==report['objects_created']==report['buckets_created']==report['estimated_cost_usd']==0
        assert report['ledger_opened'] is False and not list(work.rglob('*.jsonl'));checks+=1
        assert report['remote_absence_verified'] is False and len(report['phases'])==10;checks+=1
        assert all(p['status']=='not_run_release_api_gap' for p in report['phases']);checks+=1
        soak=report['soak']
        assert soak['seed']==20261027 and soak['rounds_planned']==30 and soak['rounds_executed']==0
        assert len(soak['rounds'])==30 and all(row['converged'] is None and row['operations'] is None and row['faults_injected']==0 for row in soak['rounds'])
        assert soak['converged'] is None and soak['faults_injected']==soak['operations_executed']==0;checks+=1
        assert len(report['reconciliation'])==8 and report['observed_max_skew_seconds'] is None and report['listing_rebuild_verified'] is False;checks+=1
        assert not any(word in json.dumps(report).lower() for word in ['arn:','account_id','syntheticrun01','lmaws-lmfiles-']);checks+=1
    print(json.dumps(dict(ok=True,preflight_checks=checks,live_phases_executed=0,aws_requests=0)))
if __name__=='__main__':main()
