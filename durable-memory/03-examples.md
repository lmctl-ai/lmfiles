# Examples

Six self-executing .lms jobs create synthetic artifacts and images. frontier,
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
