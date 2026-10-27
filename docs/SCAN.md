# Optional local ingestion

`Registry.scan(root, options_json) -> string` streams a local directory into the
current registry namespace. CLI: `lmfiles --request state.mv scan ./input '{}'`.
It only reads source files and writes registry observations; it never transfers,
deletes, assigns a stage or contacts a remote. Keep the registry outside the input
tree. Caller scripts still choose their workflow and missing-file policy.

An identity is the full SHA-256 of the normalized absolute root, a colon, then
the slash-separated relative path. Different roots cannot alias the same relative
name. Moving a root creates new identities; use explicit item/alias APIs when a
stable external identity is required. Root symlinks are not canonicalized into
another root's identity.

Options (unknown keys and wrong boolean types fail):

| Parameter | Default | Meaning |
|---|---|---|
| hash_algorithm | SHA-256 | Java MessageDigest algorithm; stored with observations |
| always_hash | false | Hash even when size, nanosecond mtime, presence and file ID match |
| include / exclude | [] | Arrays of filesystem glob patterns over relative paths |
| max_files | unlimited | Bound the selected files visited in this call |
| follow_links | false | Explicitly allow symlinks; loops and I/O failures are errors |
| shards / shard | 1 / 0 | Partition by top-level directory (root files by filename); range 0..shards-1 |

Size/mtime/file ID first; hash new/changed files or on demand. The helper verifies
size, mtime and file ID again after hashing and rejects an unstable read. Stat
reuse assumes producers do not preserve all those fields after changing bytes;
choose always_hash for that threat model. Directory mtimes cannot prove unchanged
file contents, so they do not authorize skipping an entire selected subtree.
Unselected directory shards are pruned. Files absent from the traversal are not
marked deleted or missing automatically.

Traversal memory is bounded by directory depth, a 64-KiB hash buffer and at most
256 pending observations (also constrained by the registry batch byte budget).
Each batch commits and syncs. Failure leaves earlier acknowledged batches intact
and discards the pending batch; rerunning converges. No-op files produce no event
or revision change. Results contain files, changed, hashed, bytes, elapsed_ms and
root; counters are decimal strings. They describe the visited subset, not an
implicit inventory of the entire tree.

The managed/native gate creates 100,000 synthetic small files, tests no-op hash
reuse, revisions, algorithm changes, root separation, sharding, symlinks, missing
files and failure recovery. See MEASUREMENTS.md for timings and limits.
