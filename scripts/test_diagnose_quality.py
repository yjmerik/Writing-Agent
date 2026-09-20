"""Run the real diagnostic script against isolated event streams."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT=Path(__file__).resolve().with_name('diagnose.sh')
class DiagnosisTest(unittest.TestCase):
    def diagnose(self, events, project=None):
        with tempfile.NamedTemporaryFile(mode='w', suffix='.jsonl') as f:
            for e in events: f.write(json.dumps(dict(ts='2026-09-20',projectId='p',**e))+'\n')
            f.flush()
            return subprocess.check_output(['bash',str(SCRIPT),f.name]+(['--project',project] if project else []),text=True)
    def test_each_quality_blocker_without_llm_error_requires_review(self):
        for e in [dict(event='CONSISTENCY_ISSUE',severity='HIGH'),dict(event='CONSISTENCY_ISSUE',severity='DETECT_FAILED'),dict(event='REVIEW_BARRIER_RAISED'),dict(event='WRITING_PAUSED'),dict(event='QUALITY_CIRCUIT_BREAKER'),dict(event='FINAL_GATE',passed=False),dict(event='QUALITY_REVIEW',passed=False)]:
            with self.subTest(event=e):
                out=self.diagnose([e])
                self.assertNotIn('无需修复动作',out)
                self.assertIn('人工复核',out)
                self.assertIn('质量',out)
    def test_clean_sample(self):
        out=self.diagnose([dict(event='LLM_CALL'),dict(event='QUALITY_REVIEW',passed=True)])
        self.assertIn('无需修复动作',out)
    def test_operational_and_quality_failures_are_both_reported(self):
        out=self.diagnose([dict(event='LLM_ERROR',errorClass='Timeout'),dict(event='REVIEW_BARRIER_RAISED')])
        self.assertIn('最高频失败',out)
        self.assertIn('人工复核',out)
    def test_project_filter_excludes_unrelated_blockers(self):
        out=self.diagnose([dict(event='REVIEW_BARRIER_RAISED')], 'other')
        self.assertNotIn('人工复核',out)

if __name__=='__main__': unittest.main()
