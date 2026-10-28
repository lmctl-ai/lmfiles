# Guarded local index/get/update correctness campaign

The Lead authorized this local campaign independently of the S3 test (now complete).
No cloud, AWS/admin command, credentials, real data, release or tag is involved.
The earlier no-scale roadmap decision does not prohibit this specifically
requested bounded correctness run. Timings and bytes/item are informational.

Build main, then run from the repository:

```sh
mvn -q package
python3 scripts/test-scale.py --report /tmp/lmfiles-scale-report.json \
  --progress-log /tmp/lmfiles-scale-progress.jsonl
```

Defaults:1M,5M,20M cumulative items. Each child uses the managed Java host with
-Xmx1536m and a64MiB MVStore cache. A command argv JSON override is supported;
no shell interpolation. The scale worker is a local diagnostic (`--scale-phase`),
not new registry policy or a remote API. Smaller steps support regression smoke
checks; the million-item campaign is not a default CI gate.

## Hard guards and cleanup

Settings are printed **before the first phase**. All limits below are decimal
bytes: root `/` free space must remain at least **40,000,000,000**; the dedicated
registry directory must remain at most **25,000,000,000**. Limits cannot be relaxed
through command-line flags. The parent checks before/after phases and every0.5s;
the worker checks between512-item batches and during long verification/copy loops.
Logical and allocated file sizes are counted conservatively. Any failed guard
reading fails closed, kills/reaps the child process group and aborts the campaign.

Only a fresh marked `/tmp/lmfiles-scale-<unique>` directory is used. It is removed
on success, guard stop, child failure, timeout, SIGINT or SIGTERM. No keep option.
The final report verifies cleanup. Reports/logs live outside that directory;
phase checkpoints are written atomically. A per-phase timeout bounds a hung run.
Guard unit tests inject low space, directory overrun, read failures and process
failures; sparse files test logical-size accounting without filling the disk.

Compaction and format migration need temporary space. The worker checks estimated
peak headroom before starting them and reports `skipped_headroom` when unsafe;
the real hard guards continue throughout. Initial upgrade admission reserves a
conservative multiple of source size. Subsequent steps use the preceding verified
format2/3 bytes-per-item with25% margin to estimate conversion/shadow/backup peaks.
These estimates are not a substitute for the live guard. Each step attempts
maintenance before growing the next one, so a smaller successful upgrade remains
proven if the largest dataset cannot safely fit the transient copies.

## Independent expected state and operations

All identities, aliases, attributes and SHA256 inputs are synthetic. The frontier
is registered before ingestion. Batches exercise item_upsert, alias_add, observe,
fetched and published records. One of every1,000 initial publications is pending.
Expected counts are computed independently: N items,2N identity/user aliases,
and5N-ceil(N/1000) initial audit events. Updates, claims and acknowledged crash
markers add their independently calculated event counts. Doctor checks persisted
sequences and integrity separately.

Each step verifies10,000 seeded, distinct sample items plus first/last and batch/
step boundaries. Identity lookup exercises Store's nonmutating identity index
(the same lookup used by scan); alias and numeric lookups use query_subset.
Fields, observations, stage metadata, hashes, revisions, latest attempts and
last_success are checked against the synthetic model. Unknown identities/aliases/
IDs must return absence/unknown. The sample's batched query_subset has an exact
requested/result count and ordering. Total item/alias/event counts are exact.

A seeded affine permutation selects exactly1% without replacement. Every selected
item receives new hash/attribute/revision, a failed attempt after a confirmed
success, and a stage re-record. Every updated item is checked, including the
prior success retained under failure. A declared subset remains failed/pending;
the frontier's entire ordered membership/count is compared to an independently
computed predicate, without keeping millions of expected rows in memory.

A128-item slice uses claim/complete leases and rejects repeated stale completion.
The DB is closed/reopened between phases, with sample/count/frontier checks after
reopen. In the crash phase a normal committed marker is acknowledged first; a
second batch changes it and inserts512 phantom identities. The parent sends
SIGKILL only after the test FileStore has actually written and synced half an H2
commit chunk. Recovery must retain the acknowledged marker, show no phantom IDs
or events, keep exact counts and pass doctor.

Compaction is followed by re-verification. The upgrade phase creates a genuine
format2 layout by streaming the same maps and legacy event frames, then invokes
the normal backed-up2-to3 upgrader. It checks doctor, all standard samples/counts/
frontier, and the retained format2 backup's header/counts/sample rows. Only this
throwaway fixture's redundant backup is removed after verification. No registry
format change is made by this test.

Campaign results are recorded below and in [LOCAL_SCALE_RESULTS.json](LOCAL_SCALE_RESULTS.json).
No large-tier guarantee is inferred from one disk or this particular synthetic profile.

A review found and fixed a test-fixture conversion window: replacement now uses
a single atomic REPLACE_EXISTING move, not delete-then-move. Regression checks
prove that a missing conversion source preserves the acknowledged original and
a complete conversion replaces it. The production format upgrader already used
atomic replacement. No registry format or on-disk version changed.


## Completed campaign

**PASS through the requested20M indexed items.** This was the managed JVM worker;
native full gates and a1,000-item native all-phase smoke passed separately. Each
step passed indexing, exact counts, identity/alias/unknown lookups, all selected
updates, latest/success projections, exact subset/frontier membership, leased
claim completion, reopen, real SIGKILL recovery and doctor.

| Items | Aliases | Audit events after crash recovery | Updated/checked | Sample items + boundaries | Pending frontier after claims |
|---:|---:|---:|---:|---:|---:|
| 1,000,000 | 2,000,000 | 5,055,805 | 10,000 | 10,009 | 4,186 |
| 5,000,000 | 10,000,000 | 25,335,306 | 50,000 | 10,012 | 24,027 |
| 20,000,000 | 40,000,000 | 101,453,925 | 200,000 | 10,015 | 101,161 |

Every step completed128 claims; active claims at the checkpoints were0. Failed
attempts preserved the previous successful revision for every updated item.
Recovery retained acknowledged markers and showed0 phantom identities/rows/events.
The snapshot/expected-state model stays bounded; no millions-item expected set is
held in RAM. Counts and sampled content are checked independently of stored rows.

| Maintenance |1M |5M |20M |
|---|---|---|---|
| Compaction + re-verification | PASS | PASS | SKIPPED—headroom |
| Format2→3, backup samples, doctor + re-verification | PASS | SKIPPED—headroom | SKIPPED—headroom |

These are pre-allocation safety skips, not completed maintenance tests or actual
hard-limit violations. With the documented25% migration-estimate margin, the
projected peaks were27.10GB for the5M upgrade,53.10GB for20M compaction and108.41GB
for20M upgrade. The cap stayed25GB. Largest verified compaction:5M; largest
verified format upgrade:1M. No100M attempt and no attempt to fill the disk.

| Step | Newly indexed | Index items/s | Updated items/s | Physical DB bytes/item at end of step |
|---:|---:|---:|---:|---:|
| 1,000,000 | 1,000,000 | 5,260 | 675 | 1,113.5 |
| 5,000,000 | 4,000,000 | 5,277 | 811 | 955.0 |
| 20,000,000 | 15,000,000 | 6,726 | 1,469 | 884.9 |

Numbers are informational observations from a shared machine. They include normal
MVStore retained pages and differ by operation history; they are not latency,
capacity or steady-state compression guarantees. This profile has one explicit
alias plus identity and two stages, unlike the historical10k profile.

Guard summary (decimal GB):

- Minimum root free observed: **82.33GB**, above40GB.
- Maximum fixture observed: **17.70GB**, below25GB.
- Recorded guard readings: 8,914.
- Final temporary-directory cleanup: **verified**, with independent path-absence check.
- Root free after cleanup: 104.88GB (other teams may change it).

No registry correctness failure was observed. The test-only atomic conversion
window described above was fixed with3 regression assertions; no format change
was needed. The campaign used the frozen9a3fba0 worker archive; fd45ed5 corrects
that test-fixture failure window and passes the managed/native gates and native
smoke. Successful conversion data were reverified in the campaign. The production
upgrader already used atomic replacement. Exact revisions and archive digest are
in the machine-readable result.

Local gates:271 registry assertions,21 subprocess checks and17 disk-guard unit
tests per gate (managed/native), plus9 pure S3-oracle tests. No AWS/admin command,
CI dispatch, tag or release was performed. The separately authorized S3 run is complete; see [S3_100_TEST.md](S3_100_TEST.md).


## E: volume-only follow-up

`--volume e` selects a fresh `/mnt/e/lmfiles-scale-<unique>` directory, fixed
**500,000,000,000 bytes free** and **150,000,000,000 bytes fixture maximum**.
The Linux root-free minimum remains40GB as an additional safeguard. Only build,
verify, update, claim and reopen run there. The Java worker independently refuses
crash, recovery, doctor, compaction and format-upgrade phases on E:. Core registry
operations still perform their ordinary writes/syncs; their success on E: establishes
functional observations only. **9p/DrvFS is not a supported durability target.**
See [DRVFS_LIMITATIONS.md](DRVFS_LIMITATIONS.md) for the small explicit mount probe.

```sh
python3 scripts/test-scale.py --volume e --budget 3600 \
  --report /tmp/lmfiles-e-campaign.json
```

E: default ramp:1M,10M,50M,100M cumulative items. This run chooses a **one-hour
wall-clock budget** rather than the permitted six-hour ceiling. Both monitors and
workers retain the disk guards. Before starting the next size, the runner projects
the preceding full step linearly with25% margin and stops if it cannot fit the
remaining budget. A mid-phase deadline kills/reaps the worker and deletes the
fixture; an incomplete size is never reported as passing. `budget_stopped` is a
clean planned stop. Reports and logs must be outside the fixture, which is removed
on every exit. No other E: files are inspected, altered or cleaned up.

Durability-sensitive gates remain on Linux ext4: lock/stale-lock, sync policy,
torn writes, SIGKILL recovery, compaction fault points and format2→3 atomic swap.
The historical20M Linux kill/recovery result remains valid; maintenance admission
was more restrictive. A separate guarded Linux follow-up attempts2M and8M sizes,
with the same40GB/25GB hard limits. It may skip maintenance before allocating
unsafe temporary copies. E: capacity never relaxes those Linux limits.


## Completed two-volume follow-up

All admitted steps passed; both ramps then returned `budget_stopped` before the
next size, and all temporary directories were independently verified absent.
No registry mismatch, hard-guard violation, cloud request, tag or release occurred.
No on-disk format or registry API changed.

| Run | Completed size | Exact aliases / events | Selected updates | Samples | Final pending frontier | Result |
|---|---:|---|---:|---:|---:|---|
| E:9p volume only |1,000,000 |2,000,000 /5,055,804 |10,000 |10,009 |4,186 |All5 phases passed |
| Linux ext4 |2,000,000 |4,000,000 /10,111,475 |20,000 |10,009 |8,506 |All11 phases passed |

Each completed128 claims with0 active claims afterward. Updates verified every
selected new revision/attribute and failed-after-success projection. Sampled
identity/alias/unknown lookups and the complete independently computed frontier
matched exactly. Linux additionally retained acknowledged data after actual
SIGKILL mid-batch, found0 phantoms, passed doctor, compacted and reverified,
and upgraded2M items from format2 to3 while validating the retained format2 backup.
The final upgrade repeated exact counts, samples and frontier membership.

| Run | Min free observed | Peak directory | Completed step wall time | Next size / projected time |
|---|---:|---:|---:|---|
| E: |2,847.64GB |0.926GB |295.25s |10M /3,690.62s |
| Linux |93.55GB |8.687GB |1,022.45s |8M /5,112.24s |

Each run had a3,600s budget; next-step projections include25% margin. No10M/50M/
100M E: step and no8M Linux step was started. This is a budget stop, not a complete
ramp qualification. Timings/bytes per item are informational: E:925.66bytes/item
at its final checkpoint; Linux1,118.65bytes/item after upgrade, including normal
store layout/operation history. No throughput or capacity guarantee is inferred.

Across the separate completed Linux campaigns, the largest proven core/SIGKILL
size remains20M, largest compaction5M, and largest format2→3 upgrade is now2M.
Lock/stale-lock, sync policy, torn-write, compaction fault and atomic-swap fault
fixtures ran only on ext4 in both managed/native gates. Those fault fixtures are
small; do not imply every fault injection was repeated on the largest dataset.
Linux gates passed271 registry assertions and21 subprocess checks; current guard
unit suite25 tests. Native also passed its reserved-address-space check and an
E:1,000-item functional smoke. Six native E: durability-phase requests were rejected
before any DB was created. Gallery execution was skipped by the normal default;
its existing captions/PNG checks passed. No gallery content changed.

[EXT4_FOLLOWUP_RESULTS.json](EXT4_FOLLOWUP_RESULTS.json) and
[DRVFS_VOLUME_RESULTS.json](DRVFS_VOLUME_RESULTS.json) contain sanitized per-phase
counts, timings, guard observations, cleanup and worker provenance. The
[small mount probe](DRVFS_LIMITATIONS.md) observed a missing destination during
concurrent rename on9p: **E: is not a supported durability target**. This mount
limitation is not a registry correctness defect or a reason to weaken guarantees.
