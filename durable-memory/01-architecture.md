# Purpose and architecture

lmfiles records local lifecycle beliefs for caller-defined stages; no business
rules, credentials, cloud/SSH clients or remote queries exist in core. Java21
host on pinned lmcode-java1.1, H2 MVStore2.3.232, Java/GraalVM native-image.
Item keys are dense long IDs; aliases and stage dictionary intern identities.
Item stage summaries are co-located, compressed and CRC32C-protected; events are
append-only binary records. MVStore supplies crash-safe commits and snapshots.
Positional I/O only; no mmap, JNI/native database component or C++ sidecar.

One writer OS lock. Each mutation/batch syncs before acknowledgment; failed
operations roll back. Sparse caller-declared differences update in the same
transaction and enumerate pending entries only. Leases use monotonic owner time,
persist duration, and conservatively restart that duration after reopen.
Tokens fence local completion. Scripts decide actions and report actual revisions.

Exact API: docs/API.md and generated schema. Default memory budget256MiB;
bounded records/pages/batches, VM charges and H2 cache limits, not an exact RSS cap.
