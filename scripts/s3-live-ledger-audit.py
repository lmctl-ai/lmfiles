#!/usr/bin/env python3
"""Independent, local-only PUT correlation audit; public output contains counts only."""
import argparse,collections,hashlib,json,pathlib
from s3_correctness_oracle import Model,SEED

def body(key,generation):return f'synthetic source {key} generation {generation}\n'.encode()

def expected(bucket):
    rows={};ids={};revisions={};last_seen={};published={}
    def phase(desired,number):
        for key,data in sorted(desired.items()):
            digest=hashlib.sha256(data).hexdigest()
            if key not in ids:ids[key]=len(ids)+1;revisions[key]=1
            elif last_seen[key]!=digest:revisions[key]+=1
            last_seen[key]=digest
        changed=sorted(k for k in desired if k not in published or published[k]!=desired[k])
        for key in changed:
            data=desired[key];sha=last_seen[key]
            payload={'verb':'put','item':str(ids[key]),'stage':'published','target':bucket,
                     'source_revision':str(revisions[key]),'source_fingerprint':{'hash':sha,'size':str(len(data))},
                     'record_projection':'record','args':{'key':key,'round':number,'sha256':sha}}
            op=hashlib.sha256(json.dumps(payload,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
            rows[op]=dict(key=key,round=number,sha256=sha,attempts=2 if number==3 or number in (15,21) and key==changed[0] else 1)
        published.clear();published.update(desired)
    desired={f'synthetic-{i:03d}.txt':body(f'synthetic-{i:03d}.txt',0) for i in range(100)};phase(desired,0)
    for i in (0,1,2,100,101):desired[f'synthetic-{i:03d}.txt']=body(f'synthetic-{i:03d}.txt',1)
    for i in (98,99):del desired[f'synthetic-{i:03d}.txt']
    phase(desired,1)
    desired={f'synthetic-{i:03d}.txt':body(f'synthetic-{i:03d}.txt',2) for i in range(100)};phase(desired,2)
    desired['synthetic-000.txt']=body('synthetic-000.txt',3);phase(desired,3)
    model=Model(SEED,initial=desired)
    for number in range(11,41):model.round(number);phase(dict(model.files),number)
    return rows

def audit_events(events):
    bucket='lmaws-lmfiles-'+events[0]['run_id'];wanted=expected(bucket);errors=[]
    attempts=[e for e in events if e.get('event')=='attempt' and e.get('operation')=='PutObject']
    receipts=[e['receipt'] for e in events if e.get('event')=='receipt' and e.get('operation')=='PutObject']
    counts=collections.Counter(e['context']['operation_id'] for e in attempts)
    conditional=[r for r in receipts if r.get('http_status')==412]
    if len(conditional)!=1 or conditional[0].get('ok') or conditional[0].get('ambiguous'):errors.append('conditional_412_receipt')
    condition_id=conditional[0].get('operation_id') if len(conditional)==1 else None
    if set(counts)!=set(wanted)|{condition_id}:errors.append('unexpected_or_missing_put_operation')
    if condition_id is not None and counts[condition_id]!=1:errors.append('conditional_attempt_count')
    successes=collections.Counter(r.get('operation_id') for r in receipts if r.get('ok') is True)
    failed=[r for r in receipts if r.get('error_code')=='LocalChecksumMismatch']
    if len(failed)!=1 or failed[0].get('ok') or failed[0].get('ambiguous') or wanted.get(failed[0].get('operation_id'),{}).get('round')!=27:errors.append('failed_upload_receipt')
    for op,row in wanted.items():
        if counts[op]!=row['attempts'] or successes[op]!=row['attempts']:errors.append('put_count:'+str(row['round'])+':'+row['key'])
    for e in attempts:
        ctx=e['context']
        if ctx.get('bucket')!=bucket:errors.append('foreign_bucket')
        if ctx['operation_id'] in wanted and ctx.get('key')!=wanted[ctx['operation_id']]['key']:errors.append('correlation_key')
    for r in receipts:
        if r.get('operation_id') in wanted and r.get('ok') is True:
            row=wanted[r['operation_id']]
            if r.get('sha256')!=row['sha256'] or r.get('key')!=row['key'] or not r.get('checksum_verified'):errors.append('put_content_receipt')
        elif r.get('operation_id')!=condition_id and r.get('error_code')!='LocalChecksumMismatch':errors.append('unexpected_failure_receipt')
    expected_attempts=sum(row['attempts'] for row in wanted.values())+1
    if len(attempts)!=expected_attempts or len(receipts)!=expected_attempts+1:errors.append('attempt_receipt_totals')
    if errors:raise ValueError(errors)
    rounds=[]
    for number in range(11,41):
        selected={op:row for op,row in wanted.items() if row['round']==number}
        rounds.append(dict(round=number-10,status='pass',planned_puts=len(selected),put_attempts=sum(counts[op] for op in selected),touch_puts=0,unexpected_reuploads=0))
    return dict(status='pass',expected_distinct_put_operations=len(wanted),put_attempts=len(attempts),conditional_412_attempts=1,
                documented_uncertain_retries=sum(row['attempts']-1 for row in wanted.values()),unexpected_confirmed_reuploads=0,
                touch_puts=0,failed_local_upload_receipts=1,crash_generation_puts=sum(counts[op] for op,row in wanted.items() if row['round']==2),rounds=rounds)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--work',type=pathlib.Path,required=True);args=ap.parse_args()
    root=args.work/'.lmfiles-local/s3-live';files=list((root/'ledger').glob('*-s3-operations.jsonl'))
    assert len(files)==1,'one private persistent run ledger required'
    events=[json.loads(line) for line in files[0].read_text().splitlines() if line]
    try:result=audit_events(events)
    except ValueError as error:
        (root/'audit-errors.json').write_text(json.dumps({'errors':error.args[0]},indent=2)+'\n');raise SystemExit('PUT correlation audit mismatch; private diagnostics retained')
    (root/'audit-public.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='rounds'}))
if __name__=='__main__':main()
