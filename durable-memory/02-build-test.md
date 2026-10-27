# Build and gate

mvn package verifies sdk.lock via scripts/sync-sdk.py; third_party sources are
ignored and immutable-archive checksummed. H2 pinned in pom. No dependency drift.
Managed launcher: scripts/lmfiles-managed copied to target/bin/lmfiles.
Native: GraalVM25, scripts/build-native.sh. H2 AWT metadata excluded, explicit
MetaType/FilePath reflect config, no mapped backend. Linux dynamic glibc only;
CI Ubuntu22.04 baseline. Native reserved address space4GiB, maxheap3GiB.

Run test.sh and test.sh --native. CI is workflow_dispatch only, five platforms,
local/fake tests only. v0.1.0 is released; this0.1.1 patch does not tag/release. Lead owns releases.
SDK standards and host signatures must match compiler schema output. Notices
embed MIT SDK license, H2 MPL2 text and runtime/platform attribution.

All five managed/native jobs passed: https://github.com/lmctl-ai/lmfiles/actions/runs/40526639317
Code:a8ebebb7fa26f732ffc669e3191dc5b988257d2d. No Windows stub is needed.
The five-platform evidence above is for0.1.0. v0.1.1 is qualified locally on
Linux by this task; no new tag/release or CI dispatch is made. Lead releases. Local native final gate also ran all six
gallery jobs and six publication scenarios with real cloud request count0.
