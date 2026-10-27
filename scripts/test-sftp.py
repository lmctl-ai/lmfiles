#!/usr/bin/env python3
"""Real lmssh job and interrupted reput/reget against a private loopback sshd."""
import argparse,hashlib,json,os,pathlib,shutil,signal,subprocess,tempfile,time
ROOT=pathlib.Path(__file__).resolve().parents[1]

def children(pid):
    # lmssh gives each child a private session. Kill those groups, not just the host.
    result=[]
    try:
        text=pathlib.Path(f'/proc/{pid}/task/{pid}/children').read_text()
        for n in text.split():result.extend(children(int(n)));result.append(int(n))
    except FileNotFoundError:pass
    return result

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--gallery',action='store_true');args=ap.parse_args()
    for tool in ['lmssh','lmfiles','lmscript','ssh-keygen']:
        assert shutil.which(tool),tool+' required on PATH'
    assert pathlib.Path('/proc').is_dir(),'kill-tree harness currently requires Linux'
    checks=0
    with tempfile.TemporaryDirectory(prefix='lmfiles-sftp-test-') as tmp:
        work=pathlib.Path(tmp);ready=work/'fixture.json';stop=work/'stop'
        text=(ROOT/'examples/sftp_registry.lms').read_text();code=json.JSONDecoder().raw_decode(text.split('let FIXTURE=',1)[1])[0]
        daemon=subprocess.Popen(['python3','-c',code,str(ready),str(stop),'240'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            for _ in range(100):
                if ready.is_file():break
                assert daemon.poll() is None,daemon.communicate()
                time.sleep(.05)
            assert ready.is_file(),'fixture startup timeout'
            config=json.loads(ready.read_text());remote=pathlib.Path(config['remote']);local=pathlib.Path(config['local'])
            def job(expected):
                r=subprocess.run(['lmscript',str(ROOT/'examples/sftp_registry.lms'),str(ready)],cwd=work,text=True,capture_output=True,timeout=120)
                assert r.returncode==0,(r.stdout,r.stderr)
                report=json.loads((work/'.lmfiles-local/sftp/report.json').read_text())
                assert (report['uploads'],report['downloads'])==expected,report
                assert report['remote_listing'] is False
            job((1,1));checks+=1
            assert remote.read_bytes()==local.read_bytes()==(work/'.lmfiles-local/sftp/downloaded.txt').read_bytes();checks+=1
            job((0,0));checks+=1
            (work/'.lmfiles-local/sftp/downloaded.txt').write_text('locally damaged')
            job((0,1));checks+=1
            # Strict host keys must refuse an empty known_hosts, without a transfer receipt.
            bad=work/'bad.json';empty=work/'empty-known';empty.write_text('')
            badconfig=dict(config,known_hosts=str(empty),remote=str(remote.parent/'untrusted.txt'));bad.write_text(json.dumps(badconfig))
            refused=subprocess.run(['lmscript',str(ROOT/'examples/sftp_registry.lms'),str(bad)],cwd=work,text=True,capture_output=True,timeout=60)
            assert refused.returncode!=0 and not pathlib.Path(badconfig['remote']).exists();checks+=1
            big=work/'big.txt'
            with big.open('wb') as f:
                chunk=b'synthetic-payload-'*4096
                for _ in range(1024):f.write(chunk)
            size=big.stat().st_size;target=remote.parent/'big.partial';target.write_bytes(b'')
            def stop_tree(p):
                for pid in children(p.pid):
                    try:os.killpg(pid,signal.SIGKILL)
                    except ProcessLookupError:pass
                if p.poll() is None:p.kill()
                p.wait(timeout=5)
            def interrupted_job(partial):
                p=subprocess.Popen(['lmscript',str(ROOT/'examples/sftp_registry.lms'),str(ready)],cwd=work,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                until=time.monotonic()+45
                try:
                    while time.monotonic()<until:
                        observed=partial.stat().st_size if partial.exists() else 0
                        if 0<observed<size:break
                        assert p.poll() is None,p.communicate()
                        time.sleep(.001)
                    assert 0<observed<size,('no partial observed',observed,size)
                    stop_tree(p);time.sleep(.05)
                    partial_size=partial.stat().st_size
                    assert 0<partial_size<size,(partial_size,size)
                    return partial_size
                finally:
                    if p.poll() is None:stop_tree(p)
                    p.communicate(timeout=5)
            def stage_status(stage):
                item=json.loads((work/'.lmfiles-local/sftp/item.json').read_text())['item']
                ns=config['dest']+':'+str(config['port'])
                r=subprocess.run(['lmfiles','--query',str(work/'.lmfiles-local/sftp/state.mv'),'query_subset','sftp',json.dumps([item]),json.dumps(dict(stage=stage,namespace=ns,projection='last_success'))],capture_output=True,text=True,timeout=30,check=True)
                return json.loads(r.stdout)[0]['recorded'].get('status')
            upload_config=dict(config,local=str(big),remote=str(remote.parent/'large-upload.txt'))
            ready.write_text(json.dumps(upload_config));upload_partial=pathlib.Path(upload_config['remote']+'.lmfiles-partial')
            uploaded_partial=interrupted_job(upload_partial)
            assert stage_status('published') is None;checks+=1
            job((1,1));checks+=1
            assert hashlib.sha256(big.read_bytes()).digest()==hashlib.sha256(pathlib.Path(upload_config['remote']).read_bytes()).digest();checks+=1
            download_config=dict(config,local=str(big),remote=str(remote.parent/'large-download.txt'))
            ready.write_text(json.dumps(download_config));received=work/'.lmfiles-local/sftp/downloaded.txt.partial'
            downloaded_partial=interrupted_job(received)
            assert stage_status('published')=='ok' and stage_status('downloaded') is None;checks+=1
            job((0,1));checks+=1
            assert hashlib.sha256(big.read_bytes()).digest()==hashlib.sha256((work/'.lmfiles-local/sftp/downloaded.txt').read_bytes()).digest();checks+=1
            if args.gallery:
                # Regenerate a truthful non-noop picture with a fresh item.
                fresh=dict(config,remote=str(remote.parent/'gallery.txt'));ready.write_text(json.dumps(fresh));job((1,1))
                shutil.copyfile(work/'images/sftp_registry.png',ROOT/'docs/images/sftp_registry.png')
        finally:
            stop.write_text('stop')
            try:out,err=daemon.communicate(timeout=10)
            except subprocess.TimeoutExpired:daemon.kill();out,err=daemon.communicate();raise
            assert daemon.returncode==0,(out,err)
    print(json.dumps(dict(ok=True,sftp_pairing_checks=checks,upload_partial_bytes=uploaded_partial,download_partial_bytes=downloaded_partial,total_bytes=size,remote_listing=False,cloud_requests=0)))
if __name__=='__main__':main()
