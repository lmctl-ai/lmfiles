# Persisted lease semantics (0.1.1)

A grant contains a fixed absolute wall deadline (`deadline_wall_ms`, also exposed
as `until`), a fixed logical deadline (`deadline_ms`), duration, clock era and
persisted owner generation. Reopening the DB changes neither deadline. Only a
successful, unexpired `renew` grants another duration.

Lease operations maintain a persisted last-observed wall time and logical high
watermark. Within an owner, monotonic elapsed time advances logical time even if
the wall clock stalls. Across short-lived owners, wall time and the saved logical
floor preserve progress; the duration is never restarted. Merely opening/querying
a format-2 DB does not mutate lease state or allocate an owner generation.

An observed **backward wall step starts a new persisted clock era** and fences
older grants. A forward jump may expire a grant early. Reversing that jump cannot
resurrect a retired token. This deliberately favors stale-owner rejection over
keeping work assigned during a clock discontinuity. It can cause duplicate
external work if an old worker continues; registry fencing controls recording,
not remote cancellation. Scripts still own retry/idempotency/preconditions.

Every ownership assignment gets an increasing persisted generation. Its token,
active work-item mapping and generation must agree before renewal/completion.
Expiry removes that token/active mapping and retains the generation fence in one
synced transaction. Reclaim gets a higher generation. Old completions and renewals
return `fenced-or-expired`, including after another process or a crash reopens DB.
Revision conflicts preserve a still-live grant; an invalid result is not success.

Legacy v0.1.0 grants retain their original `until` wall deadline; old in-memory
`deadline_ns`/`owner_epoch` values are ignored. If current wall time is earlier
than the inferable grant/renew time (`until - lease_ms`), the legacy token is
fenced rather than extended. No expired grant is renewed during upgrade.

Clock limits are explicit: no local algorithm can reconstruct unobserved elapsed
time while all clocks are frozen across process downtime. A usable wall clock is
required between owners. Monotonic fallback covers observed live-owner time;
clock steps observed by lease operations fence conservatively. These are
coordination primitives, not stage-age rules or an automatic job scheduler.

`--lease-test DIR` uses an injectable in-process fake clock (no production time
override). The process gate kills immediately after durable expiry retirement,
then reopens, reclaims, and rejects the old holder before completing the new one.

Claimed workflows must report through `complete(token,...)`. Direct stage_record
and generic journal record projections are unclaimed reporting primitives; they
do not authenticate ownership. A script must not use them as a substitute for
fenced completion when it requires claims.
