# Remaining scope and limits

1C-a adds real loopback lmcurl/lmssh jobs, optional local scan and format3 compact
events. The10k fixture meets the lean target (804.4544B/item); it does not qualify
100M/1B items or metadata-heavy profiles. See docs/MEASUREMENTS.md.

Remaining1C: general attribute/hash/time/aggregate indexes, change cursors,
checkpoints/event archives, coarse multi-process mailbox, packed item/raw-digest
and alias layouts if justified, fixed-memory100M scale qualification, broader raw
POSIX path I/O. Scanning does not infer deletion or safely skip directory contents
from directory mtime alone. Stat-preserving writers need always_hash.

No live cloud or real remote test is authorized here. S3 stays a deterministic
local executor. SFTP examples require installed sshd on Linux and strict temporary
known_hosts. Native macOS/Windows need later qualification of the new code.

SDK JSON numbers use Double: use decimal strings for64bit metadata. SDK cache
arena credits on close and PlanLog.unconfirmed per-line fsync optimization remain
review lows11/12; no guarantees were weakened. Lead owns0.1.2 release.
