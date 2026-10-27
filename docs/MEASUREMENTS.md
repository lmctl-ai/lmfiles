# Local qualification

Measured 2026-10-27, Linux x64/glibc2.35, GraalVM Community25.0.2+10.1.
Code: `45d4324578903b15d19f28b330d06859d22d05da`; subsequent Windows text/path
fixes do not change store behavior. Machine-readable [evidence](measurements.json).
These are single local observations, not 100M qualification, p95 statistics or an
OS-cache-flushed benchmark. The benchmark ran alongside the native gate.

| Measurement | Observed |
|---|---:|
| Synthetic items | 10,000 |
| Explicit aliases/item | 3, plus external identity lookup |
| Stage summaries/item | 5 |
| Events | 60,100 (six/item plus100 stage-forget updates) |
| Final physical store file | 15,073,280 B |
| Physical bytes/item, including retained pages | **1,507.328 B** |
| Build/update time | 4.090 s |
| Pending frontier | 100 items |
| Frontier entries examined | **100**, not all10,000 stage rows |
| Reopened frontier query | **3.711 ms** |
| 1,000 permuted subset lookups with JSON serialization | **73.083 ms** |
| Benchmark peak RSS (time -v) | 72,684 KiB |
| Linux native executable | 28,510,472 B (~27.2MiB) |
| Dynamic NEEDED entries | libc.so.6 only |

Native SHA256:
`248badcb03eb194f4c200e2490d4fd4c0fb035aaaf273e4ecf77f7cefcff0e75`.
Reproduce in an empty DB path: `lmfiles --benchmark /tmp/new-fixture.mv 10000`.
The fixture generator rejects existing DBs. Different timestamp/compression/page
retention histories can change bytes/item; no compressed-store bound is promised.

## Gate results

Both managed and native gates pass55 independent semantic assertions,13 journal
assertions, and18 process checks (the follow-up lock test also verifies recovery after
explicitly terminating its owner). Multiple opened-store caches also consume the VM budget. The native semantic suite repeats under a6GiB
virtual-address limit. A growing standard-list DSL program fails at1MiB with the
LMFILES_MAX_MEMORY_MB diagnostic; invalid budget configuration also fails.

Nine fault points are exercised: before_commit, mid_commit (actual half-written H2
page), after_commit, disk_full (injected FileStore write failure), before_compaction,
mid_compaction, after_compaction, after_plan_sync and after_result_sync. Recovery
uses the SAME DB and checks acknowledged state, not a separate self-test DB.
Other semantic checks cover rollback after conflict/malformed batch, source drift,
latest failure versus prior success, missing versus unobserved local data,
snapshot stability, fenced leases, export/import, truncation and row corruption.
Journals reject out-of-plan/digest mismatch/conflicting completions and preserve
lossless byte-path tags. The optional Linux strace check observes no remote IP
connect/bind/send operations; OS-local nscd and JVM socket capability probes are
not reported as remote calls. An open-store /proc/maps check finds no DB mapping.

Six gallery jobs pass and their images were visually inspected. Six publication
scenarios in an empty temp directory cover first publish2, no-op0, changed-but-
unconfirmed1, retry1, unchanged0 and return-to-original-content1. All use local
fake objects and explicit aws.demo(), with zero real cloud requests. All six
public README URLs were run through lmctl in separate empty directories using
the locally built unreleased binary on PATH.

## Interpretation and 1C

The design's750–1,050 B/item lean estimate is **not met by this first codec**.
The measured implementation uses dense long item keys, interned stage IDs,
co-located CRC32C-protected compressed JSON byte rows and normal MVStore pages.
It retains explicit audit metadata and timestamps rather than a custom packed
raw-digest/event codec. This is a measured small-tier implementation, not a
reason to silently drop history or claim the theoretical target.

1C must measure compact binary metadata/raw-digest encoding, prefix/delta layouts,
metadata-heavy profiles, compaction amplification and100M local items under fixed
memory. Optional scans, general indexed predicates/change cursors/event archives
and multi-process coordination also remain1C. Existing transport-host gaps remain
separate; no real AWS/SFTP test is performed or authorized here.
