"""Regression: partial/paused runs must never pass full acceptance."""
import copy
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

def accept(report, mode='full'):
    helper = ROOT / 'scripts/e2e_acceptance.py'
    if helper.exists():
        from e2e_acceptance import assess
        return assess(report, mode)[0]
    # Exercise the original production predicate before the fix.
    script = (ROOT / 'scripts/e2e.sh').read_text()
    predicate = script[script.index('ok = report['):script.index('sys.exit(0 if ok else 1)')]
    scope = {'report': report}
    exec(predicate, scope)
    return scope['ok']

class AcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.report = dict(totalChapters=3, requestedChapters=3, chaptersWritten=3,
            chapterFailures=0, llmErrors=0, taskStatus='COMPLETED', finalState='FINAL',
            activeReviewBarriers=0, pendingReviewChapters=0, unresolvedHighIssues=0)
    def test_current_quality_counts(self):
        from e2e_acceptance import quality_counts
        q=dict(chapterVersion=3,needsManualReview=False,qualityRisk=False,
               issues=[dict(chapterVersion=2,severity='HIGH',resolved=False),
                       dict(chapterVersion=3,severity='HIGH',resolved=False)])
        self.assertEqual(1, quality_counts(dict(todoList=[]),[q])['unresolvedHighIssues'])
        with self.assertRaises(KeyError): quality_counts({},[q])
    def test_complete_passes(self):
        self.assertTrue(accept(self.report))
    def test_real_paused_one_of_three_fails(self):
        self.report.update(totalChapters=1,chaptersWritten=1,taskStatus='PAUSED',activeReviewBarriers=4)
        self.assertFalse(accept(self.report))
        self.assertTrue(accept(self.report, 'smoke'))
    def test_nonterminal_and_failed_states_fail(self):
        for state in ['RUNNING','PENDING','CANCELLED','FAILED','PAUSED','?']:
            with self.subTest(state=state):
                self.report['taskStatus'] = state
                self.assertFalse(accept(self.report))
    def test_quality_evidence_cannot_be_missing_or_blocked(self):
        for field in ['activeReviewBarriers','pendingReviewChapters','unresolvedHighIssues']:
            for value in [None,1]:
                with self.subTest(field=field,value=value):
                    r=copy.copy(self.report);r[field]=value
                    self.assertFalse(accept(r))
    def test_partial_and_errors_fail(self):
        for field,value in [('totalChapters',1),('chaptersWritten',1),('requestedChapters',0),('llmErrors',1),('chapterFailures',1),('finalState','NEEDS_REVISION')]:
            with self.subTest(field=field):
                r=copy.copy(self.report);r[field]=value
                self.assertFalse(accept(r))

if __name__ == '__main__': unittest.main()
