# Guarded local index/get/update correctness campaign

The Lead authorized this local campaign independently of the blocked S3 test.
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

Current campaign results will be recorded here and in a sanitized machine-readable
summary after completion. Guard readings, pass/fail, exact counts and informational
throughput/bytes are reported; no large-tier guarantee is inferred from one disk.

A review found and fixed a test-fixture conversion window: replacement now uses
a single atomic REPLACE_EXISTING move, not delete-then-move. Regression checks
prove that a missing conversion source preserves the acknowledged original and
a complete conversion replaces it. The production format upgrader already used
atomic replacement. No registry format or on-disk version changed.
