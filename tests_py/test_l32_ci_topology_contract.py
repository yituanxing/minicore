from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"


def pull_request_paths(path: Path) -> list[str]:
    text = path.read_text()
    marker = "  pull_request:\n    paths:\n"
    if marker not in text:
        return []
    # Stop at the next top-level trigger/key rather than assuming a specific
    # trigger ordering.
    block = text.split(marker, 1)[1]
    for next_key in ("  push:\n", "  workflow_dispatch:\n", "  workflow_call:\n"):
        if next_key in block:
            block = block.split(next_key, 1)[0]
    paths = []
    for line in block.splitlines():
        if line.startswith("      - "):
            paths.append(line[len("      - ") :].strip().strip("'\""))
    return paths


class L32CiTopologyContractTest(unittest.TestCase):
    def test_current_public_pr_signoff_lanes_are_hosted(self):
        # The public repository no longer treats the historical deep L32
        # self-hosted BusyBox lane as the canonical PR hardware owner. Fast
        # Gate plus the currently targeted hosted real-system lanes own normal
        # PR qualification.
        for filename in (
            "fast-gate.yml",
            "l32-linux-build.yml",
            "l32-linux-handoff.yml",
            "rv64-minimal-initramfs-v1.yml",
        ):
            with self.subTest(workflow=filename):
                text = (WORKFLOWS / filename).read_text()
                self.assertIn("pull_request:", text)
                self.assertIn("runs-on: ubuntu-24.04", text)
                self.assertNotIn("runs-on: [self-hosted, Linux, X64, minicore]", text)

        # Fast Gate deliberately runs on every PR and classifies changed paths
        # inside a small hosted job. Pure v2 edits own a short stage-local lane;
        # shared Scala still selects compatibility and software lanes.
        fast = (WORKFLOWS / "fast-gate.yml").read_text()
        self.assertIn("Classify fast-gate paths", fast)
        self.assertIn("run_v2: ${{ steps.changes.outputs.run_v2 }}", fast)
        self.assertIn("run_legacy_chisel: ${{ steps.changes.outputs.run_legacy_chisel }}", fast)
        self.assertIn("run_smode_v1: ${{ steps.changes.outputs.run_smode_v1 }}", fast)
        self.assertIn("run_freertos: ${{ steps.changes.outputs.run_freertos }}", fast)
        self.assertIn(
            "^(src/main/scala/aethercore/core/v2/|src/test/scala/aethercore/V2[^/]*\\.scala$)",
            fast,
        )
        self.assertIn("V2 focused development gate", fast)
        self.assertIn("Legacy/shared Chisel compatibility", fast)
        self.assertIn("Supervisor and FreeRTOS compatibility", fast)
        self.assertIn("run_hardware=true", fast)
        self.assertIn("needs: classify\n    if: needs.classify.outputs.run_hardware == 'true'", fast)
        self.assertNotIn("AETHERCORE_MILL_NO_DAEMON", fast)

        pid1 = (WORKFLOWS / "rv64-minimal-initramfs-v1.yml").read_text()
        self.assertIn("Run real RV64 PID 1 UART interrupt proof", pid1)
        self.assertIn('MILESTONE="RV64 USER UART IRQ OK"', pid1)
        self.assertIn("MIN_STIP=1", pid1)
        self.assertIn("MIN_SEIP=1", pid1)

    def test_legacy_l32_prefix_milestones_are_manual_or_dedicated_branch_only(self):
        for filename in (
            "l32-linux-boot.yml",
            "l32-busybox-build.yml",
        ):
            with self.subTest(workflow=filename):
                text = (WORKFLOWS / filename).read_text()
                self.assertIn("workflow_dispatch:", text)
                self.assertNotIn("pull_request:", text)
                self.assertIn("runs-on: [self-hosted, Linux, X64, minicore]", text)

        deeper = (WORKFLOWS / "l32-linux-deeper-boot.yml").read_text()
        self.assertIn("workflow_dispatch:", deeper)
        self.assertNotIn("pull_request:", deeper)
        self.assertIn("push:", deeper)
        self.assertIn("explore/rv64-linux-deeper-v1", deeper)
        self.assertIn("runs-on: ubuntu-24.04", deeper)

    def test_public_pull_requests_can_never_target_a_self_hosted_runner(self):
        offenders = []
        for path in sorted(WORKFLOWS.glob("*.yml")):
            text = path.read_text()
            if "pull_request:" in text and "self-hosted" in text:
                offenders.append(path.name)
        self.assertEqual(
            offenders,
            [],
            "public pull_request workflows must never execute on self-hosted runners; "
            f"manualize or migrate: {offenders}",
        )

    def test_kernel_init_remains_historical_manual_self_validation(self):
        path = WORKFLOWS / "l32-linux-kernel-init.yml"
        text = path.read_text()
        self.assertIn("workflow_dispatch:", text)
        self.assertNotIn("pull_request:", text)
        self.assertIn("runs-on: [self-hosted, Linux, X64, minicore]", text)


if __name__ == "__main__":
    unittest.main()
