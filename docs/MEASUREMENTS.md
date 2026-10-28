# Historical local measurements

Measured 2026-10-27, Linux x64/glibc2.35, GraalVM Community25.0.2+10.1.
Code: `45d4324578903b15d19f28b330d06859d22d05da`; subsequent Windows text/path
fixes do not change store behavior. Machine-readable [evidence](measurements.json).
These are single local observations retained for regression context. They are not
scale, throughput, latency or large-upload qualification.

| Measurement | Observed |
|---|---:|
| Synthetic items | 10,000 |
| Explicit aliases/item | 3, plus external identity lookup |
| Stage summaries/item | 5 |
| Events | 60,100 (six/item plus100 stage-forget updates) |
| Final physical store file | 15,073,280 B |
| Physical bytes/item, including retained pages | **1,507.328 B** |
| Pending frontier | 100 items |
| Frontier entries examined | **100**, not all10,000 stage rows |
| Linux native executable | 28,510,472 B (~27.2MiB) |
| Dynamic NEEDED entries | libc.so.6 only |

Native SHA256:
`248badcb03eb194f4c200e2490d4fd4c0fb035aaaf273e4ecf77f7cefcff0e75`.
Different timestamp/compression/page retention histories can change bytes/item; no
compressed-store bound is promised.

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

The measured implementation uses dense long item keys, interned stage IDs,
co-located CRC32C-protected compressed JSON byte rows and normal MVStore pages.
It retains explicit audit metadata and timestamps rather than a custom packed
raw-digest/event codec. This is a measured small-tier implementation, not a
reason to silently drop history or claim the theoretical target.

Optional scans are now implemented. General indexed predicates/change cursors/
event archives and multi-process coordination remain possible future API work. Existing transport-host gaps remain
separate; no real AWS/SFTP test is performed or authorized here.

## Five-platform qualification

[Manual CI](https://github.com/lmctl-ai/lmfiles/actions/runs/40526639317) passed managed and native gates on all five targets
for code commit `a8ebebb7fa26f732ffc669e3191dc5b988257d2d`. Five tested artifacts
were uploaded; no tag or release was created. Linux reports18 process checks;
macOS/Windows report17 because the optional Linux strace check is unavailable.
The55 semantic and13 journal assertions pass on every target. Gallery and public
URL execution evidence is the separate local Linux campaign above.


## 0.1.1 reviewed patch: comparable storage experiment

[Machine-readable result](measurements-0.1.1.json). Both runs use Java21,
-Xmx256m, identical H2/SDK pins and the unchanged 10,000-item benchmark generator.
The baseline is reviewed format1 (`f08f454`); the patch is format2. These are
fresh **uncompacted physical files**, including normal MVStore retained pages.

| Profile | Bytes | Bytes/item |
|---|---:|---:|
| Format1 before | 15,085,568 | 1,508.5568 |
| Format2 after | 14,733,312 | 1,473.3312 |
| Reduction | 352,256 | 35.2256 (2.335%) |

Both have10,000 items,60,100 events and100 pending items; the frontier examines
exactly100 entries. No audit records or metadata were dropped. Alias kind/namespace
interning and self-success elision provide a modest physical reduction because
existing compression already deduplicated much of the repeated data. These are
historical storage observations, not a scale or performance qualification.

Format2 migration/backup/rollback behavior is in [FORMAT_UPGRADE.md](FORMAT_UPGRADE.md),
and clock/fencing behavior in [LEASES.md](LEASES.md). The patch's Linux gates add
23 deterministic fake-clock assertions,22 format-upgrade assertions,22 review
regressions and21 subprocess checks (12 fault points), alongside63 core and17
journal assertions. The previously recorded five-platform CI is for0.1.0; no CI,
tag or release is dispatched by this patch. No AWS/admin command was run.


## 1C-a: real local pairings, scan and compact events

[Machine-readable evidence](measurements-1c-a.json). Both comparable storage runs
use Java21, -Xmx256m, identical SDK/H2 pins and the unchanged 10,000-item profile.
Files are fresh/uncompacted; no events, aliases or user metadata are removed.

| Physical DB | Bytes | Bytes/item |
|---|---:|---:|
| Released format2 codec before | 14,802,944 | 1,480.2944 |
| Format3 compact events after | 8,044,544 | 804.4544 |
| Saved | 6,758,400 | 675.84 (45.6558%) |

The profile has10,000 items,60,100 events,40,000 aliases,five stages/item and100
pending items. The frontier examines100 records. Compact binary event headers and
canonical metadata are retained as historical storage evidence; no scale or
throughput claim is made from this fixture.

Prior functional pairings passed against released lmcurl0.1.2 and lmssh0.1.1:
12 HTTP,11 SFTP and12 pipeline checks, all local fixtures with synthetic bytes.
These are functional results, not performance claims. No real cloud or real remote
was used in1C-a. Historical measurement JSON remains unchanged; no new measurement
is planned.

Format1/2 upgrades retain backups; an additional native upgrade of the real
released-code10k format2 DB preserved item/event counts, the prior reader reads
the backup, and refuses format3. See FORMAT_UPGRADE.md. Core remains network-free;
all transport tests use loopback and synthetic data. No AWS/admin command, real
remote, workflow dispatch, tag or release was run for1C-a.

## Current acceptance rule

Performance and scale measurement are not planned; correctness is the bar. No100M
qualification or large-upload campaign is a goal. The separately authorized
100-object live upload correctness test passed with released lmaws0.1.23:
[phases, counts and zero-leftover proof](S3_100_TEST.md). Existing10k
figures above and the historical JSON are informational only.
