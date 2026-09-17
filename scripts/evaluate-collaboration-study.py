#!/usr/bin/env python3
"""Evaluate declared human study data; synthetic/LLM scores cannot prove acceptance."""
import argparse
import csv
import json
import random
import re
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path
from statistics import median

GENRES = ('mystery', 'wuxia')
CONDITIONS = ('collaboration', 'solo')
DIMENSIONS = ('engagement', 'coherence', 'character', 'hook', 'overall')
CHAPTERS = 20
MIN_READERS = 3
SEED = 20260917
SAMPLES = 10000


def number(raw, field):
    try:
        value = Decimal(raw)
    except (InvalidOperation, TypeError, ValueError):
        raise ValueError(f'{field}: missing or invalid number') from None
    if not value.is_finite():
        raise ValueError(f'{field}: number must be finite')
    if field in DIMENSIONS and not 1 <= value <= 10:
        raise ValueError(f'{field}: score must be 1..10')
    if field not in DIMENSIONS and value < 0:
        raise ValueError(f'{field}: must be nonnegative')
    if field == 'severeContinuityErrors' and value != value.to_integral_value():
        raise ValueError(f'{field}: must be an integer')
    return value


def statistics(pairs):
    """One observation per paired chapter, not per reader."""
    n = len(pairs)
    collab = sum(p[0] for p in pairs) / n
    solo = sum(p[1] for p in pairs) / n
    collab_time = median(p[2] for p in pairs)
    solo_time = median(p[3] for p in pairs)
    collab_errors = sum(p[4] for p in pairs)
    solo_errors = sum(p[5] for p in pairs)
    return dict(pairedChapters=n, collaborationOverall=collab, soloOverall=solo,
                qualityDifference=sum(p[0] - p[1] for p in pairs) / n,
                collaborationMedianMinutes=collab_time, soloMedianMinutes=solo_time,
                timeReduction=(solo_time - collab_time) / solo_time if solo_time else None,
                collaborationSevereErrors=collab_errors, soloSevereErrors=solo_errors,
                severeErrorDifference=collab_errors - solo_errors)


def percentile(values, fraction):
    values = sorted(values)
    position = (len(values) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(values) - 1)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def bootstrap(groups):
    # Decimal throughout: even finite very small/large timing values remain valid.
    rng = random.Random(SEED)
    metrics = ('qualityDifference', 'timeReduction', 'severeErrorDifference')
    samples = {scope: {metric: [] for metric in metrics} for scope in (*GENRES, 'overall')}
    for _ in range(SAMPLES):
        drawn = {genre: rng.choices(groups[genre], k=CHAPTERS) for genre in GENRES}
        drawn['overall'] = [p for genre in GENRES for p in drawn[genre]]
        for scope, pairs in drawn.items():
            summary = statistics(pairs)
            for metric in metrics:
                if summary[metric] is not None:
                    samples[scope][metric].append(summary[metric])
    return {scope: {metric: dict(
                lower=percentile(values, Decimal('0.025')) if values else None,
                upper=percentile(values, Decimal('0.975')) if values else None,
                validSamples=len(values)) for metric, values in metrics.items()}
            for scope, metrics in samples.items()}


def evaluate(manifest_path: Path, ratings_path: Path):
    result = dict(status='INCOMPLETE', reasons=[], summary={})
    reasons = result['reasons']
    try:
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        kind = manifest.get('evaluatorType', '') if isinstance(manifest, dict) else ''
        if not isinstance(kind, str) or not re.fullmatch(r'HUMAN(?:\s+.*)?', kind.strip()):
            reasons.append('Explicit HUMAN evaluatorType required; LLM-as-judge is not accepted')
    except (OSError, ValueError):
        reasons.append('manifest missing or invalid JSON')
    try:
        with ratings_path.open(newline='', encoding='utf-8') as file:
            reader = csv.DictReader(file)
            required = {'genre', 'condition', 'chapter', 'readerId', 'authorMinutes',
                        'severeContinuityErrors', *DIMENSIONS}
            missing = required - set(reader.fieldnames or [])
            if missing:
                reasons.append('missing columns: ' + ', '.join(sorted(missing)))
            rows = list(reader)
    except (OSError, csv.Error, UnicodeError):
        reasons.append('ratings file missing or unreadable')
        return result
    if not rows:
        reasons.append('ratings file empty')
        return result
    if missing:
        return result

    cells = {}
    for line, row in enumerate(rows, start=2):
        try:
            genre, condition = row['genre'], row['condition']
            if genre not in GENRES or condition not in CONDITIONS:
                raise ValueError('unknown genre/condition')
            raw_chapter = row['chapter'] or ''
            if not re.fullmatch(r'[0-9]+', raw_chapter) or not 1 <= int(raw_chapter) <= CHAPTERS:
                raise ValueError('chapter must be an integer in 1..20')
            key = (genre, condition, int(raw_chapter))
            reader_id = (row['readerId'] or '').strip()
            if not reader_id:
                raise ValueError('readerId must be nonblank')
            scores = {dim: number(row[dim], dim) for dim in DIMENSIONS}
            time = number(row['authorMinutes'], 'authorMinutes')
            errors = int(number(row['severeContinuityErrors'], 'severeContinuityErrors'))
            cell = cells.setdefault(key, dict(readers={}, time=time, errors=errors))
            if reader_id in cell['readers']:
                raise ValueError('duplicate reader for chapter/condition')
            if cell['time'] != time or cell['errors'] != errors:
                raise ValueError('conflicting authorMinutes/severeContinuityErrors within chapter/condition')
            cell['readers'][reader_id] = scores['overall']
        except ValueError as error:
            reasons.append(f'row {line}: {error}')
    missing_cells = []
    for genre in GENRES:
        for condition in CONDITIONS:
            for chapter in range(1, CHAPTERS + 1):
                count = len(cells.get((genre, condition, chapter), {}).get('readers', {}))
                if count < MIN_READERS:
                    missing_cells.append(f'{genre}/{condition}/ch{chapter}: {count} distinct readers (<3)')
    result['missingCount'] = len(missing_cells)
    reasons.extend(missing_cells)
    if reasons:
        return result

    groups = {}
    for genre in GENRES:
        pairs = []
        for chapter in range(1, CHAPTERS + 1):
            c, s = (cells[(genre, condition, chapter)] for condition in CONDITIONS)
            # Stable order also makes bootstrap output independent of CSV row ordering.
            c_mean = sum(c['readers'][r] for r in sorted(c['readers'])) / len(c['readers'])
            s_mean = sum(s['readers'][r] for r in sorted(s['readers'])) / len(s['readers'])
            pairs.append((c_mean, s_mean, c['time'], s['time'], c['errors'], s['errors']))
        groups[genre] = pairs
    all_pairs = [pair for genre in GENRES for pair in groups[genre]]
    result['genres'] = {genre: statistics(groups[genre]) for genre in GENRES}
    result['summary'] = statistics(all_pairs)
    result['summary'].update(entries=len(rows), genreConditionChapterCells=len(cells), cellsBelowReaderThreshold=0)
    scopes = {**result['genres'], 'overall': result['summary']}
    for scope, summary in scopes.items():
        if summary['timeReduction'] is None:
            reasons.append(f'{scope}: solo median author time is zero; reduction undefined')
    if reasons:
        return result

    intervals = bootstrap(groups)
    result['bootstrap'] = dict(seed=SEED, samples=SAMPLES, confidenceLevel=0.95,
                               method='genre-stratified paired-chapter percentile bootstrap')
    for scope, summary in scopes.items():
        summary['confidenceIntervals'] = intervals[scope]
        if summary['severeErrorDifference'] > 0:
            reasons.append(f'{scope}: severe continuity errors increased')
    summary = result['summary']
    if summary['qualityDifference'] < Decimal('-0.2'):
        reasons.append('overall: quality difference below -0.2')
    if summary['timeReduction'] < Decimal('0.2'):
        reasons.append('overall: median author time reduction below 20%')
    result['status'] = 'FAILED' if reasons else 'COMPLETE'
    return result


def json_decimal(value):
    if isinstance(value, Decimal):
        # Ordinary metrics stay JSON numbers; extreme finite values remain exact strings.
        numeric = float(value)
        import math
        return numeric if math.isfinite(numeric) and (numeric != 0 or value == 0) else str(value)
    raise TypeError(type(value).__name__)


def main():
    parser = argparse.ArgumentParser(description='Collaboration study protocol evaluation')
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--ratings', required=True)
    parser.add_argument('--require-complete', action='store_true')
    args = parser.parse_args()
    result = evaluate(Path(args.manifest), Path(args.ratings))
    print(json.dumps(result, ensure_ascii=False, indent=2, default=json_decimal, allow_nan=False))
    if args.require_complete and result['status'] != 'COMPLETE':
        sys.exit(1)


if __name__ == '__main__':
    main()
