from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "ci" / "zephyr_z3_timer_schedule.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "zephyr-stage-push.yml"


class ZephyrZ3ContractTest(unittest.TestCase):
    def test_timer_schedule_matrix_is_exact_and_fail_closed(self) -> None:
        text = SCRIPT.read_text(encoding="utf-8")

        for token in (
            'STALL_PERIODS="${AETHERCORE_ZEPHYR_Z3_STALL_PERIODS:-0 2 3 5 7}"',
            '--self-check-exit',
            'AETHERCORE ZEPHYR MAIN give=$step',
            'AETHERCORE ZEPHYR WORKER step=$step',
            'commits" != "$reference_commits',
            'negative-timeout.log',
            'FAIL: timeout after',
            'contract=zephyr-v3.7.2-aethercore-z3-timer-scheduling-v1',
            'stop_on_wfi=false',
        ):
            self.assertIn(token, text)

        self.assertIn('main_count" != "1" || "$worker_count" != "1', text)
        self.assertIn('negative_rc -eq 0', text)
        self.assertIn('AETHERCORE ZEPHYR PASS handoffs=4', text)
        self.assertIn('PASS: self-check exit=0', text)

    def test_single_cached_slot_runs_and_archives_z3(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")

        for token in (
            'group: zephyr-stage-slot-global',
            'cancel-in-progress: true',
            'runs-on: [self-hosted, Linux, X64, minicore]',
            'bash tools/ci/zephyr_z3_timer_schedule.sh',
            'minicore/build/zephyr-z3/evidence/',
            'Cached Zephyr Z1/Z2/Z3 stage passed',
        ):
            self.assertIn(token, text)

        self.assertEqual(text.count('runs-on: [self-hosted, Linux, X64, minicore]'), 1)
        self.assertNotIn('.github/full-gate-request', text)
        self.assertNotIn('Fast Gate', text)


if __name__ == "__main__":
    unittest.main()
