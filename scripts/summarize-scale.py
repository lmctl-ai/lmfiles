#!/usr/bin/env python3
"""Reduce private guarded-run logs to synthetic counts/status and disk readings."""
import argparse,hashlib,json,pathlib

def summarize(report):
    settings=report['settings'];all_reads=report.get('guard_readings',[])
    steps={};previous_built=0
    for phase in report['phases']:
        count=phase.get('count')
        if count is None:continue
        group=steps.setdefault(count,{'items_requested':count,'operations':[]})
        result=phase.get('worker_result')
        if result is None:
            result=next((r for r in reversed(phase.get('stdout',[])) if isinstance(r,dict) and r.get('event')=='result'),{})
        reads=phase.get('guard_readings',[])
        row={'operation':phase['phase'],'status':phase.get('status',phase.get('state','failed')),
             'seconds_informational':result.get('seconds',phase.get('seconds'))}
        for field in ['exact_counts','samples','subset_rows','frontier_rows','updated','failed_after_success_verified',
                      'claimed_and_completed','confirmed_records_retained','phantoms','backup_verified','upgraded_items',
                      'format2_bytes','post_upgrade','source_bytes','conservative_extra_bytes']:
            if field in result:row[field]=result[field]
        if 'db_bytes' in result:
            row['db_bytes']=result['db_bytes'];row['bytes_per_item_informational']=result['db_bytes']/count
        if reads:
            row['guard']={'samples':len(reads),'free_before':reads[0]['free_bytes'],'free_after':reads[-1]['free_bytes'],
                          'minimum_free':min(r['free_bytes'] for r in reads),'maximum_directory':max(r['dir_bytes'] for r in reads)}
        if phase['phase']=='build' and row['status']=='ok':
            row['items_added']=count-previous_built;previous_built=count
            row['items_per_second_informational']=row['items_added']/row['seconds_informational']
        if phase['phase']=='update' and row['status']=='ok':
            row['updates_per_second_informational']=row['updated']/row['seconds_informational']
        if phase['phase']=='crash' and phase.get('returncode')==-9:
            row['signal']='SIGKILL';row['half_written_commit_observed']=any(isinstance(r,dict) and r.get('event')=='kill_ready' for r in phase.get('stdout',[]))
        group['operations'].append(row)
    def largest(operation):
        return max((n for n,g in steps.items() if any(r['operation']==operation and r['status']=='ok' for r in g['operations'])),default=0)
    removed=not pathlib.Path(settings['work']).exists()
    return {'state':report['state'],'scope':'Local synthetic correctness; timings and sizes informational only',
            'volume':settings.get('volume','linux'),
            'budget_seconds':settings.get('budget_seconds'),
            'next_count':report.get('next_count'),
            'projected_next_seconds':report.get('projected_next_seconds'),
            'guards':{'minimum_volume_free_bytes':settings['min_free_bytes'],
                      'minimum_root_free_bytes':settings.get('root_min_free_bytes',settings['min_free_bytes']),
                      'maximum_registry_bytes':settings['max_dir_bytes'],
                      'readings':len(all_reads),'minimum_volume_free_observed':min((r['free_bytes'] for r in all_reads),default=None),
                      'minimum_root_free_observed':min((r['free_bytes'] for r in all_reads),default=None) if settings.get('volume','linux')=='linux' else None,
                      'maximum_registry_observed':max((r['dir_bytes'] for r in all_reads),default=None)},
            'cleanup_verified':report.get('cleanup_verified',False) and removed,'directory_independently_absent':removed,
            'largest_indexed':largest('build'),'largest_compacted':largest('compact'),'largest_upgraded':largest('upgrade'),
            'steps':[steps[n] for n in sorted(steps)],'aws_requests':0,'admin_commands':0,'tag_or_release_created':False}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('report',type=pathlib.Path);parser.add_argument('output',type=pathlib.Path)
    args=parser.parse_args();result=summarize(json.loads(args.report.read_text()))
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({key:result[key] for key in ['state','cleanup_verified','largest_indexed','largest_compacted','largest_upgraded']}))
if __name__=='__main__':main()
