# Remaining scope and limits

Performance and scale measurement are not planned; correctness is the bar.
No100M benchmark, scale-qualification goal or large-upload campaign.

1C-a adds real loopback lmcurl/lmssh jobs, optional local scan and format3 compact
events. The10k fixture is retained as historical informational evidence only;
it is not a scale or performance qualification. See docs/MEASUREMENTS.md.

Remaining1C: general attribute/hash/time/aggregate indexes, change cursors,
checkpoints/event archives, coarse multi-process mailbox, packed item/raw-digest
and alias layouts if justified, broader raw POSIX path I/O. Scanning does not
infer deletion or safely skip directory contents
from directory mtime alone. Stat-preserving writers need always_hash.

The authorized100-object/30-round S3 run is COMPLETE using released lmaws0.1.23,
profile lmaws only, us-east-1, one exact tagged bucket, cap$0.05. All phases,
reconciliation and listing-import no-op passed. Independent fresh-executor
NoSuchBucket/404 proves zero leftovers.1433 requests,estimated$0.0018854,maxskew
1.081s. Raw receipts stay private; docs/S3_100_RESULTS.json is sanitized.
No further live run is implicitly authorized by the published example.

SDK JSON numbers use Double: use decimal strings for64bit metadata. SDK cache
arena credits on close and PlanLog.unconfirmed per-line fsync optimization remain
review lows11/12; no guarantees were weakened. Lead owns future releases.

The completed S3 test includes30 seeded rounds (seed20261027), the independent
model/three-way oracle, checksum/timestamp reconciliation and explicit listing
import after DB loss. Every round converged.
Original client upload intervals cannot be reconstructed from listing alone.

Latest Lead override authorizes a LOCAL1M/5M/20M correctness ramp independently
of S3, guarded by40GB root free minimum and25GB registry maximum, mandatory
temporary DB cleanup. Informational numbers only. See docs/LOCAL_SCALE_TEST.md;
do not confuse this specific campaign with an open-ended performance roadmap.

The authorized local ramp completed through20M items and removed its DB. All core
operations/doctor passed; compaction qualified through5M and format2→3 through1M.
Larger maintenance was skipped by conservative transient-space admission, not
claimed as tested. Minrootfree82.33GB/maxfixture17.70GB; no hard guard violation.
See docs/LOCAL_SCALE_RESULTS.json for exact counts and code/archive provenance.

The E: follow-up is an explicitly bounded local correctness campaign, not a scale
qualification roadmap. Volume operations only on9p/DrvFS; all durability tests
stay on Linux ext4. No additional live/cloud run is authorized.

E: follow-up completed1M core volume checks, then stopped before10M on projected
one-hour budget; temporary directory removed.9p rename probe observed a missing
destination, so durability remains unsupported. See docs/DRVFS_LIMITATIONS.md.
