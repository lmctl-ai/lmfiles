# Released database compatibility

lmfiles 0.1.0 created database format1. lmfiles 0.1.1 uses format2. The checksummed
compressed record frame remains version1; the enclosing DB version controls the
alias-key and stage-summary representation.

When0.1.1 opens a format1 DB, it holds the existing exclusive writer lock and:

1. Checks memory (at least32MiB configured) and free space for a shadow plus backup
   (at least twice the old file size plus16MiB free).
2. Copies every supported map to a sibling shadow file in bounded row/byte batches,
   validating item/event CRCs. Unknown maps abort instead of being discarded.
3. Interns alias `(kind,namespace)` families and stores each value separately from
   those repeated names. Primary identity families remain internal/reserved.
4. Elides `last_success` only when it exactly equals the successful latest attempt.
   Distinct earlier success under a failed/newer attempt remains stored. Logical
   query results preserve both views.
5. Syncs the complete shadow, closes the old store, and copies/syncs a retained
   `<db>.v1.bak` (a unique `.v1-<nonce>.bak` if that name already exists).
6. Atomically replaces the DB and syncs the directory where supported. A crash
   before/after the swap leaves a complete format1 or format2 DB, never a mixed
   keyspace. Temporary files from a killed process are ignored on reopen.

The upgrader never resets an existing DB, overwrites a previous backup or fabricates
history. Item IDs, metadata, events, frontiers, replay markers and claims are
preserved. Ordinary failures before replacement leave format1 intact; the backup
is retained after success. Unsupported atomic replacement/fsync fails explicitly.
Do not directly open the DB with another writer during upgrade.

The0.1.0 binary refuses format2 (`unsupported-registry-format`). To roll back,
stop all writers and **copy** the retained format1 backup to a separate restore
path before using0.1.0. A reset is not required. Never run0.1.0 on format2 as an
attempted repair. Backups contain private data and are ignored by Git.

New snapshot exports use wire format2 with `store_format:2`. Version0.1.1 also
imports legacy wire-format1 snapshots, upgrading their aliases/summaries inside
the import transaction; truncation/corruption rolls the entire import back.
Version0.1.0 rejects new wire-format2 snapshots at the header.

The Linux gates test logical preservation, structured-byte aliases, identity
lookup, failed/latest versus last-success, no repeated migration, backup readability,
legacy/new snapshot import and kills on both sides of the atomic replacement.
