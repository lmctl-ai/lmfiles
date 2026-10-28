# lmfiles durable memory

- [01-architecture.md](01-architecture.md): purpose, implemented API boundary and durability.
- [02-build-test.md](02-build-test.md): SDK/H2 pins, native packaging, local gates and manual CI.
- [03-examples.md](03-examples.md): synthetic jobs, receipts and gallery generation.
- [04-open-items.md](04-open-items.md): 1C scope and known limits.
- [../docs/API.md](../docs/API.md): current JSON contracts and implemented schema subset.
- [../docs/JOURNAL.md](../docs/JOURNAL.md): optional plan/result codec and confirmation gap.
- [../docs/MEASUREMENTS.md](../docs/MEASUREMENTS.md): historical size and test evidence.
- [../docs/measurements.json](../docs/measurements.json): synthetic local measurements and exact native digest.
- [../docs/LEASES.md](../docs/LEASES.md):0.1.1 fixed deadlines, clock eras and generation fencing.
- [../docs/FORMAT_UPGRADE.md](../docs/FORMAT_UPGRADE.md):released format1/2 to3 backup, migration and rollback contract.
- [../docs/measurements-0.1.1.json](../docs/measurements-0.1.1.json): comparable format1/format2 10k storage results and patch gate counts.

- [../docs/SCAN.md](../docs/SCAN.md): optional incremental local ingestion and failure semantics.

- [../docs/measurements-1c-a.json](../docs/measurements-1c-a.json): historical storage and local functional pairing evidence (no new measurements planned).
- [../docs/S3_100_TEST.md](../docs/S3_100_TEST.md): completed authorized100-object live test, seeded soak, reconciliation and zero-leftover proof.
- [../docs/S3_100_RESULTS.json](../docs/S3_100_RESULTS.json): sanitized completed live counts/status/skew/cost; raw IDs stay private.

- [../docs/LOCAL_SCALE_TEST.md](../docs/LOCAL_SCALE_TEST.md): explicitly authorized local guarded correctness ramp, independent of S3.

- [../docs/LOCAL_SCALE_RESULTS.json](../docs/LOCAL_SCALE_RESULTS.json): completed guarded local campaign counts, maintenance skips and cleanup evidence.

- [../docs/DRVFS_LIMITATIONS.md](../docs/DRVFS_LIMITATIONS.md): E: mount probe observations; volume correctness only, unsupported durability.

- [../docs/DRVFS_PROBE_RESULTS.json](../docs/DRVFS_PROBE_RESULTS.json): sampled mount behavior; a concurrent rename reader observed a missing destination.

- [../docs/DRVFS_VOLUME_RESULTS.json](../docs/DRVFS_VOLUME_RESULTS.json): E:1M volume correctness passed; budget stopped before10M; cleanup verified.
