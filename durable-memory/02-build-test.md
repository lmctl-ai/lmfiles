# Build, gates and releases

mvn package verifies immutable sdk.lock; H2 pinned in pom. GraalVM25 native-image
via scripts/build-native.sh; exclude H2 AWT metadata and supply explicit reflection
config. Linux uses dynamic glibc, other image dependencies static. Native reserved
address space4GiB/maxheap3GiB; address-space/memory tests are in test.sh.

Managed: copy scripts/lmfiles-managed to target/bin/lmfiles. Run test.sh, build
native, then test.sh --native. Each Linux gate runs63 core,17 journal,23 lease,
22 review,111 storage and32 scan correctness assertions (1,000 synthetic files;
no timing/throughput output), DSL/CLI scan bindings,
21 subprocess checks/12 fault points, schema/notices, memory and gallery checks.
Native additionally repeats core under a6GiB virtual-address limit.

Local-fixture checks: scripts/test-pairings.py, test-sftp.py and test-pipeline.py.
They require hosts on PATH; Python3 and a private system sshd for SFTP. No cloud
or real remote is used, and these checks are not performance qualifications.

0.1.0,0.1.1 and0.1.2 are released. This follow-up adds correctness tests/docs. CI is manual dispatch only;
no dispatch, tag or release by the implementer. Historical five-platform0.1.0
CI: https://github.com/lmctl-ai/lmfiles/actions/runs/40526639317 . New evidence is
Linux only; do not imply new macOS/Windows qualification. Measurements in docs.

S3 capability preflight: scripts/test-s3-preflight.py runs10 local checks against
the pinned installed lmaws0.1.22 schema/version, never executing AWS DSL.
Use that binary on PATH. Exit2/BLOCKED is expected; all10 live phases remain NOT RUN.

The gate runs9 pure s3-correctness-oracle unit tests. They test the model and
comparison logic only, not S3 transport or live convergence. No scale study.

The separately authorized local ramp uses scripts/test-scale.py with fixed40GB
root-free minimum and25GB registry-directory maximum, a .5s parent monitor and
batch guards. All data is synthetic; cleanup is mandatory. Default steps1M/5M/20M
are opt-in, not a default gate. See docs/LOCAL_SCALE_TEST.md.
