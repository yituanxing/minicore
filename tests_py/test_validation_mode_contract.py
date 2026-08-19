from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"
FRONTIER = WORKFLOWS / "linux-frontier.yml"
FAST = WORKFLOWS / "fast-gate.yml"
FULL = WORKFLOWS / "full-gate.yml"


class ValidationModeContractTest(unittest.TestCase):
    def test_daily_hardware_owner_is_linux_frontier(self) -> None:
        text = FRONTIER.read_text()
        self.assertIn("- 'src/main/scala/aethercore/**'", text)
        self.assertIn("Frozen Linux first-failure frontier", text)
        self.assertIn("Run Linux frontier and stop at first requested milestone", text)

    def test_fast_gate_is_explicit_milestone_only(self) -> None:
        text = FAST.read_text()
        pull_request_block = text.split("  pull_request:\n", 1)[1].split("  workflow_dispatch:", 1)[0]
        self.assertIn(".github/fast-gate-request", pull_request_block)
        self.assertIn(".github/workflows/fast-gate.yml", pull_request_block)
        self.assertNotIn("src/main/scala", pull_request_block)
        self.assertNotIn("tests_py", pull_request_block)
        self.assertIn('GITHUB_EVENT_NAME}" == "workflow_dispatch"', text)
        self.assertIn("^.github/fast-gate-request$".replace(".", "\\.", 1), text)
        self.assertIn("run_chisel=true", text)
        self.assertIn("run_smode_v1=true", text)
        self.assertIn("run_freertos=true", text)

    def test_full_gate_remains_explicit_freeze_only(self) -> None:
        text = FULL.read_text()
        pull_request_block = text.split("  pull_request:\n", 1)[1].split("  workflow_dispatch:", 1)[0]
        self.assertIn(".github/full-gate-request", pull_request_block)
        self.assertNotIn("src/main/scala", pull_request_block)
        self.assertIn("Shared build and complete verification", text)

    def test_request_markers_make_regression_intent_visible(self) -> None:
        fast_request = (ROOT / ".github" / "fast-gate-request").read_text()
        full_request = (ROOT / ".github" / "full-gate-request").read_text()
        self.assertIn("qualification=linux-frontier-first-policy-v1", fast_request)
        self.assertIn("scope=", fast_request)
        self.assertIn("qualification=", full_request)
        self.assertIn("scope=", full_request)


if __name__ == "__main__":
    unittest.main()
