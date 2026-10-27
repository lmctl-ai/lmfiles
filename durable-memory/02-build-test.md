# Build and gate

mvn package verifies sdk.lock via scripts/sync-sdk.py; third_party sources are
ignored and immutable-archive checksummed. H2 pinned in pom. No dependency drift.
Managed launcher: scripts/lmfiles-managed copied to target/bin/lmfiles.
Native: GraalVM25, scripts/build-native.sh. H2 AWT metadata excluded, explicit
MetaType/FilePath reflect config, no mapped backend. Linux dynamic glibc only;
CI Ubuntu22.04 baseline. Native reserved address space4GiB, maxheap3GiB.

Run test.sh and test.sh --native. CI is workflow_dispatch only, five platforms,
local/fake tests only. No tag/release was authorized; Lead owns release.
SDK standards and host signatures must match compiler schema output. Notices
embed MIT SDK license, H2 MPL2 text and runtime/platform attribution.
