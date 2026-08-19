from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "linux-frontier.yml"
PRODUCER_WORKFLOW = ROOT / ".github" / "workflows" / "rv32c-linux-userspace.yml"
INPUT_CHECK = ROOT / "tools" / "ci" / "l32_linux_frontier_input.sh"
ARTIFACT_HELPER = ROOT / "tools" / "ci" / "l32_linux_frontier_artifact.sh"
FREEZE_MANIFEST = ROOT / "software" / "l32" / "linux-frontier-freeze.env"


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
        self.assertIn('FW_BIN="$firmware_bin"', text)
        self.assertNotIn(
            'FW_BIN="$GITHUB_WORKSPACE/build/l32-busybox-shell-boot-rv32imac', text
        )
        self.assertIn("L32_LAYERED_COMPRESSED_PASS", text)

    def test_frontier_consumes_only_prequalified_software(self) -> None:
        workflow = WORKFLOW.read_text()
        input_text = INPUT_CHECK.read_text()
        helper = ARTIFACT_HELPER.read_text()
        self.assertIn("Require frozen qualified RV32IMAC Linux payload", workflow)
        self.assertIn("tools/ci/l32_linux_frontier_input.sh", workflow)
        self.assertIn("l32_linux_frontier_artifact.sh\" resolve", input_text)
        self.assertIn("CACHE_ROOT=", helper)
        self.assertIn("l32/linux-frontier/${profile}/${firmware_sha}", helper)
        for forbidden in (
            "l32_linux_build.sh",
            "l32_linux_payload_build.sh",
            "l32_busybox_build.sh",
            "l32_rv32c_kernel_build.sh",
        ):
            self.assertNotIn(forbidden, workflow)
            self.assertNotIn(forbidden, input_text)
            self.assertNotIn(forbidden, helper)
        self.assertNotIn("actions/upload-artifact", workflow)

    def test_repo_freeze_owns_identity_and_cache_only_owns_bytes(self) -> None:
        manifest = FREEZE_MANIFEST.read_text()
        helper = ARTIFACT_HELPER.read_text()
        self.assertIn("L32_LINUX_FRONTIER_FREEZE_VERSION=1", manifest)
        self.assertIn("L32_LINUX_FRONTIER_PROFILE=rv32imac", manifest)
        self.assertIn("L32_LINUX_FRONTIER_ISA=rv32imac_zicsr_zifencei", manifest)
        self.assertIn("L32_LINUX_FRONTIER_REQUIRE_C=1", manifest)
        self.assertIn(
            "L32_LINUX_FRONTIER_LINUX_IMAGE_SHA256=0dd94887333cd6ac4daa96a190f6cece8445848defc7a8c97a2290ae7d723a16",
            manifest,
        )
        self.assertIn(
            "L32_LINUX_FRONTIER_FIRMWARE_SHA256=33921ee4fc7e8f4f7b282adf9cea1c87b98e10e7dc373f25231e5e66eba86159",
            manifest,
        )
        self.assertIn("FREEZE_MANIFEST=", helper)
        self.assertIn('sha256sum "${CACHE_FIRMWARE}"', helper)
        self.assertIn("verify_cache || fail \"qualified-cache\"", helper)
        self.assertIn("L32_LINUX_FRONTIER_ARTIFACT: status=MISS", helper)
        self.assertIn("L32_LINUX_FRONTIER_ARTIFACT: status=PASS", helper)

    def test_producer_publishes_only_after_unchanged_25_case_passes(self) -> None:
        producer = PRODUCER_WORKFLOW.read_text()
        matrix = producer.index(
            "Run unchanged 25-case matrix with per-case userspace C proof"
        )
        publish = producer.index("Publish 25-case-qualified Linux frontier artifact")
        self.assertLess(matrix, publish)
        self.assertIn("tools/ci/l32_linux_frontier_artifact.sh publish", producer)
        helper = ARTIFACT_HELPER.read_text()
        self.assertIn("producer-25-case-result", helper)
        self.assertIn("producer-25-case-pass", helper)
        self.assertIn("producer-firmware-bytes", helper)

    def test_frontier_input_is_fail_closed_on_persistent_qualified_identity(self) -> None:
        text = INPUT_CHECK.read_text()
        self.assertIn("AETHERCORE_L32_USERSPACE_PROFILE", text)
        self.assertIn("frontier-profile-must-be-rv32imac", text)
        self.assertIn("qualified-artifact", text)
        self.assertIn("artifact-firmware-bin", text)
        self.assertIn("L32_LINUX_FRONTIER_INPUT: status=MISS", text)
        self.assertIn("L32_LINUX_FRONTIER_INPUT: status=PASS", text)
        self.assertNotIn("l32_busybox_runtime_freeze.sh", text)
        self.assertNotIn("L32_BUSYBOX_RUNTIME_FREEZE", text)

    def test_success_path_keeps_evidence_in_logs_not_artifacts(self) -> None:
        text = WORKFLOW.read_text()
        self.assertIn("tail -n 200", text)
        self.assertIn("LINUX_FRONTIER_RESULT: status=FAIL", text)
        self.assertIn("LINUX_FRONTIER_RESULT: status=PASS", text)
        self.assertIn("success artifacts: none", text)
        self.assertNotIn("retention-days", text)


if __name__ == "__main__":
    unittest.main()
