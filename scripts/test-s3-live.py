#!/usr/bin/env python3
"""Safety/process supervisor only; every AWS operation belongs to one LMScript job."""
import argparse,json,os,pathlib,signal,subprocess,sys,time
REPO=pathlib.Path(__file__).resolve().parents[1]

def executor(args):
    command=json.loads(args.command);ready=pathlib.Path(args.ready);ready.unlink(missing_ok=True)
    p=subprocess.Popen(command,start_new_session=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    killed=False
    try:
        until=time.monotonic()+300
        while p.poll() is None:
            if ready.exists():
                if args.receipts and pathlib.Path(args.receipts).exists():
                    with open(args.receipts,'rb') as f:os.fsync(f.fileno())
                os.killpg(p.pid,signal.SIGKILL);killed=True;break
            if time.monotonic()>until:os.killpg(p.pid,signal.SIGKILL);raise TimeoutError('executor timeout')
            time.sleep(.005)
        out,err=p.communicate(timeout=10)
        pathlib.Path(args.output).write_text(json.dumps(dict(code=p.returncode,killed=killed,stdout=out,stderr=err)))
        if args.receipts and pathlib.Path(args.receipts).exists():
            with open(args.receipts,'rb') as f:os.fsync(f.fileno())
        if killed:assert p.returncode==-9
        return 0
    finally:
        if p.poll() is None:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)

def process_tree(pid):
    found={}
    path=pathlib.Path('/proc')/str(pid)
    try:
        found[pid]=(path/'stat').read_text().split()[21]
        for child in (path/'task'/str(pid)/'children').read_text().split():found.update(process_tree(int(child)))
    except (FileNotFoundError,ProcessLookupError):pass
    return found

def stop_tree(p,tracked):
    tracked.update(process_tree(p.pid))
    for pid,started in reversed(list(tracked.items())):
        try:
            if (pathlib.Path('/proc')/str(pid)/'stat').read_text().split()[21]!=started:continue
            try:os.killpg(pid,signal.SIGKILL)
            except ProcessLookupError:os.kill(pid,signal.SIGKILL)
        except (FileNotFoundError,ProcessLookupError):pass
    if p.poll() is None:p.kill()
    p.wait(timeout=10)

def main():
    if len(sys.argv)>1 and sys.argv[1]=='executor':
        ap=argparse.ArgumentParser();ap.add_argument('mode');ap.add_argument('--command',required=True);ap.add_argument('--ready',required=True);ap.add_argument('--receipts');ap.add_argument('--output',required=True)
        return executor(ap.parse_args())
    ap=argparse.ArgumentParser();ap.add_argument('--run-id',required=True);ap.add_argument('--work-dir',type=pathlib.Path);ap.add_argument('--fixture-endpoint');ap.add_argument('--live',action='store_true');ap.add_argument('--rounds',type=int,default=30)
    args=ap.parse_args()
    import re
    assert re.fullmatch('[a-z0-9]{1,32}',args.run_id),'synthetic run id required'
    assert os.environ.get('AWS_PROFILE')=='lmaws','AWS_PROFILE must be lmaws'
    assert args.live or args.fixture_endpoint,'explicit --live authorization switch required'
    assert 1<=args.rounds<=30
    if args.live:assert args.rounds==30,'authorized live campaign requires30 rounds'
    os.umask(0o077)
    work=(args.work_dir or REPO/'.lmfiles-local'/('live-'+args.run_id)).resolve()
    work.mkdir(parents=True,exist_ok=True)
    root=work/'.lmfiles-local/s3-live';assert not root.exists(),'fresh private work directory required'
    env=dict(os.environ,AWS_PROFILE='lmaws',AWS_DEFAULT_PROFILE='lmaws',AWS_REGION='us-east-1',AWS_DEFAULT_REGION='us-east-1',AWS_EC2_METADATA_DISABLED='true',LMAWS_LIVE='1',LMAWS_MAX_COST_USD='0.05',LMAWS_LEDGER_DIR=str(root/'ledger'),LMFILES_S3_SUPERVISED='1',LMFILES_S3_HELPER=str(REPO/'scripts/s3-live-oracle.py'),LMFILES_S3_SUPERVISOR=str(pathlib.Path(__file__).resolve()),LMFILES_S3_ROUNDS=str(args.rounds))
    if args.fixture_endpoint:
        assert args.fixture_endpoint.startswith('http://127.0.0.1:')
        env.update(CI='true',LMAWS_ENDPOINT=args.fixture_endpoint,LMFILES_S3_ALLOW_FIXTURE='1')
    else:
        assert not env.get('LMAWS_ENDPOINT'),'live run refuses endpoint overrides'
        env.pop('LMFILES_S3_ALLOW_FIXTURE',None)
    # Install immutable releases, then use those exact executables even with an older PATH hit.
    host_dirs=[]
    for spec in ['github:lmctl-ai/lmaws@v0.1.23','github:lmctl-ai/lmfiles@latest']:
        installed=subprocess.run(['lmscript','install',spec],text=True,capture_output=True,timeout=180,check=True)
        path=next(line[6:] for line in installed.stdout.splitlines() if line.startswith('path: '))
        host_dirs.append(str(pathlib.Path(path).parent))
    env['PATH']=os.pathsep.join(host_dirs+[env.get('PATH','')])
    script=REPO/'examples/upload_s3_live.lms';logs=work/'driver.log'
    assert pathlib.Path('/proc').is_dir(),'Linux process-tree supervisor required'
    status=1;p=None;tracked={}
    def interrupted(_signal,_frame):raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM,interrupted)
    try:
        with logs.open('w') as out:
            p=subprocess.Popen(['lmscript',str(script),'run',args.run_id],cwd=work,env=env,stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
            until=time.monotonic()+3600
            while p.poll() is None:
                tracked.update(process_tree(p.pid))
                if time.monotonic()>until:raise TimeoutError('whole-job timeout')
                time.sleep(.2)
            status=p.returncode
    except (KeyboardInterrupt,TimeoutError):status=1
    finally:
        if p is not None:stop_tree(p,tracked)
        # Same job file, exact bucket/run/profile/cap. No orphan executor may race teardown.
        with (work/'cleanup.log').open('w') as out:
            cleaned=subprocess.run(['lmscript',str(script),'cleanup',args.run_id],cwd=work,env=env,stdout=out,stderr=subprocess.STDOUT,timeout=300)
        if cleaned.returncode:status=1
    summary=root/'public-results.json'
    if summary.exists():
        report=json.loads(summary.read_text())
        report['live']=args.fixture_endpoint is None
        if status==0 and args.rounds==30:
            with (work/'put-audit.log').open('w') as out:
                audited=subprocess.run(['python3',str(REPO/'scripts/s3-live-ledger-audit.py'),'--work',str(work)],stdout=out,stderr=subprocess.STDOUT,timeout=30)
            if audited.returncode:status=1
            else:report['put_correlation_audit']=json.loads((root/'audit-public.json').read_text())
        if status==0 and not (report.get('status')=='pass' and report.get('zero_leftovers_verified') is True and report.get('listing_rebuild_verified') is True):status=1
        if status!=0:report['status']='failed'
        summary.write_text(json.dumps(report,indent=2)+'\n')
        print(summary.read_text())
    else:status=1
    print(json.dumps(dict(driver_exit=status,cleanup_exit=cleaned.returncode,private_work=str(work))))
    return status
if __name__=='__main__':raise SystemExit(main())
