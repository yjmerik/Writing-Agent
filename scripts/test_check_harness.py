"""Negative contract tests for the repository harness (stdlib only)."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys
sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location('check_harness', Path(__file__).with_name('check-harness.py'))
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in ['AGENTS.md', 'init.sh', 'docs/specs/TEMPLATE.md', 'docs/specs/example.md']:
            self.write(name, 'content')
        for name in ['progress.md', 'session-handoff.md']:
            self.write(name, '2026-09-07\n## Current\n## Files\n## Verification\n## Blockers\n## Next Steps\n')
        self.feature = dict(id='p31-01', name='Example', description='Scope', phase=31,
                            dependencies=[], files=['init.sh'], status='in-progress',
                            acceptance='command exits 0', verify_cmd='true', evidence='pending',
                            spec='docs/specs/example.md')
        self.data = dict(schema_version=2, project='test', current_phase=31,
                         updated_at='2026-09-07', active_feature='p31-01', features=[self.feature])
        self.records = dict(schema_version=1, records=[])

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def errors(self, git=False):
        self.write('feature_list.json', json.dumps(self.data))
        self.write('docs/harness/completions.json', json.dumps(self.records))
        return harness.audit(self.root, check_git=git)[0]

    def test_valid_active_feature(self):
        self.assertEqual([], self.errors())

    def test_duplicate_id(self):
        self.data['features'].append(copy.deepcopy(self.feature))
        self.assertTrue(any('duplicate' in e for e in self.errors()))

    def test_missing_dependency_and_cycle(self):
        self.feature['dependencies'] = ['missing']
        self.assertTrue(any('dependency' in e for e in self.errors()))
        self.feature['dependencies'] = ['p31-01']
        self.assertTrue(any('cycle' in e for e in self.errors()))

    def test_multiple_active_features(self):
        other = copy.deepcopy(self.feature)
        other['id'] = 'p31-02'
        self.data['features'].append(other)
        self.assertTrue(any('active' in e for e in self.errors()))

    def test_unmerged_work_cannot_be_completed(self):
        self.feature.update(status='completed', delivery_state='local-unmerged')
        self.data['active_feature'] = None
        self.assertTrue(any('local-unmerged' in e for e in self.errors()))

    def test_deferred_requires_reason_and_is_not_active(self):
        self.feature['status'] = 'deferred'
        self.data['active_feature'] = None
        self.assertTrue(any('deferred_reason' in e for e in self.errors()))
        self.feature['deferred_reason'] = 'existing local work'
        self.assertEqual([], self.errors())

    def complete(self):
        self.feature.update(status='completed', completion_record='completion')
        self.data['active_feature'] = None
        self.records['records'] = [dict(id='completion', kind='verified', feature_ids=['p31-01'],
                                       branch='codex/p31-01-example', implementation_commit='a'*40,
                                       main_commit='a'*40, evidence='docs/harness/evidence/test.json')]
        self.write('docs/harness/evidence/test.json', json.dumps(dict(
            commit='a'*40, cleanCheckout=True,
            checks=[dict(command='mvn compile && mvn test', exitCode=0, result='PASS')])) )

    def test_completed_requires_record(self):
        self.feature['status'] = 'completed'
        self.data['active_feature'] = None
        self.assertTrue(any('completion_record' in e for e in self.errors()))

    def test_missing_or_failed_evidence(self):
        self.complete()
        self.assertEqual([], self.errors())
        self.write('docs/harness/evidence/test.json', json.dumps(dict(
            commit='a'*40, cleanCheckout=True,
            checks=[dict(command='mvn test', exitCode=1, result='FAIL')])) )
        self.assertTrue(any('exitCode' in e for e in self.errors()))
        (self.root / 'docs/harness/evidence/test.json').unlink()
        self.assertTrue(any('evidence' in e for e in self.errors()))

    def test_new_feature_cannot_claim_legacy_completion(self):
        self.complete()
        self.records['records'][0]['kind'] = 'historical'
        self.assertTrue(any('historical' in e for e in self.errors()))

    def test_missing_spec_and_stale_handoff(self):
        self.feature['spec'] = 'docs/specs/missing.md'
        self.write('session-handoff.md', 'old handoff')
        errors = self.errors()
        self.assertTrue(any('spec' in e for e in errors))
        self.assertTrue(any('session-handoff' in e for e in errors))

    def test_git_rejects_unmerged_implementation(self):
        self.complete()
        def git(*args):
            return subprocess.check_output(['git', '-C', str(self.root), *args], text=True).strip()
        git('init', '-q', '-b', 'main')
        git('-c', 'user.name=Harness Test', '-c', 'user.email=harness@example.invalid',
            'commit', '-q', '--allow-empty', '-m', 'baseline')
        main = git('rev-parse', 'HEAD')
        git('checkout', '-q', '-b', 'codex/p31-01-example')
        git('-c', 'user.name=Harness Test', '-c', 'user.email=harness@example.invalid',
            'commit', '-q', '--allow-empty', '-m', 'feature')
        implementation = git('rev-parse', 'HEAD')
        record = self.records['records'][0]
        record.update(implementation_commit=implementation, main_commit=main)
        self.assertTrue(any('ancestor' in e for e in self.errors(git=True)))


if __name__ == '__main__':
    unittest.main()
