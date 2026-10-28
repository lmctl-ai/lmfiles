# Examples

Ten self-executing .lms jobs create synthetic artifacts and images. frontier,
revisions, claims, snapshots and journal_resume reset only their private synthetic
fixture DBs. publish_ttl keeps state: repeat run is no-op; changed selects one
revision; uncertain deliberately omits a result, so the next run retries it.

The README raw URLs install lmfiles from the latest release automatically (install="github:lmctl-ai/lmfiles@latest"). lmplot
renders every image. publish_ttl additionally binds existing lmaws in aws.demo()
mode; no profile/credentials/real request. The outer LMScript owns a local fake
bucket and writes confirmed receipts only after its synthetic actions succeed.

examples/render-all.sh regenerates docs/images. Gallery captions use the short
lmctl URL.lms form. Tests run in fresh temp directories, not against user files.

`pipeline_lifecycle.lms` uses real lmcurl against its loopback mock, local cleanup,
and a persistent local fake S3-like executor. No AWS host or credentials are
needed. `scripts/test-pipeline.py` checks all three effect-before-confirmation
interruptions, replay, bytes and a second no-op (12 checks); `--gallery` renders
its README picture. Explicit hosts install from `github:lmctl-ai/<name>@latest`.

`download_lmcurl.lms` uses released lmcurl 0.1.2 Request.allow_error plus ETag and
Last-Modified headers against loopback only. It reads a URL list, queries registry
stage metadata, skips recorded gone URLs, verifies unchanged candidates locally,
and records outcomes/failure counts. `scripts/test-pairings.py`:12 checks.

`sftp_registry.lms` uses released lmssh 0.1.1 and a private temporary sshd (Python3,
ssh-keygen and installed sshd required). Strict known_hosts, reput/reget -p,
temporary names, rename, and query-driven skips; no remote listing/checksum calls.
`scripts/test-sftp.py`:11 Linux checks including actual upload/download job interruption
and resume, digest validation, local corruption and host-key refusal. A new remote
partial must first be created: only reput's explicit missing-remote error selects
initial put; other failures remain failures. All fixtures are temporary/synthetic.

`upload_s3_live.lms` is the completed one-job live S3 pairing. Public autorun needs
--live and a unique synthetic ID; bootstrap invokes Linux scripts/test-s3-live.py
for process-tree control and mandatory cleanup. Only lmaws/profile,us-east-1,
pinnedhost0.1.23 and one matching-tag bucket; persistentcap$0.05. The supervisor
runs local PUT audit after cleanup. `render-s3-results.lms` renders the sanitized
actual operation chart. See docs/S3_100_TEST.md; no default/CI live execution.
