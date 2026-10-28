#!/usr/bin/env python3
"""Unit checks of independent model/reconciliation, not a live or fake S3 run."""
import base64,copy,unittest
from s3_correctness_oracle import Model,SEED,ROUNDS,PUT_CAP,fingerprint,reconcile,soak_plan,timestamp_ms

class OracleChecks(unittest.TestCase):
    def views(self):
        local={'synthetic-000.txt':dict(fingerprint(b'synthetic\n'),content_type='text/plain',cache_control='max-age=60')}
        f=local['synthetic-000.txt']
        registry={'synthetic-000.txt':dict(present=True,status='ok',confirmed=True,size=f['size'],sha256=f['sha256'],etag='"'+f['md5']+'"',version=None,request_started='2026-01-01T00:00:00Z',confirmed_at='2026-01-01T00:00:02Z',round=1,revision=1)}
        remote={'synthetic-000.txt':dict(size=f['size'],etag='"'+f['md5']+'"',version=None,checksum_sha256=base64.b64encode(bytes.fromhex(f['sha256'])).decode(),last_modified='2026-01-01T00:00:01Z',content_type=f['content_type'],cache_control=f['cache_control'])}
        return local,registry,remote

    def test_java_instant_nanosecond_timestamps(self):
        self.assertEqual(timestamp_ms('2026-01-01T00:00:00.123456789Z'),timestamp_ms('2026-01-01T00:00:00.123456Z'))
        local,registry,remote=self.views()
        registry['synthetic-000.txt']['request_started']='2026-01-01T00:00:00.123456789Z'
        registry['synthetic-000.txt']['confirmed_at']='2026-01-01T00:00:02.123456789Z'
        self.assertTrue(reconcile(local,registry,remote)['ok'])

    def test_exact_three_way_match(self):
        local,registry,remote=self.views()
        result=reconcile(local,registry,remote,expected=local)
        self.assertTrue(result['ok'],result)
        self.assertEqual(result['observed_max_skew_seconds'],0)

    def test_key_sets_and_confirmed_deletion(self):
        for view in range(3):
            rows=list(self.views());rows[view]['synthetic-extra.txt']=copy.deepcopy(next(iter(rows[view].values())))
            self.assertFalse(reconcile(*rows)['ok'])
        local,registry,remote=self.views();key=next(iter(local));local.clear();remote.clear()
        deletion=reconcile(local,registry,remote,origins={key:7})
        self.assertFalse(deletion['ok']);self.assertEqual(deletion['mismatches'][0]['round'],7)
        registry[key]['present']=False
        self.assertTrue(reconcile(local,registry,remote)['ok'])
        registry[key]['confirmed']=False
        self.assertFalse(reconcile(local,registry,remote)['ok'])

    def test_each_integrity_field(self):
        mutations=[(0,'size',0),(0,'sha256','0'*64),(0,'md5','0'*32),
                   (1,'size',0),(1,'sha256','0'*64),(1,'etag','wrong'),(1,'version','unexpected'),
                   (1,'confirmed',False),(1,'status','failed'),(2,'checksum_type','COMPOSITE'),(2,'size',0),(2,'etag','wrong'),
                   (2,'content_type','wrong'),(2,'cache_control','wrong'),(2,'checksum_sha256','bad')]
        for view,field,value in mutations:
            with self.subTest(view=view,field=field):
                rows=list(self.views());next(iter(rows[view].values()))[field]=value
                result=reconcile(*rows)
                self.assertFalse(result['ok'],result)
                self.assertEqual(result['mismatches'][0]['key'],'synthetic-000.txt')
                self.assertEqual(result['mismatches'][0]['round'],1)

    def test_metadata_checksum_fallback_and_disagreement(self):
        local,registry,remote=self.views();row=next(iter(remote.values()));sha=next(iter(local.values()))['sha256']
        row['metadata']={'sha256':sha};self.assertTrue(reconcile(local,registry,remote)['ok'])
        del row['checksum_sha256'];self.assertTrue(reconcile(local,registry,remote)['ok'])
        row['checksum_sha256']=base64.b64encode(b'\0'*32).decode();self.assertFalse(reconcile(local,registry,remote)['ok'])
        del row['checksum_sha256'];del row['metadata'];self.assertFalse(reconcile(local,registry,remote)['ok'])
        local,registry,remote=self.views()
        for view in [local,registry,remote]:next(iter(view.values()))['size']=None
        self.assertFalse(reconcile(local,registry,remote)['ok'])

    def test_timestamp_bounds_and_invalid_dates(self):
        for when,passes in [('2025-12-31T23:55:00Z',True),('2025-12-31T23:54:59Z',False),('2026-01-01T00:05:02Z',True),('2026-01-01T00:05:03Z',False),('2026-01-01T00:00:01',False)]:
            rows=list(self.views());next(iter(rows[2].values()))['last_modified']=when
            self.assertEqual(reconcile(*rows)['ok'],passes,when)
        rows=list(self.views());next(iter(rows[1].values()))['confirmed_at']='2025-01-01T00:00:00Z'
        self.assertFalse(reconcile(*rows)['ok'])

    def test_ordering_and_equal_second_precision(self):
        rows=self.views();history=[dict(key='synthetic-older.txt',round=0,revision=90,last_modified='2026-01-01T00:00:01Z')]
        # Revision90 on another item is unrelated to revision1; timestamps may tie.
        self.assertTrue(reconcile(*rows,history=history)['ok'])
        history[0]['last_modified']='2026-01-01T00:00:02Z'
        self.assertFalse(reconcile(*rows,history=history)['ok'])
        history=[dict(key='synthetic-000.txt',round=0,revision=0,last_modified='2026-01-01T00:00:02Z')]
        self.assertFalse(reconcile(*rows,history=history)['ok'])

    def test_expected_model_independent_of_local_files(self):
        local,registry,remote=self.views();expected=copy.deepcopy(local)
        expected['synthetic-000.txt']['sha256']='0'*64
        self.assertFalse(reconcile(local,registry,remote,expected=expected)['ok'])

    def test_seed_reproducibility_coverage_and_budget(self):
        first=soak_plan();self.assertEqual(first,soak_plan(SEED));self.assertNotEqual(first,soak_plan(SEED+1))
        self.assertEqual(len(first['rounds']),ROUNDS);self.assertLessEqual(first['put_admission_bound'],PUT_CAP)
        self.assertTrue(all(first['operations_by_type'][kind]>0 for kind in ['create','update','touch','delete','rename','recreate','noop']),first['operations_by_type'])
        for row in first['rounds']:
            self.assertLessEqual(len(row['expected']),100)
            for operation in row['operations']:
                kind=operation['applied'];plan=row['plan']
                if kind=='rename':
                    self.assertIn(operation['key'],plan['deletes']);self.assertIn(operation['target'],plan['adds'])
                elif kind in ('create','recreate'):self.assertIn(operation['key'],plan['adds'])
                elif kind=='delete':self.assertIn(operation['key'],plan['deletes'])
                elif kind=='update':self.assertIn(operation['key'],plan['changed'])
                elif kind=='touch':self.assertNotIn(operation['key'],plan['adds']+plan['changed']+plan['deletes'])
            if row['round'] in first['faults']:
                self.assertTrue(row['plan']['adds'] or row['plan']['changed'],'fault round requires an upload')
        with self.assertRaises(ValueError):soak_plan(rounds=31)
        with self.assertRaises(ValueError):reconcile(*self.views(),tolerance_seconds=301)

    def test_touch_and_rename_rules(self):
        key='synthetic-000.txt';body=f'synthetic source {key} generation 1\n'.encode()
        collision=Model(initial={key:body});collision.operation('update')
        self.assertNotEqual(collision.files[key],body)
        model=Model();before=model.view();operation=model.operation('touch')
        self.assertEqual(model.view(),before);self.assertEqual(operation['applied'],'touch')
        deleted=model.operation('delete');self.assertNotIn(deleted['key'],model.files)
        recreated=model.operation('recreate');self.assertEqual(recreated['key'],deleted['key'])
        model.operation('delete');before=model.view();rename=model.operation('rename')
        self.assertNotIn(rename['key'],model.files);self.assertIn(rename['target'],model.files)
        self.assertEqual(model.view()[rename['target']]['sha256'],before[rename['key']]['sha256'])

if __name__=='__main__':
    print('Independent correctness-model seed:',SEED,flush=True)
    unittest.main()
