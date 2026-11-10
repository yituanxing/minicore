"""Fail-closed static contract for the PR379-only 15012M formal V6 proposal.

No GitHub event is sent and no Product RTL guest cycle is executed here.
"""
from pathlib import Path
import json
import os
import re
import subprocess
import tempfile
import textwrap
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
            reviewed = [s for s in steps if s.get('with', {}).get('path') == 'v6-formal-reviewed-tools']
            self.assertEqual(len(reviewed), 1)
            self.assertEqual(reviewed[0]['with']['ref'], '${{ env.V6_FORMAL_EXECUTOR_HEAD }}')
            imported_steps = [s for s in steps if s.get('name', '').startswith('Import immutable')]
            self.assertEqual(len(imported_steps), 1)
            imported = imported_steps[0]['run']
            self.assertIn('product_sd1_v6_formal_pr379_guard.sh', imported)
            self.assertIn('git -C "$reviewed" rev-parse "HEAD:$path"', imported)
            self.assertIn('bash tools/ci/product_sd1_v6_formal_pr379_guard.sh', imported)

    def test_duplicate_formal_run_guard_with_mocked_github_history(self):
        first = self.data['jobs']['v6_billion_1']['steps']
        self.assertEqual(first[0]['name'], 'Reject duplicate formal RTL runs before guest execution')
        guard = first[0]['run']
        self.assertIn('gh api --paginate', guard)
        self.assertIn('jobs?filter=all&per_page=100', guard)
        self.assertIn('prior formal guest run', guard)
        self.assertIn('PRODUCT_SD1_V6_FORMAL_FIRST_RUN_ONLY_VERIFIED', guard)
        self.assertIn('github.token', first[0]['env']['GH_TOKEN'])
        for i in range(2, 6):
            self.assertFalse(any('duplicate formal RTL' in s.get('name', '')
                                 for s in self.data['jobs'][f'v6_billion_{i}']['steps']))

        fake_gh = textwrap.dedent('''\
            #!/usr/bin/env python3
            import json, os, sys
            url = sys.argv[-1]
            case = os.environ.get('V6_GH_MOCK_CASE')
            head = 'a' * 40
            if case == 'api_fail':
                sys.exit(1)
            if url.endswith('/actions/runs/999'):
                data = dict(id=999, workflow_id=42,
                            run_number=11 if case == 'self_mismatch' else 12,
                            event='pull_request', head_sha=head)
            elif '/actions/workflows/42/runs?' in url:
                current = dict(id=999, workflow_id=42,
                               run_number=12, head_sha=head)
                runs = [] if case in ('history_empty', 'self_missing') else [current]
                if case == 'self_duplicate':
                    runs.append(current.copy())
                if case not in ('fresh', 'same_run_attempt', 'history_empty',
                                'self_duplicate'):
                    runs.append(dict(id=998, workflow_id=42, run_number=11,
                                     head_sha='b' * 40 if case == 'other_head' else head))
                data = dict(total_count=len(runs), workflow_runs=runs)
                if case == 'history_incomplete':
                    data['total_count'] += 1
            elif '/actions/runs/998/jobs?' in url:
                if 'filter=all&per_page=100' not in url:
                    sys.exit(3)
                status = 'in_progress' if case == 'duplicate_running' else 'completed'
                conclusions = dict(duplicate_success='success',
                                   duplicate_failed='failure',
                                   duplicate_cancelled='cancelled',
                                   duplicate_running=None,
                                   missing_jobs='skipped')
                name = ('Wrong job' if case == 'missing_jobs' else
                        'Genuine RTL 15012 to 15992M, 49 previously unexecuted 20M stages')
                data = dict(jobs=[dict(name=name, status=status,
                                       conclusion=conclusions.get(case, 'skipped'))])
                if case in ('earlier_success_latest_skipped',
                            'earlier_failed_latest_skipped', 'all_skipped_attempts'):
                    old = ('success' if case == 'earlier_success_latest_skipped' else
                           'failure' if case == 'earlier_failed_latest_skipped' else 'skipped')
                    data['jobs'] = [dict(name=name, status='completed', conclusion=old),
                                    dict(name=name, status='completed', conclusion='skipped')]
                data['total_count'] = len(data['jobs'])
                if case == 'jobs_incomplete':
                    data['total_count'] += 1
            else:
                sys.exit(2)
            print(json.dumps(data))
        ''')
        with tempfile.TemporaryDirectory() as temp:
            mock = Path(temp) / 'gh'
            mock.write_text(fake_gh)
            mock.chmod(0o755)
            env = dict(os.environ, PATH=f"{temp}:{os.environ['PATH']}",
                       GITHUB_REPOSITORY='yituanxing/minicore',
                       GITHUB_EVENT_NAME='pull_request',
                       GITHUB_RUN_ID='999', GITHUB_RUN_NUMBER='12',
                       V6_SOURCE_SHA='a' * 40)
            for case, accepted in (
                ('fresh', True), ('skipped', True), ('other_head', True),
                ('same_run_attempt', True), ('all_skipped_attempts', True),
                ('duplicate_success', False), ('duplicate_failed', False),
                ('earlier_success_latest_skipped', False),
                ('earlier_failed_latest_skipped', False),
                ('duplicate_cancelled', False), ('duplicate_running', False),
                ('missing_jobs', False), ('history_incomplete', False),
                ('history_empty', False), ('self_missing', False),
                ('self_duplicate', False), ('jobs_incomplete', False),
                ('self_mismatch', False), ('api_fail', False),
            ):
                result = subprocess.run(['bash', '-c', guard],
                                        env=dict(env, V6_GH_MOCK_CASE=case),
                                        text=True, capture_output=True,
                                        timeout=30, check=False)
                self.assertEqual(result.returncode == 0, accepted,
                                 (case, result.stdout, result.stderr))

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

    def test_final_proof_is_inert_and_handles_early_skipped_needs(self):
        final = self.data['jobs']['final_genuine_rtl_proof']
        # The release must remove only `false &&`: a status function avoids
        # implicit success(), and !cancelled() respects an aborted workflow.
        self.assertEqual(final['if'].strip(), 'false && !cancelled()')
        self.assertEqual(
            final['needs'],
            [f'v6_billion_{i}' for i in range(1, 6)],
        )
        script = final['steps'][0]['run']
        proof = re.search(r"jq -e '\s*(.*?)\s*'\s*<<<", script, re.S)
        self.assertIsNotNone(proof)

        def job(result, login='0', terminal=''):
            return {
                'result': result,
                'outputs': {'serial_login_verified': login, 'terminal_m': terminal},
            }

        early = {
            'v6_billion_1': job('success', login='1', terminal='15032'),
            **{f'v6_billion_{i}': job('skipped') for i in range(2, 6)},
        }
        complete = {
            f'v6_billion_{i}': job('success', terminal=str(14992 + 1000 * i))
            for i in range(1, 6)
        }
        complete['v6_billion_5']['outputs']['terminal_m'] = '19992'
        failed = dict(early)
        failed['v6_billion_2'] = job('failure')
        cancelled = dict(early)
        cancelled['v6_billion_2'] = job('cancelled')
        no_login = dict(early)
        no_login['v6_billion_1'] = job('success', terminal='15992')

        for case, accepted in ((early, True), (complete, True),
                               (failed, False), (cancelled, False),
                               (no_login, False)):
            result = subprocess.run(
                ['jq', '-e', proof.group(1)],
                input=json.dumps(case), text=True, capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode == 0, accepted, case)


if __name__ == '__main__':
    unittest.main()
