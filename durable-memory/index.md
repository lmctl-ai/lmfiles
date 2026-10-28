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
- [../docs/S3_100_TEST.md](../docs/S3_100_TEST.md): authorized100-object live test plus seeded soak/reconciliation, blocked at the released-host API preflight.
- [../docs/S3_100_RESULTS.json](../docs/S3_100_RESULTS.json): sanitized counts/status; no AWS session or resources created.

- [../docs/LOCAL_SCALE_TEST.md](../docs/LOCAL_SCALE_TEST.md): explicitly authorized local guarded correctness ramp, independent of S3.
