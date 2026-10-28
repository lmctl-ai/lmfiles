# E: (9p/DrvFS) characterization

`/mnt/e` is a Windows-backed 9p/DrvFS mount.  It is available for the
large-volume, correctness-only portion of the optional scale campaign, but it
is **not a supported durability target** for lmfiles.  The crash-safe registry
tests remain on Linux ext4 (`/tmp` or the repository filesystem), where fsync,
locks, and atomic replacement have the required semantics.

Run the self-cleaning probe with:

```text
python3 scripts/probe-drvfs.py
```

The probe creates one uniquely named `lmfiles-scale-probe-*` directory under
`/mnt/e`, tests fsync return, cross-process exclusive locking, replacement
visibility, and a killed mid-write prefix, then removes that directory in a
`finally` path.  It prints only boolean-style outcomes and never includes
account, bucket, or user-file data.  A passing observation characterizes this
machine; it does not establish crash durability for a registry stored there.

The scale runner must keep its E: guards (500 GB minimum free space and 150 GB
maximum fixture size) and must remove its dedicated directory after every run.

## Characterization on 2026-10-27

The first probe run observed fsync returning, second-process lock exclusion,
400 replacements without a missing/partial payload, and20 complete JSONL records
plus a torn tail after deterministic SIGKILL. A repeated probe under concurrent
volume load **observed a missing destination during replacement** (reader exit2).
The writer completed400 replacements. Both runs removed their dedicated probe
directories. [DRVFS_PROBE_RESULTS.json](DRVFS_PROBE_RESULTS.json) records the
repeat's `limited` result; a later passing probe must not erase this observation.

These are sampled mount observations, not proof of atomicity or persisted writes
across host/Windows crashes. No power failure or Windows restart was tested.
The second-process lock probe uses `flock`; lmfiles itself uses Java FileLock,
whose durability-sensitive exclusion tests remain on ext4. SIGKILL here kills a
Python JSONL writer only, not a registry or the Windows host. This explicit probe
is the sole small mount characterization exception; registry crash/compaction/
upgrade tests never run on E:.

## Completed volume-only run

The one-hour ramp completed all five phases at **1,000,000 items**, then returned
`budget_stopped` before attempting10M. The next step projected3,690.6seconds with
25% margin, exceeding the remaining3,304.8seconds. No50M/100M attempt was made.
The dedicated directory was removed and its absence independently checked.
[DRVFS_VOLUME_RESULTS.json](DRVFS_VOLUME_RESULTS.json) contains exact counts.

| Operation | Correctness result | Seconds (informational) |
|---|---|---:|
| Bulk index |1M items,2M aliases,4,999,000 events |255.76 |
| Identity/alias/subset verify |10,009 samples;1,000 pending, exact membership |7.10 |
| Update |10,000 new revisions; all failed-after-success projections retained |23.30 |
| Claim/complete |128 completed; duplicate completions fenced |1.30 |
| Reopen and verify |10,009 samples;4,186 pending, exact membership |7.17 |

Final exact counts:1M items,2M aliases,5,055,804 events,0 active claims.
Minimum observed E: free2,847.64GB; peak fixture0.926GB (limits500GB/150GB).
End-of-step physical DB925.7bytes/item is informational only. The managed JVM
performed the large step; a native1,000-item volume smoke also passed. No registry
mismatch was found. These statements confer no crash/durability qualification.
