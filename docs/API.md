# Implemented registry API

The generated schema is authoritative. Context `registry.open(path, namespace,
options_json)` returns Registry. Options accept v and cache_mb (1..64; at most
half LMFILES_MAX_MEMORY_MB). Unknown option keys fail explicitly.

All IDs and revisions in JSON are decimal strings. Item numbers are dense positive 64-bit
values (1..Long.MAX_VALUE) within this local DB; do not mix IDs between DBs. Metadata is caller data.

| Method | JSON contract |
|---|---|
| scan(root, options) | Optional streaming local ingestion; stat/hash reuse, bounded batches and directory shards. [Contract](SCAN.md) |
| item_upsert(identity, fields) | identity `{id:"opaque",aliases:[{kind,namespace,value}]}`; aliases optional, max64. Fields free-form except item/revision/identity/stages/observation/tombstone. Content-hash changes advance revision. Returns `{status,item,revision}` |
| alias_add/remove(item, alias) | `{kind,namespace,value}`; collision/removal by wrong owner returns conflict |
| observe(item, observation) | hash, size, mtime/mtime_ns, present, algorithm, ctime, file_id. Hash/stat changes advance revision; explicit present:false records absence |
| stage_record(item,result) | required stage, namespace, status (ok/failed/gone/skipped), source_revision; optional slot/timestamp/metadata/source_fingerprint. Future revisions fail. Older successes cannot overwrite newer ones |
| stage_forget(item,stage,namespace,slot) | Local projection tombstone event; never deletes a file/object |
| difference_index(a,b,options) | Selectors stage/namespace/slot/status/projection (latest_attempt or last_success); options id, require_matching_source_revision. Same ID with different definition fails |
| stage_difference(a,b,options) | Required index name, optional require_index and require_matching_source_revision. Exact selectors must match the registered index. Returns a cursor over pending items |
| query_subset(namespace,ids,options) | Explicit array of item IDs or alias objects, max4096 per batch. Optional baseline stage/namespace/slot/projection. One neutral verdict per input; no implicit scan |
| claim(selection,work_namespace,count,lease_ms,owner) | selection `{difference:"registered-id"}`; count1..4096, lease1..86400000ms. Rows contain token/item/source_revision |
| complete(token,result,expected_revision) | Atomic stage+claim release, source-revision compare-and-swap. Expired/replaced token returns fenced-or-expired; changed source returns conflict |
| renew/release(token,...) | Cannot revive an expired/replaced token. Script chooses when/why to retry |
| snapshot() | snapshot.query accepts `{ids:[...]}` or `{all:true}` plus options; owns a pinned local read version |
| begin() | WriteBatch.add accepts `{op:"stage_record",args:[item,result_json]}`; commit atomic, rollback cancels intentions |
| doctor(options) | Checks row/event CRCs, item identity, alias refs and frontier consistency. Does no remote verification |
| export_records(path,options) | CREATE_NEW consistent local export. CLI import_records restores into an empty registry for recovery fixtures |
| apply_records(plan,results,options) | Plan-validated journal replay with durable result-ID dedup |

Cursor.next_json(max_rows,max_bytes) returns an array JSON string; rows1..4096,
bytes <=16 MiB and <=a quarter of the configured budget. A too-large row is an
explicit error, never an empty page. rows() uses one reusable Rows buffer;
json_at(index), len(), clear() avoid per-item handles. write_jsonl streams to a
local file. Close cursors/snapshots/registries when done.

Local codec context helpers: log_append(path,line_json,options),
instruction_digest(instruction_json), unconfirmed(plan,results,output). These
perform local file I/O only. CLI --request/--query expose the same local primitive
for diagnostics/tests; test-only --hold-lock is not a background daemon.

Deferred 1C: arbitrary indexed attribute/hash/time/group queries,
full event change-cursor API/checkpoints/archive management, multi-process owner
mailbox and raw file download/provider gaps. Performance and scale measurement
are not planned; correctness is the acceptance bar.
No stub functions claim to implement those capabilities.

Operational limits: individual decoded records <=1MiB and <=a quarter of the
configured memory budget; batches <=16MiB and <=a quarter of budget. Snapshot
import rejects oversized lines, missing footer and checksum failures.

## 0.1.1 compatibility and precision

DB format3 adds compact event frames; format2 introduced alias-family interning and implicit self-success summaries. The
logical query API remains unchanged. Format1/2 DBs receive an automatic backed-up upgrade; legacy snapshot inputs are
left untouched during transactional import: [details](FORMAT_UPGRADE.md).

Claims return owner_generation, clock_era and fixed deadline_wall_ms/deadline_ms
strings. Reopen does not renew; an expired/reclaimed token cannot complete.
Backward wall steps fence older eras: [semantics](LEASES.md).

Pass `size`, `mtime_ns`, `ctime`, `file_id` and other64-bit integer metadata as
**decimal strings**, as for item IDs/revisions. The pinned SDK1.1 JSON parser
uses Double for JSON numeric tokens; values above2^53 can lose precision. The
registry does not infer lost digits. This patch does not change that SDK.
