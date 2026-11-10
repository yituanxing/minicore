"""Fail-closed static contract for the PR379-only 15012M formal V6 proposal.

No GitHub event is sent and no Product RTL guest cycle is executed here.
"""
from pathlib import Path
import re
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/product-sd1-v6-20b-needs-15012-to-19992-PR379-INERT-DRAFT.yml'


class V6FormalInertContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.text)

    def test_all_jobs_truly_inert(self):
        for name, job in self.data['jobs'].items():
            self.assertTrue(job['if'] is False or str(job['if']).strip().startswith('false'), name)
        self.assertEqual(self.data['permissions'], {'contents': 'read', 'actions': 'read'})
        self.assertEqual(self.data["on"]['pull_request']['types'], ['labeled'])

    def test_249_only_new_20m_stages(self):
        ends = [15992, 16992, 17992, 18992, 19992]
        starts = [15012] + ends[:-1]
        jobs = [self.data['jobs'][f'v6_billion_{i}'] for i in range(1, 6)]
        total = 0
        for i, (job, start, end) in enumerate(zip(jobs, starts, ends), 1):
            actions = [s for s in job['steps'] if s.get('uses') == './.github/actions/product-sd1-v6-batch']
            self.assertEqual(len(actions), 1, i)
            inputs = actions[0]['with']
            self.assertEqual((int(inputs['start_m']), int(inputs['end_m'])), (start, end))
            self.assertEqual(int(inputs['stage_count']), (end - start) // 20)
            self.assertEqual(inputs['source_mode'], 'technical_terminal' if i == 1 else 'intra_run')
            self.assertEqual(len([s for s in job['steps'] if 'cache/restore' in s.get('uses', '')]), 0)
            self.assertEqual(len([s for s in job['steps'] if s.get('name', '').startswith('Import immutable')]), 1)
            total += int(inputs['stage_count'])
        self.assertEqual(total, 249)
        self.assertNotIn("start_m: '14992'", self.text)

    def test_identity_and_source_pins(self):
        e = self.data['env']
        self.assertEqual(e['V6_FORMAL_PARENT_HEAD'], 'd1a13ac76b09e00b0298e69bb95217e5197d235f')
        self.assertEqual(e['V6_TECH_QUAL_EXECUTOR_HEAD'], 'ffa59df56fa88611eaf727bbd96909fae748ebd2')
        self.assertEqual(e['V6_FORMAL_EXECUTOR_HEAD'], '6a3227180b58b19945bbb49189f5c1d7538b683f')
        self.assertEqual(e['V6_TECH_QUAL_ID'], '13742299835')
        self.assertEqual(e['V6_TECH_TERMINAL_ID'], '13742384494')
        self.assertEqual(e['V6_QUAL_ID'], '13742896253')
        self.assertEqual(e['V6_MODEL_SHA'], '3b22f54ee7017b605c7fafe86ca3e21fcdb7d3945e93e329b10c62fa39806101')
        self.assertNotEqual(e['V6_FORMAL_PARENT_HEAD'], e['V6_TECH_QUAL_EXECUTOR_HEAD'])
        self.assertTrue(str(e['AETHERCORE_SD1_V6_20B_APPROVED']).startswith('PENDING_'))
        self.assertTrue(str(e['AETHERCORE_SD1_RELAY_CONFIRM']).startswith('PENDING_'))

    def test_reviewed_import_and_scope_per_job(self):
        batch_action = (ROOT / '.github/actions/product-sd1-v6-batch/action.yml').read_text()
        self.assertIn('actions/cache/restore@v4', batch_action)
        self.assertIn("steps.diag.outputs.cache-hit", batch_action)
        self.assertIn("steps.seed.outputs.cache-hit", batch_action)
        self.assertIn("if: inputs.stage_count == '50'", batch_action)
        assert "stage_count:" in batch_action
        for i in range(1, 6):
            steps = self.data['jobs'][f'v6_billion_{i}']['steps']
            self.assertEqual(steps[1]['with']['ref'], '${{ env.V6_FORMAL_EXECUTOR_HEAD }}')
            imported = steps[2]['run']
            self.assertIn('product_sd1_v6_formal_pr379_guard.sh', imported)
            self.assertIn('git -C "$reviewed" rev-parse "HEAD:$path"', imported)
            self.assertIn('bash tools/ci/product_sd1_v6_formal_pr379_guard.sh', imported)

    def test_guard_does_not_collapse_executor_and_parent(self):
        guard = (ROOT / 'tools/ci/product_sd1_v6_formal_pr379_guard.sh').read_text()
        self.assertIn('V6_TECH_QUAL_EXECUTOR_HEAD', guard)
        self.assertIn('V6_FORMAL_PARENT_HEAD', guard)
        self.assertIn('git rev-parse HEAD^', guard)
        self.assertIn('PRODUCT_SD1_V6_FORMAL_PR379_REJECTED', guard)
        self.assertIn('terminal_90d_artifact_zip_digest', guard)
        self.assertIn('host_model_diff_14952_to_14972_20m_verified', guard)
        self.assertIn('"${signed_diff[0]}" == .github/workflows/product-sd1-v6-20b-needs-', guard)
        self.assertIn('"${signed_diff[1]}" == .github/workflows/product-sd1-v6-20m-qualification-', guard)

    def test_terminal_archive_is_not_v5_14992(self):
        prepare = (ROOT / 'tools/ci/product_sd1_v6_chain_prepare.sh').read_text()
        self.assertIn('technical_terminal', prepare)
        self.assertIn('source_run="$V6_TECH_QUAL_RUN"', prepare)
        self.assertIn('15012M technical terminal has changed 14992M V5/V6 lineage', prepare)
        self.assertIn('13742384494', prepare)
        self.assertIn('V6_TECH_QUAL_REPORT_VERIFIED', prepare)


if __name__ == '__main__':
    unittest.main()
