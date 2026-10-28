#!/usr/bin/env python3
"""Corrupt synthetic event streams to test PUT audit distribution, not just totals."""
import copy,importlib.util,pathlib,unittest
spec=importlib.util.spec_from_file_location('audit',pathlib.Path(__file__).with_name('s3-live-ledger-audit.py'));audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
class Checks(unittest.TestCase):
    def events(self):
        bucket='lmaws-lmfiles-syntheticrun';wanted=audit.expected(bucket);events=[{'run_id':'syntheticrun'}]
        for op,row in wanted.items():
            for _ in range(row['attempts']):
                events.append({'event':'attempt','operation':'PutObject','context':{'operation_id':op,'bucket':bucket,'key':row['key']}})
                events.append({'event':'receipt','operation':'PutObject','receipt':{'operation_id':op,'key':row['key'],'sha256':row['sha256'],'ok':True,'checksum_verified':True}})
        events.extend([{'event':'attempt','operation':'PutObject','context':{'operation_id':'condition','bucket':bucket,'key':'synthetic-000.txt'}},{'event':'receipt','operation':'PutObject','receipt':{'operation_id':'condition','ok':False,'ambiguous':False,'http_status':412}}])
        failed=next(op for op,row in wanted.items() if row['round']==27)
        events.append({'event':'receipt','operation':'PutObject','receipt':{'operation_id':failed,'ok':False,'ambiguous':False,'error_code':'LocalChecksumMismatch'}})
        return events
    def test_expected_count_and_positive(self):
        report=audit.audit_events(self.events());self.assertEqual(report['expected_distinct_put_operations'],244);self.assertEqual(report['put_attempts'],248);self.assertEqual(report['crash_generation_puts'],100)
    def test_same_total_wrong_distribution(self):
        events=self.events();attempts=[e for e in events if e.get('event')=='attempt'];a=attempts[0];a['context']['operation_id']=attempts[-2]['context']['operation_id']
        with self.assertRaises(ValueError):audit.audit_events(events)
    def test_unplanned_touch_attempt(self):
        events=self.events();next(e for e in events if e.get('event')=='attempt')['context']['operation_id']='unexpected-touch'
        with self.assertRaises(ValueError):audit.audit_events(events)
    def test_missing_checksum_or_foreign_bucket(self):
        events=self.events();next(e for e in events if e.get('event')=='receipt')['receipt']['checksum_verified']=False
        with self.assertRaises(ValueError):audit.audit_events(events)
        events=self.events();next(e for e in events if e.get('event')=='attempt')['context']['bucket']='foreign'
        with self.assertRaises(ValueError):audit.audit_events(events)
if __name__=='__main__':unittest.main()
