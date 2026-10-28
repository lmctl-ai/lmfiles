#!/usr/bin/env python3
"""Pure correctness oracle. No registry reads, AWS clients, subprocesses or transfers.

The live driver must supply independently collected local, registry and S3 views.
Detailed mismatches stay private; publish only counts/status/max observed skew.
"""
import base64, collections, datetime, hashlib, re

SEED = 20261027
ROUNDS = 30
PUT_CAP = 400
UNIVERSE = tuple(f"synthetic-{i:03d}.txt" for i in range(100))
KINDS = ('create', 'update', 'touch', 'delete', 'rename', 'recreate', 'noop')


def fingerprint(body):
    return dict(size=len(body), sha256=hashlib.sha256(body).hexdigest(),
                md5=hashlib.md5(body, usedforsecurity=False).hexdigest())


class Model:
    """Expected local tree, independent of registry and remote receipts."""
    def __init__(self, seed=SEED, initial=None):
        if not isinstance(seed, int) or isinstance(seed, bool) or not 0 <= seed < 2**32:
            raise ValueError('seed must be an unsigned 32-bit integer')
        self.seed = self.state = seed
        self.files = dict(initial) if initial is not None else {key: f'synthetic source {key} generation 0\n'.encode() for key in UNIVERSE}
        if not set(self.files) <= set(UNIVERSE) or any(not isinstance(body, bytes) or len(body) > 4096 for body in self.files.values()):
            raise ValueError('initial fixture must use the fixed universe and small byte bodies')
        self.last_changed = {key: 0 for key in self.files}
        self.current_round = 0
        self.in_round = False
        self.round_touched = set()
        self.deleted = set(UNIVERSE) - set(self.files)
        self.mtimes = {key: 0 for key in UNIVERSE}
        self.sequence = 0

    def random(self):
        # Specified LCG rather than a library-version-dependent PRNG.
        self.state = (1664525 * self.state + 1013904223) & 0xffffffff
        return self.state

    def choose(self, values):
        values = sorted(values)
        return values[self.random() % len(values)] if values else None

    def view(self):
        return {key: dict(fingerprint(body), round=self.last_changed[key]) for key, body in self.files.items()}

    def operation(self, kind):
        if kind not in KINDS:
            raise ValueError('unknown operation')
        self.sequence += 1
        present = set(self.files)
        absent = set(UNIVERSE) - present
        if self.in_round:
            present -= self.round_touched
            absent -= self.round_touched
        candidates = absent if kind == 'create' else (absent & self.deleted if kind == 'recreate' else present)
        key = self.choose(candidates)
        if kind == 'noop' or key is None:
            return dict(requested=kind, applied='noop', reason='requested_noop' if kind == 'noop' else 'no_eligible_key')
        if kind == 'rename':
            target = self.choose(absent)
            if target is None:
                return dict(requested=kind, applied='noop', reason='universe_full')
            self.files[target] = self.files.pop(key)
            self.mtimes[target] = self.sequence
            self.last_changed[target] = self.current_round
            self.deleted.add(key)
            if self.in_round:
                self.round_touched.update([key, target])
            return dict(requested=kind, applied=kind, key=key, target=target)
        if kind in ('create', 'recreate', 'update'):
            body = f'synthetic source {key} generation {self.sequence}\n'.encode()
            if body == self.files.get(key):
                body += b'changed\n'
            self.files[key] = body
            self.last_changed[key] = self.current_round
        elif kind == 'delete':
            del self.files[key]
            self.deleted.add(key)
            self.last_changed[key] = self.current_round
        self.mtimes[key] = self.sequence
        if self.in_round:
            self.round_touched.add(key)
        return dict(requested=kind, applied=kind, key=key)

    def round(self, number):
        self.current_round = number
        before = self.view()
        self.in_round = True
        self.round_touched = set()
        try:
            operations = [self.operation(KINDS[self.random() % len(KINDS)]) for _ in range(4)]
        finally:
            self.in_round = False
        after = self.view()
        adds = sorted(after.keys() - before.keys())
        changed = sorted(k for k in after.keys() & before.keys() if after[k]['sha256'] != before[k]['sha256'])
        deletes = sorted(before.keys() - after.keys())
        return dict(round=number, operations=operations, expected=after,
                    plan=dict(adds=adds, changed=changed, deletes=deletes))


def soak_plan(seed=SEED, rounds=ROUNDS, initial=None):
    if not isinstance(rounds, int) or isinstance(rounds, bool) or not 1 <= rounds <= ROUNDS:
        raise ValueError('rounds must be 1..30')
    model = Model(seed, initial)
    plan = [model.round(i) for i in range(1, rounds + 1)]
    counts = collections.Counter(op['applied'] for row in plan for op in row['operations'])
    # 100 initial + 5 delta + 100 generation + uncertain/precondition/retry reserves.
    puts = 100 + 5 + 100 + 10 + sum(len(r['plan']['adds']) + len(r['plan']['changed']) for r in plan)
    if puts > PUT_CAP:
        raise ValueError('scripted and random plan exceeds the conservative 400-PUT admission cap')
    faults = {}
    for earliest, fault in [(5, 'kill'), (11, 'lost_response'), (17, 'failed_upload')]:
        if earliest > rounds:
            continue
        candidates = [r['round'] for r in plan if r['round'] >= earliest and r['round'] not in faults
                      and (r['plan']['adds'] or r['plan']['changed'])]
        if not candidates:
            raise ValueError('seed/round count does not provide an eligible fault round')
        faults[candidates[0]] = fault
    return dict(seed=seed, rounds=plan, operations_by_type={k: counts[k] for k in KINDS},
                put_admission_bound=puts, faults=faults)


def timestamp_ms(value):
    if not isinstance(value, str):
        raise ValueError('timestamp must be an aware ISO-8601 string')
    # Java Instant emits up to9 fractional digits; Python3.10 accepts at most6.
    normalized = re.sub(r'(\.\d{6})\d+(?=Z$|[+-]\d\d:\d\d$)', r'\1', value)
    date = datetime.datetime.fromisoformat(normalized.replace('Z', '+00:00'))
    if date.utcoffset() is None:
        raise ValueError('timestamp must include a timezone')
    return round(date.timestamp() * 1000)


def remote_sha(row):
    checksum = row.get('checksum_sha256')
    metadata = row.get('metadata', {}).get('sha256')
    digest = None
    if checksum:
        if row.get('checksum_type', 'FULL_OBJECT') != 'FULL_OBJECT':
            raise ValueError('composite checksum is not a full-object SHA256')
        raw = base64.b64decode(checksum, validate=True)
        if len(raw) != 32 or base64.b64encode(raw).decode() != checksum:
            raise ValueError('additional checksum is not a canonical full-object SHA256')
        digest = raw.hex()
    if metadata:
        if not isinstance(metadata, str) or re.fullmatch('[0-9a-f]{64}', metadata) is None:
            raise ValueError('invalid SHA256 object metadata')
        if digest and digest != metadata:
            raise ValueError('S3 additional checksum and metadata disagree')
        digest = metadata
    if not digest:
        raise ValueError('no S3 full-object SHA256 or explicit SHA256 metadata')
    return digest


def reconcile(local, registry, remote, history=(), tolerance_seconds=300, expected=None, origins=None):
    """Inputs are keyed by object key; never derive local/model truth from registry.

    Registry rows: present, size, sha256, etag, version, request_started,
    confirmed_at, revision, round. Remote rows: size, etag, version,
    checksum_sha256 (base64) or metadata.sha256, last_modified, content_type,
    cache_control. Local rows: size, sha256, md5, content_type, cache_control.
    History holds independently verified prior receipts for ordering across rounds.
    expected/origins come from the independent model, including deletion rounds.
    """
    if not isinstance(tolerance_seconds, int) or not 0 <= tolerance_seconds <= 300:
        raise ValueError('clock tolerance must be 0..300 seconds')
    errors = []
    def mismatch(key, view, field, round_number=None):
        if origins is not None and key in origins:
            round_number = origins[key]
        if round_number is None:
            round_number = (expected or {}).get(key, {}).get('round', registry.get(key, {}).get('round', 'unknown'))
        errors.append(dict(key=key, view=view, field=field, round=round_number))
    if expected is not None:
        for key in sorted(set(local) ^ set(expected)):
            mismatch(key, 'local_vs_model', 'key_set')
        for key in sorted(set(local) & set(expected)):
            for field in ('size', 'sha256', 'md5'):
                if local[key].get(field) != expected[key].get(field):
                    mismatch(key, 'local_vs_model', field)
    for key, row in registry.items():
        if row.get('present') is False and (row.get('confirmed') is not True or row.get('status') != 'ok'):
            mismatch(key, 'registry', 'unconfirmed_absence', row.get('round'))
    believed = {k: row for k, row in registry.items() if row.get('present') is True}
    keys = set(local)
    for view, rows in [('registry', believed), ('s3', remote)]:
        for key in sorted(keys ^ set(rows)):
            mismatch(key, view, 'key_set', registry.get(key, {}).get('round'))
    maximum_skew = 0
    valid_intervals = 0
    ordering = list(history)
    for key in sorted(keys & set(believed) & set(remote)):
        actual, recorded, observed = local[key], believed[key], remote[key]
        round_number = recorded.get('round')
        for view, row in [('local', actual), ('registry', recorded), ('s3', observed)]:
            if type(row.get('size')) is not int or row['size'] < 0:
                mismatch(key, view, 'size_missing_or_invalid', round_number)
        if recorded.get('status') != 'ok' or recorded.get('confirmed') is not True:
            mismatch(key, 'registry', 'unconfirmed_belief', round_number)
        for field in ('size', 'sha256'):
            if actual.get(field) != recorded.get(field):
                mismatch(key, 'registry', field, round_number)
        if observed.get('size') != actual.get('size'):
            mismatch(key, 's3', 'size', round_number)
        try:
            if remote_sha(observed) != actual.get('sha256'):
                mismatch(key, 's3', 'sha256', round_number)
        except (ValueError, TypeError, AttributeError):
            mismatch(key, 's3', 'checksum_missing_or_invalid', round_number)
        etag = str(observed.get('etag', '')).strip('"')
        if etag != actual.get('md5'):
            mismatch(key, 's3', 'single_part_etag_md5', round_number)
        if str(recorded.get('etag', '')).strip('"') != etag:
            mismatch(key, 'registry', 'etag', round_number)
        if recorded.get('version') != observed.get('version'):
            mismatch(key, 'registry', 'version', round_number)
        for field in ('content_type', 'cache_control'):
            if observed.get(field) != actual.get(field):
                mismatch(key, 's3', field, round_number)
        try:
            start = timestamp_ms(recorded['request_started'])
            confirmed = timestamp_ms(recorded['confirmed_at'])
            modified = timestamp_ms(observed['last_modified'])
            skew = max(start - modified, modified - confirmed, 0)
            maximum_skew = max(maximum_skew, skew)
            valid_intervals += 1
            if start > confirmed or skew > tolerance_seconds * 1000:
                mismatch(key, 's3_vs_registry', 'last_modified_interval', round_number)
            ordering.append(dict(key=key, round=round_number, revision=recorded['revision'],
                                 last_modified=observed['last_modified']))
        except (KeyError, ValueError, TypeError):
            mismatch(key, 's3_vs_registry', 'timestamp_missing_or_invalid', round_number)
    # Revisions are per item, NOT comparable between different keys. Global rounds
    # establish serial upload order. S3 whole-second timestamps may tie.
    try:
        by_round = collections.defaultdict(list)
        by_key = collections.defaultdict(list)
        for receipt in ordering:
            when = timestamp_ms(receipt['last_modified'])
            if type(receipt['round']) is not int or type(receipt['revision']) is not int or receipt['round'] < 0 or receipt['revision'] < 1:
                raise ValueError('round/revision must be normalized integers')
            by_round[receipt['round']].append((when, receipt))
            by_key[receipt['key']].append((receipt['revision'], when, receipt))
        previous_max = None
        for number in sorted(by_round):
            for when, row in by_round[number]:
                if previous_max is not None and when < previous_max:
                    mismatch(row['key'], 's3_vs_registry', 'round_timestamp_order', number)
            previous_max = max([v[0] for v in by_round[number]] + ([] if previous_max is None else [previous_max]))
        for rows in by_key.values():
            previous_revision = previous_time = None
            for revision, when, row in sorted(rows, key=lambda x: x[0]):
                if previous_revision is not None and revision > previous_revision and when < previous_time:
                    mismatch(row['key'], 's3_vs_registry', 'revision_timestamp_order', row['round'])
                previous_revision, previous_time = revision, when
    except (KeyError, TypeError, ValueError):
        mismatch(None, 'history', 'invalid_timestamp_ordering_input')
    return dict(ok=not errors, local_keys=len(local), registry_keys=len(believed), s3_keys=len(remote),
                observed_max_skew_seconds=maximum_skew / 1000 if valid_intervals else None, valid_intervals=valid_intervals, mismatches=errors)
