# Optional journal

The script writes an instruction with v:1, kind:instruction, job, op and payload.
Payload binds item, stage, target/namespace, source_revision, optional source_fingerprint,
verb and record_projection (record/forget/none). Core never executes verb.

A result has kind:result, same job/op, unique result_id, completes_instruction,
and payload containing the exact instruction_digest, confirmed and an explicit
projection. Projection identity/stage/namespace/revision/fingerprint must match
the plan. Confirmed means the script reported that fact; demo receipts belong in
synthetic namespaces, not real production belief.

log_append computes CRC32C over canonical sorted JSON excluding checksum, appends
one LF-terminated line and fsyncs before acknowledging. instruction_digest is
SHA-256 of canonical payload. Limit64 KiB including LF; a torn final line is
unconfirmed, middle corruption is an error. Unicode JSON strings are escaped;
byte paths use `{utf8:"valid Unicode"}` or canonical `{base64:"/w=="}` for invalid
UTF-8 bytes. NUL and malformed/redundant tags fail; no lossy replacement.

apply_records requires the plan, validates all complete result lines, and rejects
out-of-plan/digest mismatch, conflicting IDs and incompatible completing results.
Each projection and durable replay marker commits atomically. Identical replay
is harmless. Unconfirmed produces an atomically replaced remainder file.

No receipt means no recorded publication, even if a local fake/real host completed
its action. Retry/reconcile/idempotency is the script's decision. Claims fence local
completion, not remote effects. --journal-test and the publication regression run
exercise plan-only, durable-result replay, duplicate replay, torn tail, corruption,
wrong digest, out-of-plan and oversized-path/error cases.
