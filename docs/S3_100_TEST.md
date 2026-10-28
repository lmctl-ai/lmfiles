# Bounded real S3 correctness test

The Lead authorized **one** functional test with100 synthetic objects, profile
`lmaws` only, region `us-east-1`, exact bucket `lmaws-lmfiles-<runid>`, tag
`lmaws_test=<runid>`, custom run ID and default$0.05 ledger cap. No admin/default
writes, AWS CLI, other buckets, large uploads, scale or performance measurements.

The full one-job driver is now implemented in
[upload_s3_live.lms](../examples/upload_s3_live.lms). The public autorun requires
explicit `--live` and a unique synthetic run ID, then bootstraps the same job and
its Linux process/cleanup supervisor from this repository:

```sh
AWS_PROFILE=lmaws lmctl https://raw.githubusercontent.com/lmctl-ai/lmfiles/main/examples/upload_s3_live.lms --live UNIQUE_SYNTHETIC_RUN_ID
```

From a checkout, the equivalent test entry is:

```sh
AWS_PROFILE=lmaws python3 scripts/test-s3-live.py --live --run-id UNIQUE_SYNTHETIC_RUN_ID
```

The supervisor auto-installs pinned **lmaws v0.1.23** and released lmfiles, forces
only the lmaws profile/us-east-1/default$0.05 cap, uses one persistent private
ledger directory across restarts, and invokes only this one LMScript for AWS
policy/actions. On failure it stops/reaps the executor tree before invoking the
same script in cleanup mode. A bucket not proved created by this test is never
adopted for deletion. Raw receipts/diagnostics stay under git-ignored
`.lmfiles-local/live-<runid>/`; only sanitized counts/status are published.

The old0.1.22 capability block is resolved. Preflight now requires these actual
0.1.23 bindings: s3_bucket_create_exact, s3_bucket_attach, s3_bucket_delete,
s3_put_file, s3_head_object, s3_list_objects, s3_delete_objects and
s3_ledger_summary. The preflight opens no AWS session; READY means signatures,
not a live success. Every restarted executor explicitly attaches. The zero-change
job never creates a cloud session and compares local ledger summaries before/after.

Full LIST+HEAD/checksum/header verification covers every object after each scripted
phase and at final reconciliation/import. Each soak round does a fresh listing
and HEADs changed objects; unchanged metadata comes from the test's previous HEAD
only when listing ETag/LastModified/size still agree. Final reconciliation always
re-HEADs every object. None of this verification data determines normal upload
plans; the job selects solely from lmfiles and the local tree.

A kill after40 durable SDK receipts tests replay before replan (60 remaining,
100 total PUT attempts). Soak kill occurs before the application receipt is
written, exercising the one uncertain retry. Lost-response injection discards a
successful SDK receipt at the application boundary; it does not claim a real
network outage. Failed-upload injection uses the host's LocalChecksumMismatch
refusal, which is non-ambiguous and performs no PUT. Conditional412 uses different
bytes and independently verifies the old object survived.

`scripts/test-s3-live-wire.py` runs the entire driver against an actual SDK
loopback S3 service, including30 rounds, all fault types, import and cleanup.
It passed with248 PUT attempts and1433 total reservations. These are fixture
counts, not published live results. Nanosecond Java Instant input exposed a
Python3.10 oracle parsing bug; normalization and a regression case fix it.
The authorized live run completed successfully. Results below are live evidence,
not fixture counts; no further live execution is part of this report.

## Completed live phases

| Phase | Live result | Evidence |
|---|---|---|
| First publish | PASS |100 adds; all100 independently listed/HEAD-verified |
| No-change | PASS | Empty plan;0 ledger requests; no cloud session/attach/HEAD/LIST |
| Change/add/delete | PASS | Exactly3 changes +2 adds +2 deletes;100 objects verified |
| Kill/resume | PASS |40 durable receipts replayed;60 replanned;100 PUTs total |
| Uncertain result | PASS | Dropped application response remained unconfirmed;1 item replanned |
| Conditional412 | PASS | Different attempted bytes refused; existing object independently unchanged |
| Seeded soak | PASS |30 rounds,120 operations,3 faults; every round converged |
| Final reconciliation | PASS |99 final keys match files/registry/S3; checksums/headers/times agree |
| Listing import | PASS | Explicit listing/HEAD import; next plan empty with0 requests |
| Teardown | PASS |99 objects deleted, bucket deleted, fresh executor404 NoSuchBucket |

Seed **20261027**. Actual operation totals: create7, update16, touch20, delete16,
rename7, re-create8, no-op46. Faults: kill in round5, application response loss in
round11, local failed-upload refusal in round17. Convergence:30/30. Per-round
counts/status/faults and the separate PUT correlation audit are in
[S3_100_RESULTS.json](S3_100_RESULTS.json). Maximum observed interval skew:
**1.081 seconds**, within300 seconds.

The full final check and the explicit import fetch independently HEADed all99
present objects. No-change intentionally performs no remote verification inside
its zero-request job; its preceding/following mutation phases are independently
verified. During soak, fresh listings plus changed-object HEADs preserve the
bounded request cost; final reconciliation re-HEADs every object.

Persistent ledger summary (API request attempts):

| Operation | Requests |
|---|---:|
| CreateBucket | 1 |
| DeleteBucket | 1 |
| DeleteObjects | 18 |
| GetBucketTagging | 390 |
| HeadObject | 736 |
| ListObjectsV2 | 38 |
| PutBucketTagging | 1 |
| PutObject | 248 |

Total **1,433**; estimated request cost **$0.0018854**, cap **$0.05**. Tiny transient
storage/transfer is not separately metered. No cap raise or IAM/admin operation.
One exact sandbox bucket was created/deleted using only profile lmaws in us-east-1.
No denial or registry/host correctness bug was observed. Atomic DeleteBucket
success plus a fresh-process NoSuchBucket/404 proves no visible or hidden-version
resource remains in that bucket. No request/account IDs or ARNs are public.

The independent PUT audit reconstructs the planned correlation IDs from the
fixture/model and reads the SDK ledger locally:244 distinct planned uploads,
248 PUT attempts =244 +3 documented uncertain retries +1 conditional412. It
checks the distribution per operation ID, not merely a matching total. There were
**0 touch PUTs**, **0 unexpected confirmed reuploads**, and exactly100 PUTs for
the crash generation. Local upload-checksum refusal produced1 error receipt and
no additional PUT transmission. Four negative unit cases reject unexpected IDs,
wrong duplicate distribution, missing checksum evidence and foreign buckets.

Oracle issues were fixed with regressions: Python3.10 Java-Instant nanosecond
parsing, and verification trusting an older manifest rather than rehashing the
actual tree. A post-run fresh-file check against the saved pre-teardown registry/
S3 snapshot verified all99 keys and timestamps with0 mismatches. The remote-import helper was also made independent of local manifest values;
it now validates and imports only the saved S3 checksum/size/ETag metadata. A
remote-only import recheck of the same99-key snapshot again produced an empty
plan with0 AWS requests. No second S3 fetch or bucket was created for these local
proof checks. The SDK and registry product behavior did not need a patch. Live driver commit:2cfa9b8;
subsequent proof/checker changes are recorded in Git. No second bucket or live
rerun was needed for those local rechecks.

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

| Check | Required comparison | Completed live result |
|---|---|---|
| Key sets | Actual local tree = confirmed registry presence = S3 keys; confirmed deletes leave no believed presence | PASS |
| Size | Local byte length = recorded size = S3 size | PASS |
| SHA256 | Local digest = recorded digest = S3 full-object ChecksumSHA256, or explicit `sha256` object metadata | PASS |
| ETag | Single-part ETag = local MD5 = recorded ETag | PASS |
| Version/headers | Remote version matches receipt; Content-Type and Cache-Control match script policy | PASS |
| Time interval | S3 LastModified within recorded request start..confirmation, allowing at most300s skew | PASS |
| Time ordering | Server times nondecreasing across serial rounds and per-item successful revisions; whole-second ties allowed | PASS |
| Independent model | Actual local key set/bytes match the in-memory expected tree | PASS |

Additional SHA256 checksums are canonical Base64 full-object32-byte SHA256; do not
compare a multipart/composite checksum to a local full-object hash. If both an
additional checksum and metadata SHA256 exist, require them to agree. LIST does
not return user metadata or headers: obtain them via the test's explicit HEAD
with ChecksumMode or GetObjectAttributes path. Release0.1.23 supplies those bindings; receipts and headers are checked by the
driver. Never substitute ETag for SHA256.

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
counts/pass-fail and observed max skew (1.081 seconds in the completed run).

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
ETag/version/headers, unconfirmed beliefs, timestamps and ordering. The local oracle/model tests are distinct from the completed live evidence above.

The preflight still publishes only NOT RUN summaries and never executes AWS.
The live results above come from the separately authorized full driver, followed
by independent teardown and local correlation/fresh-file proof checks.


## Live soak by round

Counts below are local-tree operations, not SDK requests. PUT attempts include
only that round's uploads/retries; DELETE and ownership/verification requests are
in the cumulative ledger table. Every round converged before the next mutation.

| Round | Create | Update | Touch | Delete | Rename | Recreate | No-op | PUT attempts | Fault | Converged |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| 1 | 0 | 1 | 0 | 2 | 0 | 0 | 1 | 1 | none | yes |
| 2 | 0 | 1 | 2 | 0 | 0 | 0 | 1 | 1 | none | yes |
| 3 | 0 | 0 | 1 | 0 | 2 | 0 | 1 | 2 | none | yes |
| 4 | 1 | 0 | 0 | 0 | 0 | 1 | 2 | 2 | none | yes |
| 5 | 0 | 1 | 0 | 1 | 0 | 0 | 2 | 2 | kill | yes |
| 6 | 0 | 0 | 1 | 0 | 1 | 0 | 2 | 1 | none | yes |
| 7 | 1 | 0 | 1 | 0 | 0 | 0 | 2 | 1 | none | yes |
| 8 | 0 | 0 | 0 | 0 | 0 | 0 | 4 | 0 | none | yes |
| 9 | 0 | 0 | 1 | 0 | 0 | 0 | 3 | 0 | none | yes |
| 10 | 0 | 0 | 0 | 0 | 0 | 0 | 4 | 0 | none | yes |
| 11 | 0 | 2 | 2 | 0 | 0 | 0 | 0 | 3 | lost | yes |
| 12 | 0 | 0 | 0 | 0 | 0 | 0 | 4 | 0 | none | yes |
| 13 | 0 | 1 | 2 | 0 | 0 | 0 | 1 | 1 | none | yes |
| 14 | 0 | 0 | 0 | 3 | 0 | 0 | 1 | 0 | none | yes |
| 15 | 0 | 0 | 0 | 1 | 1 | 1 | 1 | 2 | none | yes |
| 16 | 0 | 0 | 1 | 1 | 1 | 1 | 0 | 2 | none | yes |
| 17 | 2 | 1 | 0 | 0 | 0 | 1 | 0 | 4 | failed | yes |
| 18 | 0 | 1 | 0 | 1 | 0 | 0 | 2 | 1 | none | yes |
| 19 | 0 | 1 | 1 | 0 | 0 | 1 | 1 | 2 | none | yes |
| 20 | 0 | 0 | 2 | 0 | 0 | 0 | 2 | 0 | none | yes |
| 21 | 0 | 1 | 1 | 0 | 0 | 0 | 2 | 1 | none | yes |
| 22 | 0 | 0 | 0 | 1 | 0 | 0 | 3 | 0 | none | yes |
| 23 | 0 | 1 | 0 | 0 | 0 | 1 | 2 | 2 | none | yes |
| 24 | 0 | 0 | 0 | 2 | 0 | 0 | 2 | 0 | none | yes |
| 25 | 1 | 1 | 1 | 0 | 1 | 0 | 0 | 3 | none | yes |
| 26 | 0 | 0 | 1 | 1 | 1 | 0 | 1 | 1 | none | yes |
| 27 | 1 | 1 | 1 | 1 | 0 | 0 | 0 | 2 | none | yes |
| 28 | 0 | 0 | 1 | 1 | 0 | 1 | 1 | 1 | none | yes |
| 29 | 1 | 1 | 1 | 0 | 0 | 0 | 1 | 2 | none | yes |
| 30 | 0 | 2 | 0 | 1 | 0 | 1 | 0 | 3 | none | yes |
