# Build, gates and releases

mvn package verifies immutable sdk.lock; H2 pinned in pom. GraalVM25 native-image
via scripts/build-native.sh; exclude H2 AWT metadata and supply explicit reflection
config. Linux uses dynamic glibc, other image dependencies static. Native reserved
address space4GiB/maxheap3GiB; address-space/memory tests are in test.sh.

Managed: copy scripts/lmfiles-managed to target/bin/lmfiles. Run test.sh, build
native, then test.sh --native. Each Linux gate runs63 core,17 journal,23 lease,
22 review,111 storage and29 scan assertions (100k files), DSL/CLI scan bindings,
21 subprocess checks/12 fault points, schema/notices, memory and gallery checks.
Native additionally repeats core under a6GiB virtual-address limit.

Local real-product gates: scripts/test-pairings.py (12 HTTP checks), test-sftp.py
(11 Linux checks including killed reput/reget), test-pipeline.py (12 checks).
Require hosts on PATH; Python3 and private system sshd for SFTP. No cloud.

0.1.0 and0.1.1 released; main0.1.2 awaits Lead release. CI is manual dispatch only;
no dispatch, tag or release by the1C-a implementer. Historical five-platform0.1.0
CI: https://github.com/lmctl-ai/lmfiles/actions/runs/40526639317 . New evidence is
Linux only; do not imply new macOS/Windows qualification. Measurements in docs.
