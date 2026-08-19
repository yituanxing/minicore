from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "linux-frontier.yml"
INPUT_CHECK = ROOT / "tools" / "ci" / "l32_linux_frontier_input.sh"


class LinuxFrontierContractTest(unittest.TestCase):
    def test_frontier_is_the_hardware_first_failure_lane(self) -> None:
        text = WORKFLOW.read_text()
        self.assertIn("name: Linux Frontier First", text)
        self.assertIn("- 'src/main/scala/aethercore/**'", text)
        self.assertIn("cancel-in-progress: true", text)
        self.assertIn("TARGET_SHA: ${{ github.event.pull_request.head.sha || github.sha }}", text)
        self.assertIn("Run Linux frontier and stop at first requested milestone", text)
        self.assertIn('MILESTONE="$FRONTIER_MILESTONE"', text)
        self.assertIn('MAX_CYCLES="$FRONTIER_MAX_CYCLES"', text)
        self.assertIn("PROGRESS_INTERVAL_CYCLES=0", text)

    def test_linux_behavior_runs_before_frontier_regression_contract(self) -> None:
        text = WORKFLOW.read_text()
        linux = text.index("Run Linux frontier and stop at first requested milestone")
        regression = text.index("Validate frontier contracts after Linux passes")
        self.assertLess(linux, regression)

    def test_frontier_uses_the_deep_frozen_rv32imac_profile(self) -> None:
        text = WORKFLOW.read_text()
        self.assertIn("AETHERCORE_L32_USERSPACE_PROFILE: 'rv32imac'", text)
        self.assertIn("TOP=AetherCoreOpenSbiCSimTop", text)
        self.assertIn("ELABORATE_MAIN=aethercore.ElaborateOpenSbiC", text)
        self.assertIn("-DAETHERCORE_L32_C_TOP", text)
        self.assertIn("REQUIRE_LAYERED_COMPRESSED=1", text)
        self.assertIn("build/l32-busybox-shell-boot-rv32imac/opensbi/platform/generic/firmware/fw_payload.bin", text)
        self.assertIn("L32_LAYERED_COMPRESSED_PASS", text)

    def test_frontier_consumes_only_prequalified_software(self) -> None:
        text = WORKFLOW.read_text()
        self.assertIn("Require frozen qualified RV32IMAC Linux payload", text)
        self.assertIn("tools/ci/l32_linux_frontier_input.sh", text)
        self.assertNotIn("l32_linux_build.sh", text)
        self.assertNotIn("l32_linux_payload_build.sh", text)
        self.assertNotIn("l32_busybox_build.sh", text)
        self.assertNotIn("l32_rv32c_kernel_build.sh", text)
        self.assertNotIn("actions/upload-artifact", text)

    def test_frontier_input_is_fail_closed_on_deep_freeze_identity(self) -> None:
        text = INPUT_CHECK.read_text()
        self.assertIn("AETHERCORE_L32_USERSPACE_PROFILE", text)
        self.assertIn("frontier-profile-must-be-rv32imac", text)
        self.assertIn("l32_busybox_runtime_freeze.sh", text)
        self.assertIn("L32_BUSYBOX_RUNTIME_FREEZE: status=PASS", text)
        self.assertIn("profile=rv32imac", text)
        self.assertIn("require_c=1", text)
        self.assertIn("firmware-sha", text)
        self.assertIn("linux-sha", text)
        self.assertIn("L32_LINUX_FRONTIER_INPUT: status=MISS", text)
        self.assertIn("L32_LINUX_FRONTIER_INPUT: status=PASS", text)

    def test_success_path_keeps_evidence_in_logs_not_artifacts(self) -> None:
        text = WORKFLOW.read_text()
        self.assertIn("tail -n 200", text)
        self.assertIn("LINUX_FRONTIER_RESULT: status=FAIL", text)
        self.assertIn("LINUX_FRONTIER_RESULT: status=PASS", text)
        self.assertIn("success artifacts: none", text)
        self.assertNotIn("retention-days", text)


if __name__ == "__main__":
    unittest.main()
