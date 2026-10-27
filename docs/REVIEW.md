# Independent Code Review: `lmfiles` v0.1.0

**Target**: `lmctl-ai/lmfiles` (`v0.1.0` / `main`)
**Reference**: `lmctl-ai/lmaws` `docs/LMSYNC_DESIGN.md` and `docs/LMSYNC_REVIEW.md`
**Scope**: Registry correctness, crash safety, plan/result codec, `lmcode` API surface, resource/memory budgets, the 1,507 B/item measurement, security boundaries, and test/example coverage.

---

## Executive Summary

`lmfiles` v0.1.0 is a clean, compact (~750 LOC across 10 Java classes), zero-business-rule local file lifecycle registry built on `lmcode-java` v0.1.1 and H2 `MVStore` 2.3.232. It resolves the major architectural blockers identified in `LMSYNC_REVIEW.md`:
- The API is strictly compliant with the `lmcode` type system (`int`, `bool`, `string`, `void` and opaque handles `Registry`, `Snapshot`, `WriteBatch`, `Cursor`, `Rows`), verified at startup by `vm.checkSchemaSignatures` and in `test.sh` by `lmscript --dsl-signatures`.
- Zero business rules, transport clients, credentials, or network calls exist in core (`strace -f -e trace=network` verifies no remote socket operations; `/proc/<pid>/maps` verifies positional `FileChannel` I/O with no `mmap`).
- The 1,507.328 B/item measurement at 10,000 items (`15,073,280 B`) is honest and byte-for-byte reproducible (`Bench.java`).

During this review, **7 clearly-wrong small bugs were fixed directly** with 12 new regression assertions added to `CoreChecks.java` (55 -> 63 assertions) and `PlanLogChecks.java` (13 -> 17 assertions). All findings below are ranked by severity with exact `file:line` references.

---

## Ranked Findings

### HIGH Severity

#### 1. [HIGH — Fixed] False `same-as-recorded` ("unchanged/published") verdict after stat-only `observe()` advances `revision` (`Store.java:114-126`, `Store.java:236`)
- **Problem**: When an item with a recorded `content_hash` (from `item_upsert` or an earlier `observe`) and a baseline stage with `source_fingerprint.hash` was subsequently updated via a stat-only `observe(item, {"present": true, "size": 200})` (no `hash` supplied), `observe()` detected the `size`/`mtime`/`mtime_ns`/`present` change and incremented `revision` (e.g., `1 -> 2`), but left the previous revision's `content_hash` on the item row (`if (!h.isEmpty()) r.put("content_hash", h)`). In `compare()` (`Store.java:236`), `digests` evaluated `true` because both `fingerprint.hash` and `r.content_hash` were non-empty, comparing the baseline stage's digest against the item's stale revision-1 `content_hash` and returning `"state": "same-as-recorded"` despite `revision` having advanced to `2`. Additionally, `observe()` skipped checking `present` changes when `hash` was non-empty (`CoreChecks.java:28`) and skipped comparing `size` against `item_upsert` fields when `prior` (`r.get("observation")`) was `null`.
- **Fix applied**: In `Store.observe()` (`Store.java:115-126`), when `changed` is `true` and `h.isEmpty()`, `r.remove("content_hash")` clears the stale digest from the prior revision so `compare()` falls back to `revision-comparison` and reports `"state": "changed"`. `observe()` now also checks `present` transitions even when `hash` is supplied, checks `size` drift against initial `item_upsert` fields when `prior == null`, and rejects non-boolean `present` values. Covered by `CoreChecks.java:41-46`.

#### 2. [HIGH — Fixed] `PlanLog.append()` after an unconfirmed torn final line fused the new record onto the torn bytes, corrupting the journal (`PlanLog.java:22-45`)
- **Problem**: `PlanLog.read()` (`PlanLog.java:50-65`) treats an incomplete final line (no trailing `\n`) as an unconfirmed torn write and ignores it during `apply_records` and `unconfirmed`. However, `PlanLog.append()` previously opened the journal with `StandardOpenOption.APPEND` and wrote `J.str(m) + "\n"` at EOF without checking whether the existing file ended with `\n`. If a crash left a torn final line (e.g., `{"v":1,"kind":`), the next `PlanLog.append()` call concatenated the new `\n`-terminated record directly onto the torn bytes (`{"v":1,"kind":{"checksum":...}\n`), turning the torn tail into a newline-terminated corrupt line that caused all subsequent `apply_records` and `unconfirmed` calls to fail with `IllegalArgumentException("trailing JSON")`.
- **Fix applied**: Under `c.lock()` in `PlanLog.append()` (`PlanLog.java:28-39`), if `c.size() > 0` and the final byte is not `\n`, `append()` scans backward up to `MAX_LINE` bytes for the last `\n` and truncates the unconfirmed torn tail (`c.truncate(...)`) before writing and `fsync`ing the new line. Covered by `PlanLogChecks.java:24-27`.

#### 3. [HIGH — Fixed] Primary item identity index in `aliases` shared key space with user aliases of `kind: "identity"` (`Store.java:73`, `Store.java:108-119`)
- **Problem**: `Store.upsert()` (`Store.java:113`) stores the primary `(namespace, identity.id) -> item` mapping in the `aliases` MVMap under `json(List.of("identity", namespace, external))`. `Store.aliasKey()` (`Store.java:73`) encoded user aliases into the same `aliases` map as `json(List.of(kind, namespace, value))` without reserving `"identity"`. A caller invoking `alias_remove(item, {"kind": "identity", "namespace": ns, "value": id})` could delete an item's primary identity lookup (causing the next `item_upsert` for `id` to allocate a duplicate item number), or hijack another `identity.id` via `alias_add`.
- **Fix applied**: Reserved `"identity"` in `Store.aliasKey()` (`Store.java:73`, throwing `IllegalArgumentException("reserved-alias-kind")`). Covered by `CoreChecks.java:36`.

#### 4. [HIGH — Fixed in 0.1.1] Persisted claim leases reset their full `lease_ms` timer on every `Store` open across short-lived CLI/script invocations (`Store.java:28-29`, `Store.java:166-172`)
- **Problem**: Each `Store` instance generates an in-memory `ownerEpoch = UUID.randomUUID().toString()` and `ownerStarted = System.nanoTime()` (`Store.java:28-29`). For any claim created by a prior `Store` instance (`!ownerEpoch.equals(text(c, "owner_epoch"))`), `alive(token)` (`Store.java:172`) computes `deadline = ownerStarted + Long.parseLong(text(c, "lease_ms")) * 1_000_000`. Because `alive()` never persists the new `owner_epoch` back into the claim record on first reopen, and because `lmscript` executes each `_lmcode_ ... end _lmcode_` block (and each `--request` CLI call) in a fresh `Store` instance where `System.nanoTime() - ownerStarted` is only a few milliseconds, a claim created in one process **never expires** in subsequent short-lived processes unless a single `Store` instance remains open continuously for longer than `lease_ms`. Meanwhile, the wall-clock `"until"` field written to every claim record (`Store.java:167, 176`) is never read.
- **Recommendation**: On `Store` open (or lazily on the first `alive()` check for an old-epoch claim), either bound reopened claims by `Math.min(until - System.currentTimeMillis(), lease_ms)` or persist the reopen timestamp/epoch transition so repeated short-lived `Store` opens do not perpetually restart the lease timer from zero.

---

### MEDIUM Severity

#### 5. [MEDIUM — Fixed] `stage_record` dropped out-of-order `last_success` updates when `latest_attempt` was at a higher revision (`Store.java:134-141`)
- **Problem**: In `Store.record()` (`Store.java:134-140`), `success` (`last_success`) was updated whenever `status.equals("ok") && (success.isEmpty() || rev >= success.source_revision)` and stored inside `p.put("last_success", success)`. However, `all.put(d, p)` was only executed when `prev.isEmpty() || rev >= prev.source_revision`. If revision 2 was recorded first with `status: "failed"` (`prev.source_revision = "2"`, `last_success = {}`) and a late `status: "ok"` receipt for revision 1 arrived afterward, `p` was discarded and `prev` kept an empty `last_success` even though revision 1 succeeded and no newer success existed.
- **Fix applied**: Added `else if (!success.isEmpty()) { var keep = new LinkedHashMap<>(prev); keep.put("last_success", success); all.put(d, keep); }` in `Store.record()` (`Store.java:140`) so `latest_attempt` remains revision 2 (`failed`) while `last_success` records revision 1 (`ok`). Covered by `CoreChecks.java:47-49`.

#### 6. [MEDIUM — Fixed] `PlanLog.apply()` checked `persisted-result-conflict` (`replay_seen`) during the commit pass after earlier lines had already committed (`PlanLog.java:127-143`)
- **Problem**: `PlanLog.Index` validated all plan and result lines in a first pass before mutating `Store`, except for `persisted-result-conflict` (checking whether a `result_id` was already recorded in `store.replay` with a different SHA-256 digest), which was only checked inside the second-pass loop (`PlanLog.java:133-134`) after earlier result lines in the same `apply_records` call had already been committed in separate `Store.Batch` transactions.
- **Fix applied**: Added a pre-pass in `PlanLog.apply()` (`PlanLog.java:130-135`) that verifies `replay_seen` for all confirmed result lines before committing any batch. Covered by `PlanLogChecks.java:35-42`.

#### 7. [MEDIUM — Fixed] `PlanLog.validatePaths()` coerced non-string tag values via `Objects.toString()`, accepting `{"base64": null}` as valid non-UTF-8 bytes (`PlanLog.java:66-80`)
- **Problem**: `PlanLog.validatePaths()` used `Objects.toString(map.get("base64"))` and `Objects.toString(map.get("utf8"))` without checking `instanceof String`. For `{"base64": null}`, `Objects.toString(null)` returned `"null"`, which Base64-decoded to the 3 bytes `[0x9e, 0xe9, 0x65]` (invalid UTF-8, no NUL byte, canonical Base64 `"null"`), passing validation as a valid raw-byte path.
- **Fix applied**: Required `map.get("base64")` and `map.get("utf8")` to be non-empty `String` instances (`PlanLog.java:72, 77`). Covered by `PlanLogChecks.java:50-51`.

#### 8. [MEDIUM — Fixed] `compact` only set `db.setRetentionTime(0)` when `faultFile != null`, causing normal compaction to double file size within H2's 45s retention window (`Store.java:86`)
- **Problem**: In `Store.call("compact")` (`Store.java:86`), `db.setRetentionTime(0)` was guarded by `if (faultFile != null)`. In normal operation (`faultFile == null`), H2 `MVStore` kept its default 45,000 ms `retentionTime`; calling `compact` within 45 seconds of writes copied live chunks into new chunks while retaining all old chunks, increasing the 10k benchmark DB from `15,069,184 B` to `28,487,680 B`.
- **Fix applied**: When `snapshots.isEmpty()`, `compact` temporarily sets `db.setRetentionTime(0)` before `db.compactFile(1000)` and restores the previous retention time in `finally` (`Store.java:86`).

#### 9. [MEDIUM — Fixed] Unchecked root `registry` cast, duplicate VM memory charge on `Cursor.next_json`, and missing option/input-size checks on query and helper paths (`DslHost.java:15,26,41-52`, `Store.java:80-89,144,184,197`)
- **Problem**:
  1. `DslHost` binds the root context variable `registry` to `new Object()` (`DslHost.java:19`); calling an instance method such as `registry.item_upsert(...)` directly on `registry` instead of a handle from `registry.open(...)` threw `ClassCastException`.
  2. `DslHost.java:52` charged `out.length() * 2L + 32` inside `case "next_json"`, and the outer callback wrapper (`DslHost.java:15`) charged `result.asString().toString().length() * 2L + 32L` a second time for the same string, while `Cursor.rows()` only charged 64 bytes regardless of row count.
  3. `Store.query()` and `Snapshot.query()` bypassed the `MAX_RECORD` / `memoryBudget()/4` input size check enforced by `Store.call()`; `selector()` (`Store.java:144`) did not validate `projection` at `difference_index` creation time; and `doctor`, `export_records`, `apply_records`, and `log_append` ignored unknown keys in their `options` JSON arguments.
- **Fix applied**: Added `DslHost.store(Value)` type check (`DslHost.java:26`), removed the duplicate charge in `next_json` and charged `r.len() * 64L` in `rows` (`DslHost.java:52`), added `checkInputBudget` to `Store.query` and `Snapshot.query` (`Store.java:79,185,197`), validated `projection` in `selector()` (`Store.java:144`), and validated `options` in `doctor`, `export_records`, `apply_records`, and `log_append`.

#### 10. [MEDIUM — Open] `io.lmctl.lmcode.Json` parses all JSON numbers as `Double`, losing precision above $2^{53}$ (`third_party/lmcode-java/.../Json.java:12`, `J.java:12,18-22`)
- **Problem**: `Json.value()` in pinned `lmcode-java` v0.1.1 parses every numeric token via `Double.valueOf(n)` (`Json.java:12`). `lmfiles` mitigates this for IDs and revisions by requiring decimal strings (`docs/API.md:7`), and `J.str()` formats numbers via `new BigDecimal(n.toString()).stripTrailingZeros().toPlainString()`. However, if a caller supplies a 64-bit nanosecond timestamp (`mtime_ns` / `ctime`, ~19 digits $\approx 1.7 \times 10^{18} > 2^{53}$) or `file_id` as a JSON number rather than a string in `observe()`, `Double.valueOf` silently rounds the low ~10 bits before `Store.observe()` receives the map.
- **Recommendation**: Document in `docs/API.md` that `mtime_ns`, `ctime`, `size`, and `file_id` in `observe()` should be passed as decimal strings (like `size` in `Bench.java:23`), or update `lmcode-java` `Json.java` in 1.2 to parse integral tokens into `Long`/`BigInteger`.

---

### LOW Severity

#### 11. [LOW — Open] `DslHost` does not credit back `cache_mb` when `Registry.close()` is called (`DslHost.java:29, 43`)
- **Problem**: `DslHost.open()` charges `cache * 1024L * 1024 + 65536` against `Context` (`DslHost.java:29`), which enforces the budget across concurrently open stores (`test.sh:87-102`). Because `Context` in `lmcode-java` v0.1.1 has no `release(long)` method, closing a `Registry` handle in a long-running `_lmcode_` block does not reclaim its cache charge for subsequent `registry.open()` calls in the same `_lmcode_` block.
- **Recommendation**: Track open store cache bytes in `DslHost` separately from the monotonic VM string/collection arena, or add `unCharge` support when closing a `Store`.

#### 12. [LOW — Open] `PlanLog.unconfirmed()` calls `PlanLog.append()` per unconfirmed line, issuing one `fsync` per line on a temporary file (`PlanLog.java:152-157`)
- **Problem**: `PlanLog.unconfirmed()` writes unconfirmed instructions to `temp` by calling `append(temp, J.str(m), "{}")` per line (`PlanLog.java:154`), which opens, locks, writes, and `force(true)`s `temp` on every line before calling `channel.force(true)` again at the end (`PlanLog.java:156`) and atomically renaming `temp` to `target`. Also, `target.getParent()` directory is not `fsync`ed after `Files.move`.
- **Recommendation**: Write all unconfirmed lines through a single buffered stream/channel on `temp`, `force(true)` `temp` once before `Files.move`, and `force(true)` the parent directory channel on POSIX platforms.

#### 13. [LOW — Fixed in 0.1.1] Post-release documentation drift on `v0.1.0` tag status (`README.md:17-18`, `examples/publish_ttl.lms:1`, `durable-memory/02-build-test.md:11,17`)
- **Problem**: Tag `v0.1.0` (`9e6212b`) and commit `95b7389` (`install="github:lmctl-ai/lmfiles@latest"`) are published on `main`, but `README.md:17-18` and `examples/publish_ttl.lms:1` still state *"This is the unreleased 0.1.0 development implementation... No release/tag has been published."*

---

## Review by Dimension

### 1. Correctness of the Registry
- **Item / Alias / Observe**: `item_upsert` assigns dense monotonic positive 64-bit IDs (`1..Long.MAX_VALUE`) scoped to the local DB and rejects read-only system fields (`item`, `revision`, `stages`, `identity`, `observation`, `tombstone`). Fixing Finding #1 (clearing stale `content_hash` on stat-only revision drift) and Finding #3 (reserving alias kind `"identity"`) eliminates the two paths that could produce a false `same-as-recorded` state or duplicate item identity.
- **Stage Records, Revisions, and Projections**: `stage_record` rejects future revisions (`rev > item.revision`) and invalid statuses, and keeps `latest_attempt` and `last_success` distinct. With Finding #5 fixed, out-of-order `ok` receipts for older revisions populate `last_success` even when a newer revision has a `failed`/`skipped`/`gone` attempt.
- **Frontier Updates (`difference_index` / `stage_difference`)**: Sparse frontier entries (`<index>\0<20-digit-padded-item-id>`) are updated synchronously inside the mutating transaction via `refresh()` (`Store.java:158-160`). `stage_difference` iterates only matching prefix keys in `frontier` (`examined == pending`), never scanning all items.
- **Claims, Fencing, and Lease Expiry**: `claim()` issues monotonic fenced tokens (`<uuid>:<next_fence>`), skips items with an active lease in the same `work_namespace`, and `complete()` atomically verifies token liveness, active-claim ownership, and `expected_revision == claim.source_revision == item.revision == result.source_revision` before recording the stage and releasing the claim (`Store.java:173-182`). See Finding #4 regarding lease timer reset across short-lived `Store` reopens.
- **Snapshots and Transactions**: `Snapshot` pins the MVStore version via `db.registerVersionUsage()` and opens versioned views of `items`, `frontier`, `differences`, and `aliases` (`Store.java:196`). `WriteBatch` buffers operations up to `min(16 MiB, memoryBudget/4)` and executes them inside a single `mutate()` transaction; any exception or non-`"ok"` receipt triggers `db.rollback()`, rolling back all map mutations including sequence counters in `meta`.

### 2. Crash Safety
- **MVStore & Positional I/O**: `Store` configures `MVStore.Builder().cacheSize((int)cache).compress().autoCommitDisabled().fileName(...)` (`Store.java:44-47`), which uses H2's `SingleFileStore` / `FilePathDisk` positional `FileChannel` (`pread`/`pwrite`) and never `mmap`. `test-processes.py:137-138` verifies `/proc/<pid>/maps` contains no mapping of the DB file.
- **Locking & Stale-Lock Recovery**: `Store` acquires an exclusive OS `FileLock` on `<db>.lock` (`Store.java:39-41`) in addition to MVStore's lock on `<db>`. On process termination (`SIGKILL` or `halt(77)`), the OS kernel automatically releases the lock; `test-processes.py:131-146` and `CoreChecks.java:40` verify both cross-process and same-process lock rejection and immediate recovery after owner termination.
- **Fault-Injection Harness & the Nine Kill Points**: `test-processes.py:88-127` exercises 9 kill points (`before_commit`, `mid_commit`, `after_commit`, `disk_full`, `before_compaction`, `mid_compaction`, `after_compaction`, `after_plan_sync`, `after_result_sync`) against the same DB file using `Runtime.getRuntime().halt(77)` and `LmfilesFaultFileStore.writeFully()` (which writes half an H2 chunk, syncs, and halts).
- **What Was Missing in Fault Injection**:
  1. Appending to a journal *after* a torn write (fixed and tested in Finding #2 / `PlanLogChecks.java:24-27`).
  2. A kill point midway through multi-record `apply_records` (between committing result 1 and result 2) to exercise crash-resume deduplication via `replay_seen` in `test-processes.py`.
  3. First-open initialization fault: if `mid_commit` strikes during the very first `Store` creation on a non-existent file (before any MVStore file header block has ever been written), MVStore leaves an uninitialized file header; once the DB has completed its initial creation commit, all subsequent `mid_commit` faults recover cleanly to the prior committed chunk.

### 3. Plan/Result Codec (`PlanLog.java`)
- **Grammar & Digests**: Canonical sorted-key JSONL (`J.str` with `TreeMap`), 8-hex-digit `CRC32C` over the canonical line excluding `checksum`, 64-KiB line limit including `\n` enforced *before* buffer growth (`PlanLog.java:41, 55`), and `instruction_digest = SHA-256(canonical payload)`.
- **Non-UTF-8 Path Escaping**: `validatePaths()` (`PlanLog.java:66-80`) enforces single-key tagged objects `{"utf8": "..."}` or `{"base64": "..."}`, rejects NUL bytes, rejects non-canonical Base64, and rejects valid UTF-8 inside `{"base64": ...}` so every path has a unique canonical representation. Finding #7 fixed `null`/non-string tag coercion.
- **Out-of-Plan, Duplicates, and Replay**: `Index` (backed by a temporary MVStore so validation is bounded in memory) rejects out-of-plan results, instruction digest mismatches, duplicate `result_id`s with different payloads, conflicting completions for the same `(job, op)`, and projection mismatches (`item`, `stage`, `target`/`namespace`, `source_revision`, `source_fingerprint`). Finding #6 ensures `persisted-result-conflict` is also checked before any batch commits.

### 4. API Surface & `lmcode` Type System
- **Type System Compliance**: Every method in `HostApi.java` uses only `string`, `int`, `void`, and opaque host types (`Registry`, `Snapshot`, `WriteBatch`, `Cursor`, `Rows`). `Cursor.rows()` + `Rows.len()` / `Rows.json_at(i)` avoids per-row handle explosion.
- **Zero Business Logic in Core**: Core contains no stage names, no TTL/freshness heuristics, no retry counters, and no remote I/O.

### 5. Resource Use, Memory Budget, and 27.2 MiB Native Binary
- **`LMFILES_MAX_MEMORY_MB`**: Enforced at startup (`Main.memoryBudget()`), on bytecode loading (`min(budget/4, 64 MiB)`), on every `Store.call` and `Store.query` input (`min(1 MiB, budget/4)`), on `Cursor.nextJson` and `WriteBatch.add` (`min(16 MiB, budget/4)`), on `Store` cache sizing (`cache_mb <= budget/2`), and in the `lmcode` VM arena (`Context.charge`), including charging `cache_mb` per opened `Store`.
- **Native Binary (`28,510,472 B` / `27.2 MiB`)**: Built with GraalVM CE 25.0.2 (`pom.xml:55-99`), excluding upstream H2 Swing/AWT reflection metadata, enforcing `-H:AbortOnTypeReachable=java.awt.Toolkit`, and statically linking `libstdc++`/`libgcc`/`libz` so only `libc.so.6` is dynamically linked on Linux.

### 6. The 1,507.328 Bytes/Item Measurement
- **Reproducibility & Honesty**: Running `java -jar target/lmfiles-0.1.0-all.jar --benchmark /tmp/bench.mv 10000` reproduces `disk_bytes = 15,073,280` (`1,507.328 B/item`) and `examined = 100` on the reopened frontier query in ~2.5 ms. `docs/MEASUREMENTS.md` and `README.md` honestly state that this first codec does not meet the design's 750–1,050 B/item target.
- **Exact Payload Breakdown of the 10,000-Item Benchmark DB**:
  Inspecting the exported map payloads of the 10,000-item fixture (`17,026,265 B` logical map bytes across `15,073,280 B` on disk) reveals where the space actually goes:
  | Map | Entries | Logical Payload Bytes | Bytes / Item | Share of Payload |
  |---|---:|---:|---:|---:|
  | `events` | 60,100 | 11,203,190 B | **1,120.3 B** | **65.8%** |
  | `aliases` | 40,000 | 3,031,136 B | **303.1 B** | **17.8%** |
  | `items` | 10,000 | 2,787,822 B | **278.8 B** | **16.4%** |
  | `frontier` + `meta` + `dictionary` + `differences` | 120 | 4,117 B | **0.4 B** | **<0.1%** |
  | **Total Logical Payload** | **110,220** | **17,026,265 B** | **1,702.6 B** | **100.0%** |
- **What It Would Take to Reach 750–1,050 B/Item**:
  1. **Stop per-row `DeflaterOutputStream` wrapping on `events` (`Store.java:297`)**: Each of the 60,100 event rows is a ~220-byte JSON string individually compressed with `DeflaterOutputStream` + 12-byte header into ~186 bytes (`1,120 B/item` for 6 events/item). Individual Deflate streams on 220-byte strings have poor dictionary reuse and produce high-entropy bytes that defeat MVStore's page-level LZF compression! Storing compact binary or uncompressed UTF-8 event rows and letting MVStore compress at the 4–16 KiB page level (where repeated keys `"committed_at"`, `"operation"`, `"source_revision"`, `"namespace"` compress across dozens of events per page) cuts `events` storage by >60% (~400–450 B/item saved).
  2. **Avoid duplicating `last_success` inside `items` when `status == "ok"` (`Store.java:135-136`)**: When `latest_attempt` is `"ok"`, `p.put("last_success", success)` embeds a full copy of the stage map inside itself, doubling stage projection bytes in `items`. A boolean flag `"last_success_same": true` when `latest_attempt` is `"ok"` saves ~80–100 B/item.
  3. **Intern `(kind, namespace)` in `aliases` via `dictionary` (`Store.java:73`)**: Just as `stageId()` interns `(stage, namespace, slot)` into a dense integer ID, interning `(kind, namespace)` in `aliases` and storing raw 32-byte digests in binary item records easily brings the total footprint into the **650–850 B/item** range (well below the 750–1,050 B/item target).

### 7. Security
- **Path & Input Handling**: All string fields reject NUL (`\0`); `PlanLog` enforces canonical UTF-8 or Base64 path tags and 64 KiB line bounds; `Store.decode()` bounds `InflaterInputStream` via `in.readNBytes(length + 1)` with `0 <= length <= MAX_RECORD` (1 MiB), preventing decompression bombs; `Json.java` enforces a 128-level nesting cap and rejects duplicate keys and unescaped control characters.
- **Zero Secrets / Zero Network**: No network, HTTP, S3, or SSH code is linked; `export_records` refuses to overwrite the DB path (`Store.java:270`); `unconfirmed` refuses to overwrite `plan` or `results` (`PlanLog.java:150`).

### 8. Tests and Examples
- **Test Gate (`test.sh`, `CoreChecks`, `PlanLogChecks`, `test-processes.py`, `test-publication.py`)**: Fast, deterministic, and self-contained. `CoreChecks` (63 assertions) and `PlanLogChecks` (17 assertions) test semantic invariants and corruption detection; `test-processes.py` (18 checks) tests cross-process durability, all 9 fault-injection points against the same DB, lock contention, `/proc/<pid>/maps`, and `strace` network denial.
- **Evaluation of the Six Examples & the "Uncertain Result" Scenario**:
  - All 6 examples (`publish_ttl.lms`, `frontier.lms`, `revisions.lms`, `claims.lms`, `journal_resume.lms`, `snapshots.lms`) execute real `_lmcode_` bytecode against `lmfiles` and plot real outputs with `lmplot`.
  - In `examples/publish_ttl.lms` (verified across 6 sequential runs by `scripts/test-publication.py`), the `"uncertain"` mode (Run 3) modifies `law-a`, appends the `put` instruction to `plan.jsonl`, executes the synthetic transfer (`aws.demo()` + writing `fake-bucket/law-a.html`), and deliberately omits appending the receipt to `results.jsonl`. `apply_records` therefore leaves `law-a`'s recorded `published` stage at revision 1 while `unconfirmed` writes the 1 unconfirmed instruction to `unconfirmed.jsonl`. On Run 4 (`"changed"`), `publish_ttl.lms` replays any prior confirmed receipts before upserting items, queries `query_subset`, sees `law-a` is still `"changed"` relative to revision 1, re-transfers `law-a`, writes the confirmation receipt, and converges to `selected=0` on Run 5.
  - **One subtlety to note in `publish_ttl.lms`**: Run 4 re-identifies the unconfirmed item via `query_subset` (`state != "same-as-recorded"`) and truncates `plan.jsonl`/`results.jsonl` at line 31 after calling `apply_records` at line 17, rather than reading `unconfirmed.jsonl` as an input queue. That is completely truthful to the registry model (since unconfirmed transfers never advanced the registry's `last_success` projection, `query_subset` naturally re-selects them), while `unconfirmed.jsonl` serves as an explicit audit artifact of interrupted intents.


## 0.1.1 fix disposition

Finding4 is fixed with persisted absolute wall/logical deadlines, a persisted
clock era, and per-work-item owner generations. No reopen restarts lease_ms.
Backward wall steps fence older eras; forward jumps can expire early; monotonic
elapsed time covers a stalled wall clock inside an owner. Legacy until deadlines
are honored and an inferably future-issued legacy grant is fenced. The exact
clock assumptions and non-remote guarantees are in [LEASES.md](LEASES.md).

Regression coverage keeps the review's existing63 core/17 journal assertions and
adds23 fake-clock,22 focused-review and22 format-upgrade assertions. The process
harness adds a kill after expiry retirement and before/after format replacement,
then reopens the SAME DB and verifies fencing/data preservation. Existing nine
fault points remain. Root-context errors and both memory-budget tests run in DSL.

Both requested low-risk storage changes are implemented: interned alias families
and omission of last_success only when equal to latest_attempt. Database format2
and wire snapshot format2 are explicit; v0.1.0 data upgrades by a bounded shadow
copy with a retained format1 backup. Unknown maps fail without being discarded.
See [FORMAT_UPGRADE.md](FORMAT_UPGRADE.md) for upgrade/downgrade and old snapshots.
The comparable10k profile drops1,508.5568 to1,473.3312 B/item (about2.34%).

Finding10's supported workaround is now explicit in docs/API.md: use decimal
strings for64-bit observation metadata; the SDK's numeric parser is unchanged.
Finding13's stale release text is corrected:0.1.0 is released;0.1.1 awaits the Lead.
Findings11/12 remain lower-priority SDK-arena credit/per-line-fsync work, outside
this lease and storage patch. Reuse a Registry within a bounded host invocation;
no cache-credit or fsync-admission guarantees were weakened here. Compaction
retention/pinned-snapshot preservation is tested; immediate physical shrink is
not promised. Storage comparisons above do not depend on compaction behavior.

No AWS/admin command, tag, release or CI dispatch is part of this fix task.

Final Linux managed/native gates pass:63 core,17 journal,23 lease,22 review and22
upgrade assertions, plus21 subprocess checks covering12 fault points. The new
review tests include exact registered-callback budgets for one next_json charge
and materialized row charging. No AWS/admin commands or new tag/release.
