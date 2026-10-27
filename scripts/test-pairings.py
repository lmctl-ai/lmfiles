#!/usr/bin/env python3
"""Real lmcurl + lmfiles: local HTTP validators, gone suppression and failure receipts."""
import argparse,collections,hashlib,http.server,json,pathlib,shutil,subprocess,tempfile,threading,time
ROOT=pathlib.Path(__file__).resolve().parents[1]
class Handler(http.server.BaseHTTPRequestHandler):
    calls=collections.Counter();validators=[];body=b'synthetic law fixture\n'
    def log_message(self,*args):pass
    def do_GET(self):
        type(self).calls[self.path]+=1;body=self.body;code=200
        if self.path=='/stable':
            validators=(self.headers.get('If-None-Match'),self.headers.get('If-Modified-Since'))
            self.validators.append(validators)
            if validators[0]=='"fixture-v1"' or validators[1]=='Wed, 01 Jan 2025 00:00:00 GMT':code=304;body=b''
        elif self.path=='/slow':time.sleep(.35)
        elif self.path=='/disconnect':self.connection.close();return
        elif self.path=='/fail':code=500
        else:code=404
        self.send_response(code);self.send_header('Content-Length',str(len(body)))
        if self.path=='/stable':
            self.send_header('ETag','"fixture-v1"');self.send_header('Last-Modified','Wed, 01 Jan 2025 00:00:00 GMT')
        self.end_headers()
        try:self.wfile.write(body)
        except BrokenPipeError:pass

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--gallery',action='store_true');args=ap.parse_args();checks=0
    with tempfile.TemporaryDirectory(prefix='lmfiles-http-') as td:
        work=pathlib.Path(td)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True
        threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            base=f'http://127.0.0.1:{server.server_port}'
            urls=work/'urls.txt';urls.write_text(''.join(base+route+'\n' for route in ['/stable','/gone','/slow','/fail','/disconnect']))
            def run(*args):
                r=subprocess.run(['lmscript',str(ROOT/'examples/download_lmcurl.lms'),*args],cwd=work,text=True,capture_output=True,timeout=150)
                assert r.returncode==0,(r.stdout,r.stderr)
                return json.loads((work/'.lmfiles-local/download/report.json').read_text())
            first=run(base,str(urls))
            assert first==dict(requests=9,conditional=1,gone=1,failed=6,skipped=1,fetched=1,cloud_requests=0),first;checks+=1
            assert Handler.calls=={'/stable':2,'/gone':1,'/slow':2,'/fail':2,'/disconnect':2},Handler.calls;checks+=1
            assert Handler.validators==[(None,None),('"fixture-v1"','Wed, 01 Jan 2025 00:00:00 GMT')],Handler.validators;checks+=1
            host=shutil.which('lmfiles');assert host,'lmfiles required on PATH'
            query=subprocess.run([host,'--query',str(work/'.lmfiles-local/download/state.mv'),'query_subset','download','["1","2","3","4","5"]','{"stage":"fetched","namespace":"lmcurl","projection":"latest_attempt"}'],capture_output=True,text=True,timeout=30,check=True)
            rows=json.loads(query.stdout);checks+=1
            assert rows[0]['recorded']['metadata']['hash']==hashlib.sha256(Handler.body).hexdigest();checks+=1
            assert rows[1]['recorded']['status']=='gone';checks+=1
            assert all(r['recorded']['status']=='failed' and r['recorded']['metadata']['failure_count']==2 for r in rows[2:]);checks+=1
            assert rows[2]['recorded']['metadata']['http_status']==0 and rows[4]['recorded']['metadata']['error'];checks+=1
            assert not any('last_success' in r['recorded'] for r in rows[1:]);checks+=1
            again=run(base,str(urls));assert again['requests']==8 and again['conditional']==2 and again['skipped']==2,again;checks+=1
            assert Handler.calls['/gone']==1;checks+=1
            if args.gallery:shutil.copyfile(work/'images/download_lmcurl.png',ROOT/'docs/images/download_lmcurl.png')
        finally:server.shutdown();server.server_close()
    print(json.dumps({'ok':True,'http_pairing_checks':checks,'cloud_requests':0}))
if __name__=='__main__':main()
