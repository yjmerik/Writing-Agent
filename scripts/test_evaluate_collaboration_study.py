#!/usr/bin/env python3
"""Synthetic contract data only; never evidence for the real human study."""
import csv
from decimal import Decimal
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name('evaluate-collaboration-study.py')
FIELDS = ['genre', 'condition', 'chapter', 'readerId', 'engagement', 'coherence',
          'character', 'hook', 'overall', 'authorMinutes', 'severeContinuityErrors']


def full_rows(quality='8', minutes='32'):
    return [dict(zip(FIELDS, [genre, condition, chapter, reader, 8, 8, 8, 8,
                             quality if condition == 'collaboration' else '8',
                             minutes if condition == 'collaboration' else '40', 0]))
            for genre in ('mystery', 'wuxia') for condition in ('collaboration', 'solo')
            for chapter in range(1, 21) for reader in ('r1', 'r2', 'r3')]


class StudyContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.manifest = Path(self.tmp.name) / 'manifest.json'
        self.ratings = Path(self.tmp.name) / 'ratings.csv'
        self.manifest.write_text(json.dumps({'evaluatorType': 'HUMAN blind readers'}))

    def run_study(self, rows, fields=FIELDS, require=True):
        if rows is not None:
            with self.ratings.open('w', newline='') as file:
                writer = csv.DictWriter(file, fieldnames=fields, extrasaction='ignore')
                writer.writeheader()
                writer.writerows(rows)
        args = [sys.executable, str(SCRIPT), '--manifest', str(self.manifest), '--ratings', str(self.ratings)]
        if require:
            args.append('--require-complete')
        proc = subprocess.run(args, capture_output=True, text=True)
        self.assertTrue(proc.stdout.startswith('{'), proc.stderr)
        result = json.loads(proc.stdout)
        self.assertEqual(0 if result['status'] == 'COMPLETE' or not require else 1, proc.returncode)
        return result

    def test_audit_counterexample_fails_both_quality_and_time(self):
        result = self.run_study(full_rows('7', '60'))
        self.assertEqual('FAILED', result['status'])
        reasons = ' '.join(result['reasons']).lower()
        self.assertIn('quality', reasons)
        self.assertIn('time', reasons)
        self.assertEqual(-1, result['summary']['qualityDifference'])
        self.assertEqual(-0.5, result['summary']['timeReduction'])

    def test_exact_thresholds_and_just_outside(self):
        for quality, minutes, expected in [('7.8', '32', 'COMPLETE'),
                                            ('7.79999', '32', 'FAILED'),
                                            ('7.8', '32.00001', 'FAILED')]:
            with self.subTest(quality=quality, minutes=minutes):
                self.assertEqual(expected, self.run_study(full_rows(quality, minutes))['status'])

    def test_missing_empty_and_insufficient_readers(self):
        for rows in (None, [], [r for r in full_rows() if r['readerId'] != 'r3']):
            with self.subTest(rows='missing' if rows is None else len(rows)):
                result = self.run_study(rows)
                self.assertEqual('INCOMPLETE', result['status'])
                self.assertTrue(result['reasons'])

    def test_only_explicit_human_metadata_is_eligible(self):
        for kind in ('LLM-as-judge', '', 'robot'):
            with self.subTest(kind=kind):
                self.manifest.write_text(json.dumps({'evaluatorType': kind}))
                self.assertEqual('INCOMPLETE', self.run_study(full_rows())['status'])

    def test_invalid_manifest_is_incomplete(self):
        self.manifest.write_text('{broken')
        self.assertEqual('INCOMPLETE', self.run_study(full_rows())['status'])

    def test_severe_errors_required_and_no_default_zero(self):
        self.assertEqual('INCOMPLETE', self.run_study(full_rows(), FIELDS[:-1])['status'])
        rows = full_rows()
        rows[0]['severeContinuityErrors'] = ''
        self.assertEqual('INCOMPLETE', self.run_study(rows)['status'])

    def test_severe_error_increase_is_not_hidden_by_other_genre(self):
        rows = full_rows()
        for r in rows:
            if (r['genre'], r['condition'], r['chapter']) in [('mystery', 'collaboration', 1), ('wuxia', 'solo', 1)]:
                r['severeContinuityErrors'] = 1
        result = self.run_study(rows)
        self.assertEqual('FAILED', result['status'])
        self.assertEqual(1, result['summary']['collaborationSevereErrors'])
        self.assertEqual(1, result['summary']['soloSevereErrors'])
        self.assertIn('mystery', ' '.join(result['reasons']))

    def test_invalid_rows_are_rejected_instead_of_weighted_or_ignored(self):
        mutations = [('readerId', ''), ('readerId', ' '), ('overall', 'NaN'), ('hook', 'Infinity'),
                     ('coherence', '-Infinity'), ('engagement', ''), ('character', 11),
                     ('authorMinutes', 'NaN'), ('authorMinutes', ''), ('authorMinutes', -1),
                     ('severeContinuityErrors', '0.5'), ('severeContinuityErrors', -1),
                     ('genre', 'unknown'), ('condition', 'other'), ('chapter', 21), ('chapter', '1.5')]
        for field, value in mutations:
            with self.subTest(field=field, value=value):
                rows = full_rows()
                rows[0][field] = value
                self.assertEqual('INCOMPLETE', self.run_study(rows)['status'])
        rows = full_rows()
        rows.append(dict(rows[0]))
        self.assertEqual('INCOMPLETE', self.run_study(rows)['status'])

    def test_conflicting_chapter_time_or_error_count_is_incomplete(self):
        for field in ('authorMinutes', 'severeContinuityErrors'):
            with self.subTest(field=field):
                rows = full_rows()
                rows[0][field] = 1
                self.assertEqual('INCOMPLETE', self.run_study(rows)['status'])

    def test_median_time_cannot_be_replaced_by_mean(self):
        rows = full_rows(minutes='60')
        for r in rows:
            if r['condition'] == 'solo' and r['chapter'] == 20:
                r['authorMinutes'] = 4000
        result = self.run_study(rows)
        self.assertEqual('FAILED', result['status'])
        self.assertEqual(40, result['summary']['soloMedianMinutes'])
        self.assertEqual(-0.5, result['summary']['timeReduction'])

    def test_zero_control_median_is_incomplete(self):
        rows = full_rows()
        for r in rows:
            if r['condition'] == 'solo':
                r['authorMinutes'] = 0
        self.assertEqual('INCOMPLETE', self.run_study(rows)['status'])

    def test_chapter_means_have_equal_weight_and_genres_remain_visible(self):
        rows = full_rows()
        # A fourth reader changes one chapter mean by 0.5, not the weight of that chapter.
        extra = dict(rows[0], readerId='r4', overall='10')
        rows.append(extra)
        result = self.run_study(rows)
        self.assertEqual('COMPLETE', result['status'])
        self.assertAlmostEqual(0.5 / 40, result['summary']['qualityDifference'])
        self.assertAlmostEqual(0.5 / 20, result['genres']['mystery']['qualityDifference'])
        self.assertEqual(0, result['genres']['wuxia']['qualityDifference'])
        self.assertEqual(80, result['summary']['genreConditionChapterCells'])

    def test_bootstrap_is_paired_stratified_and_reproducible(self):
        rows = full_rows()
        for r in rows:
            base = Decimal(7) + Decimal(r['chapter']) / 20
            delta = Decimal('-0.2') if r['genre'] == 'mystery' else Decimal('0.2')
            r['overall'] = str(base + delta if r['condition'] == 'collaboration' else base)
            r['authorMinutes'] = str(Decimal(r['chapter'] + 20) * (Decimal('0.8') if r['condition'] == 'collaboration' else 1))
            r['severeContinuityErrors'] = 1
        first = self.run_study(rows)
        second = self.run_study(list(reversed(rows)))
        self.assertEqual(first, second)
        method = first['bootstrap']
        self.assertEqual(20260917, method['seed'])
        self.assertEqual(10000, method['samples'])
        for summary in [first['summary'], *first['genres'].values()]:
            for metric in ('qualityDifference', 'timeReduction', 'severeErrorDifference'):
                ci = summary['confidenceIntervals'][metric]
                self.assertEqual(10000, ci['validSamples'])
                self.assertAlmostEqual(summary[metric], ci['lower'])
                self.assertAlmostEqual(summary[metric], ci['upper'])
        self.assertEqual('COMPLETE', first['status'])

    def test_informational_cli_still_reports_failure_without_nonzero_exit(self):
        self.assertEqual('FAILED', self.run_study(full_rows('7', '60'), require=False)['status'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
