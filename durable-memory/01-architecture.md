# Purpose and architecture

lmfiles is a Java21/lmcode-java1.1 host for caller-defined lifecycle beliefs.
H2 MVStore2.3.232 uses positional I/O, never mmap. No networking, credentials,
cloud/SSH SDK, JNI or C++ component exists in core. Scripts own every policy.

Dense item numbers, interned stage/alias families, co-located stage projections,
append-only events, sparse caller-registered difference frontiers. Format3 uses
compact checksummed binary event records with MVStore page compression; item
records retain bounded Deflate frames. Equal success/latest summaries share data.

One writer OS lock; bounded transactions sync before acknowledgment and roll back
on failure. Snapshots pin versions. Fixed lease deadlines, clock eras and owner
generations fence expired/replaced holders across reopen; see docs/LEASES.md.
Formats1/2 upgrade through a synced shadow and retained backup, never reset.

Registry.scan is optional local ingestion: stat/hash reuse, directory shards,
streaming traversal, 256-observation/byte-bounded commits; no deletion or stages.
Missing paths are not automatically marked gone. See docs/SCAN.md.

Scalars/JSON and Registry/Snapshot/Cursor/WriteBatch/Rows handles only. Memory
budget default256MiB bounds records/pages/batches/cache/VM arena, not exact RSS.
Exact contracts: generated schema, docs/API.md and docs/FORMAT_UPGRADE.md.
