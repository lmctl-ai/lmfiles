# Remaining scope and limits

Performance and scale measurement are not planned; correctness is the bar.
No100M benchmark, scale-qualification goal or large-upload campaign.

1C-a adds real loopback lmcurl/lmssh jobs, optional local scan and format3 compact
events. The10k fixture is retained as historical informational evidence only;
it is not a scale or performance qualification. See docs/MEASUREMENTS.md.

Remaining1C: general attribute/hash/time/aggregate indexes, change cursors,
checkpoints/event archives, coarse multi-process mailbox, packed item/raw-digest
and alias layouts if justified, broader raw POSIX path I/O. Scanning does not
infer deletion or safely skip directory contents
from directory mtime alone. Stat-preserving writers need always_hash.

Only one100-object S3 correctness run is now authorized (profile lmaws, exact
lmaws-lmfiles-<runid> bucket, us-east-1, matching run tag, cap$0.05). It is blocked
before AWS by released lmaws0.1.22 API gaps; see docs/S3_100_TEST.md. No resource
was created and no remote absence proof was attempted. Existing upload examples
still use the local executor. SFTP examples require installed sshd on Linux and strict temporary
known_hosts. Native macOS/Windows support remains separately unverified.

SDK JSON numbers use Double: use decimal strings for64bit metadata. SDK cache
arena credits on close and PlanLog.unconfirmed per-line fsync optimization remain
review lows11/12; no guarantees were weakened. Lead owns future releases.

The same blocked S3 test now includes30 seeded rounds (seed20261027), an independent
model/three-way oracle, checksum/timestamp reconciliation and explicit listing
import after DB loss. No live rounds ran; no max skew or convergence is claimed.
Original client upload intervals cannot be reconstructed from listing alone.

Latest Lead override authorizes a LOCAL1M/5M/20M correctness ramp independently
of S3, guarded by40GB root free minimum and25GB registry maximum, mandatory
temporary DB cleanup. Informational numbers only. See docs/LOCAL_SCALE_TEST.md;
do not confuse this specific campaign with an open-ended performance roadmap.
