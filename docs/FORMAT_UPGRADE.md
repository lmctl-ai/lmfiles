# Released database compatibility

lmfiles 0.1.0 created format 1; 0.1.1 created format 2. Main (0.1.2)
creates format 3. Opening either released format performs a bounded shadow
migration under the exclusive writer lock. No reset is required.

Format 2 interned alias families and omitted `last_success` when identical to the
successful latest attempt. Format 3 changes event records: numeric item/sequence
fields, length-delimited UTF-8 operation/timestamp and canonical JSON metadata,
inside a version-2 CRC32C frame. Events are not individually Deflated; MVStore
compresses pages across records. Item frames remain version 1. Logical records,
IDs, event timestamps, claims, fences, replay markers and frontiers are preserved.

The upgrader requires at least 32 MiB configured memory and free space of at least
2 × the old DB size + 16 MiB. It rejects unknown maps or malformed records, copies
supported maps in bounded row/byte batches, syncs the shadow and old DB, retains a
synced `<db>.v1.bak` or `<db>.v2.bak`, then atomically replaces the DB. Existing
backups receive unique suffixes instead of being overwritten. Directory sync is
required on POSIX; Windows uses the available filesystem provider. Failure before
the swap leaves the original database intact. Crash recovery sees a complete old
or new store, never a mixed keyspace; abandoned shadow files are ignored.

Old readers reject format 3. Stop writers and **copy** the appropriate retained
backup to a separate restore path before downgrading. A downgrade loses changes
made after upgrade; never open the new DB with an old binary as a repair attempt.
Backups contain private registry data and are Git-ignored. Nonempty unversioned
stores are rejected without reset.

The snapshot JSONL envelope remains wire format 2, with `store_format:3` declaring
the changed binary payloads. Main imports wire formats 1/2 from store formats
1/2/3 transactionally, normalizing aliases/summaries/events. Missing footer,
invalid checksum, unsupported maps or malformed event metadata abort the import.
Released 0.1.1 rejects `store_format:3`; 0.1.0 rejects wire format 2.

Linux managed/native gates test preservation, corruption/truncation rejection,
byte aliases, identity lookup, old/latest success ordering, one-time migration,
retained backups, snapshot import and kills on both sides of atomic replacement.
