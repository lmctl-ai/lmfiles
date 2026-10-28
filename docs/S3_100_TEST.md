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
| Independent verification | NOT RUN—release API gap | Exact key set, sizes, hashes/ETags, Content-Type and Cache-Control |
| Teardown/absence | NOT RUN—nothing created | Delete owned objects/bucket and independently prove absence |

Created resources=0, AWS requests=0, estimated cost=$0.00. This is a preflight
safety result, **not an independent remote absence proof** and not a successful
live upload test. Sanitized evidence: [S3_100_RESULTS.json](S3_100_RESULTS.json).
The roughly$0.001 intended live estimate remains unmeasured. No test upload, tag,
release or workflow dispatch is performed by this capability check.

Correctness is the bar. Existing10k storage figures remain historical information;
performance and scale measurement,100M qualification and large uploads are not
planned. The future driver remains one LMScript per job; lmfiles only records
confirmed state and never performs AWS operations.
