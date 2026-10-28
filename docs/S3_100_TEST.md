# Bounded real S3 correctness test: blocked before AWS

The Lead authorized **one** functional test with100 synthetic objects, profile
`lmaws` only, region `us-east-1`, exact bucket `lmaws-lmfiles-<runid>`, tag
`lmaws_test=<runid>`, custom run ID and default$0.05 ledger cap. No admin/default
writes, AWS CLI, other buckets, large uploads, scale or performance measurements.

The test is **not implemented or executed end to end** because released lmaws
v0.1.22 lacks necessary bindings. The self-executing capability preflight is:

```sh
AWS_PROFILE=lmaws lmctl https://raw.githubusercontent.com/lmctl-ai/lmfiles/main/examples/upload_s3_100_preflight.lms syntheticrun01
```

It auto-installs the pinned release when needed, requires version0.1.22, calls
only `--version`/`--dsl-schema`, writes a private sanitized preflight report under
`.lmfiles-local/s3-100/`, prints BLOCKED and exits2. Its lmcode block is never
executed. No AWS session, identity lookup, SDK operation or bucket creation occurs. No cost
ledger is opened; the zero-request count follows from version/schema-only
execution, not a live request ledger or remote inventory.
An older binary on PATH is refused rather than silently used. No credentials are
read by this preflight. `scripts/test-s3-preflight.py` checks the expected blocked
exit, both profile rejections and sanitized output; it does not run AWS.

## Missing release capabilities

| Required behavior | Released0.1.22 limitation |
|---|---|
| Exact named/tagged bucket, recoverable after executor kill | Run.bucket constructs `lmaws-<runid>-<suffix>`; process-owned Run state cannot reattach the persisted bucket; closing the Run cleans it |
| File PUT with integrity, Content-Type and Cache-Control | Run.put(Row,key,string) returns void and exposes no header/checksum/structured receipt options |
| If-None-Match and distinguish412 from ambiguous failure | No conditional generic PUT binding |
| Independently list every object and compare metadata | No generic object-list or HEAD-object binding; account bucket inventory is not an object inventory |
| Selectively delete2 objects and record per-key outcomes | No generic delete-object(s) binding; whole-run cleanup is not selective deletion |
| Persistent request/cost evidence across executor recovery | Needs an explicit recovered operation/receipt ledger contract rather than process-local Run tracking |

The existing design requests `s3_put_file`, `s3_head_object` and
`s3_delete_objects`; exact-name bucket create/delete and bounded object-list
bindings are also needed. These are proposed small additions, not APIs claimed to
exist. Implement them with generic run ownership/ledger guards and fake tests in
lmaws, then publish an approved usable host before enabling the live driver.
Do not weaken sandbox ownership checks or bypass lmaws via another SDK/CLI.

`s3_upload_check` is unsuitable: its source explicitly uses default for PUT,
controls a different fixed smoke test and does not implement these phases. It was
not invoked. No IAM denial occurred, so there is no denied action/resource/
condition to report. No profile or policy was changed.

## Phase disposition

| Phase | Status | Required assertion when unblocked |
|---|---|---|
| First publish | NOT RUN—release API gap |100 adds, confirmed receipts, independent verification |
| No-change | NOT RUN—release API gap | Empty plan; zero AWS requests, including ownership/credential probes |
| Change/add/delete | NOT RUN—release API gap |3 changed uploads +2 adds +2 deletes |
| Kill/resume | NOT RUN—release API gap | Only unconfirmed work replanned;100 PUTs plus at most1 in-flight retry |
| Uncertain/412 | NOT RUN—release API gap | Ambiguous result unconfirmed;412 never overwrites existing content |
| Seeded soak | NOT RUN—release API gap |30 rounds, seed20261027, all7 operation types, fault/recovery checks |
| Three-way reconciliation | NOT RUN—release API gap | S3 listing/metadata, registry belief and actual local files agree |
| Listing-import rebuild | NOT RUN—release API gap | Lose DB; explicitly import verified remote state; next plan empty |
| Independent verification | NOT RUN—release API gap | Exact key set, sizes, hashes/ETags, Content-Type and Cache-Control |
| Teardown/absence | NOT RUN—nothing created | Delete owned objects/bucket and independently prove absence |

Created resources=0, AWS requests=0, estimated cost=$0.00. This is a preflight
safety result, **not an independent remote absence proof** and not a successful
live upload test. Sanitized evidence: [S3_100_RESULTS.json](S3_100_RESULTS.json).
The roughly$0.002 intended live estimate remains unmeasured. No test upload, tag,
release or workflow dispatch is performed by this capability check.

Correctness is the bar. Existing10k storage figures remain historical information;
performance and scale measurement,100M qualification and large uploads are not
planned. The future driver remains one LMScript per job; lmfiles only records
confirmed state and never performs AWS operations.


## Seeded correctness soak (same job and bucket)

After the five scripted mutation/fault phases, the same LMScript job runs30 rounds
with fixed printed seed **20261027**, four random operations per round, over only
`synthetic-000.txt` through `synthetic-099.txt`. The expected tree is a separate
in-memory byte model, never built from lmfiles or S3. Initialize it from the
script's own desired fixture after the scripted phases, not the observed remote
result. The helper accepts that initial byte map. It is also compared to actual
local file bytes before checking publication. A specified32-bit LCG
`state=(1664525*state+1013904223) mod2^32` makes operation selection reproducible
across runtimes. Sorted candidate sets make key selection stable. Each round uses disjoint
affected keys, so later mutations cannot cancel a rename or turn its delete/add
into an overwrite before the normal job sees the plan. No timing,
throughput or scale measurement is performed.

Operations: create, overwrite with new bytes, touch-only, delete, rename
(delete+add), re-create a previously deleted key, and no-op. A missing eligible
source/target becomes an explicit no-op, never an out-of-universe key. Touch-only
updates local mtime but preserves content hash: the job must plan zero PUTs for
it; a stat observation/event is permitted. Rename to a free universe key retains
bytes and requires confirmed old-key deletion plus new-key publication. Each
round uses the ordinary scan/query -> plan -> lmaws executor -> confirmed record
path. It must not manipulate projections to manufacture a passing comparison.

The job schedules kill, lost-response and failed-upload faults from rounds5,11
and17 respectively, deferring to the next round that actually has an upload.
This seed selects rounds5,11,17. Before the next round's new mutations, replay
confirmed receipts and converge the interrupted desired tree. An ambiguous
response remains unconfirmed; a failed request never becomes a success. Report
attempt counts, durable confirmations, faults injected and convergence separately.
During kill/fault phases allow only one in-flight PUT, durably recording its
confirmation before dispatching the next; this bounds the ambiguous retry.

The pure model currently derives120 operations: create7, update16, touch20,
delete16, rename7, re-create8, no-op46. These are **planned model counts, not live
operations**. The final expected model has99 present keys, within the100-key
universe; do not force deleted keys back into existence to reach100. Its
conservative object-PUT admission bound is253, including the
scripted phases and10 reserved attempts for conditional/fault/retry cases. The
job additionally refuses beyond400 object-PUT attempts and enforces the existing
$0.05 cost ledger cap. If recovery would exceed either cap, stop and report;
never silently retry beyond the budget. The intended whole live run estimate is
about$0.002; current actual preflight cost remains$0.00.

## Final three-way reconciliation

The TEST collects an independently paginated S3 listing and per-object metadata
through lmaws, separately from the normal job. LIST is permitted only for this
explicit verification and the explicit DB-loss import. Empty plans must not even
open an AWS session. Small single-part PUTs must use compatible SSE-S3 settings;
ETag/MD5 equivalence is not claimed for multipart/SSE-KMS objects. If the host
cannot supply the required compatible PUT/checksum metadata, stop as a capability
limitation rather than weakening the assertion.

| Check | Required comparison | Current live result |
|---|---|---|
| Key sets | Actual local tree = confirmed registry presence = S3 keys; confirmed deletes leave no believed presence | NOT RUN |
| Size | Local byte length = recorded size = S3 size | NOT RUN |
| SHA256 | Local digest = recorded digest = S3 full-object ChecksumSHA256, or explicit `sha256` object metadata | NOT RUN |
| ETag | Single-part ETag = local MD5 = recorded ETag | NOT RUN |
| Version/headers | Remote version matches receipt; Content-Type and Cache-Control match script policy | NOT RUN |
| Time interval | S3 LastModified within recorded request start..confirmation, allowing at most300s skew | NOT RUN |
| Time ordering | Server times nondecreasing across serial rounds and per-item successful revisions; whole-second ties allowed | NOT RUN |
| Independent model | Actual local key set/bytes match the in-memory expected tree | NOT RUN |

Additional SHA256 checksums are canonical Base64 full-object32-byte SHA256; do not
compare a multipart/composite checksum to a local full-object hash. If both an
additional checksum and metadata SHA256 exist, require them to agree. LIST does
not return user metadata or headers: obtain them via the test's explicit HEAD
with ChecksumMode or GetObjectAttributes path. The release lacks even generic
HEAD/list bindings, so these extra receipt fields must be supplied/tested when
that host API is added. Never substitute ETag for SHA256.

Record request-start immediately before dispatch and confirmation time only after
a successful response plus durable receipt. Compute observed skew as the largest
distance of LastModified outside that client interval; print max absolute seconds.
If within the interval it contributes0. Allow at most300 seconds, and retain both
original timestamps. This tolerance does not excuse inverted client intervals or
reversed server ordering. S3 timestamps may tie within one second: do not sleep
or add timed performance trials. Revisions are scoped to one item, not comparable
between different keys; global round IDs establish cross-key serial ordering.
Retain verified previous successful upload metadata to test ordering over time,
not just the final object snapshot. Private mismatch diagnostics contain synthetic
key, disagreeing view/field and originating round from the independent model
(including deletion origins; pass its origin map to the checker), or explicit
unknown for an unowned/extra key with no source event. Public results retain only
counts/pass-fail and observed max skew (currently null, not an invented zero).

## Explicit listing-import recovery

After reconciliation, lose/remove only the test's registry and rebuild its
believed state using explicit lmaws listing/HEAD results. The script imports
verified size, full SHA256, ETag/version and remote LastModified using existing
item/alias/observe/stage_record/batch primitives; `import_records` snapshot restore
is not an S3-listing parser. Record import provenance and the verification interval,
then confirm the next normal plan is empty with zero AWS requests. Import is an
explicit costed verify action; it is not normal automatic remote discovery.

A listing cannot reconstruct the original client's request-start/confirmation
times or old revision history. Do not fabricate them: imported rows mark original
client times unknown and retain remote LastModified plus import observation
bounds. The upload timestamp-order assertions above run **before** DB loss.
Post-import checks cover reconstructed present state/checksums and empty-plan
behavior, not invented historical upload intervals. No new core policy or remote
side effect belongs in lmfiles.

Teardown must check bucket versioning without enabling it. If versions unexpectedly
exist, cleanup needs exact owned-bucket ListObjectVersions/DeleteObjectVersion
support, including delete markers, before independently proving bucket absence.
No unrelated bucket may be touched. These capabilities are additional live-host
prerequisites; they are not present or exercised by the preflight.

## Executable local oracle and honest status

`scripts/s3_correctness_oracle.py` implements the independent seeded model and pure
three-view checker. It performs no AWS call, registry query or transfer. The
normal LMScript driver must supply independently acquired views; this helper is
not an S3 executor or an implemented live driver. `scripts/test-s3-oracle.py`
checks reproducibility, all operation types, touch/rename/recreate rules, request
admission, missing/extra keys, stale deletion beliefs, hashes, metadata fallback,
ETag/version/headers, unconfirmed beliefs, timestamps and ordering. Nine local
unit tests pass; they do not imply any live round converged.

The extended preflight publishes30 NOT RUN round summaries,10 NOT RUN phases and
eight NOT RUN reconciliation checks. Actual operations/faults=0, converged=null,
observed max skew=null, rebuilt=false. Missing released APIs still block the same
live test. No additional bucket, AWS session or live request was introduced.
