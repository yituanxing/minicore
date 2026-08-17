from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "rv32c-linux-userspace.yml"
SUITE = ROOT / "tools" / "ci" / "l32_linux_runtime_suite.py"


class Rv32CLinuxUserspaceContract(unittest.TestCase):
    def test_workflow_is_explicit_rv32imac_peer(self) -> None:
        text = WORKFLOW.read_text()
        for required in (
            "name: RV32C Linux Userspace Qualification",
            "AETHERCORE_L32_USERSPACE_PROFILE: rv32imac",
            "tools/ci/l32_rv32c_kernel_build.sh",
            "RV32C_LINUX_KERNEL_RESULT: status=PASS",
            "fw_payload_fdt_addr=0x87f00000",
            "TOP=AetherCoreOpenSbiCSimTop",
            "ELABORATE_MAIN=aethercore.ElaborateOpenSbiC",
            "-DAETHERCORE_L32_C_TOP",
            "FORKSERVER_REQUIRE_USER_COMPRESSED=1",
        ):
            self.assertIn(required, text)

    def test_workflow_reuses_profile_owned_software_chain(self) -> None:
        text = WORKFLOW.read_text()
        for required in (
            "l32_software_artifact_cache.sh busybox",
            "l32_musl_link_wrapper.sh",
            "l32_software_artifact_cache.sh runtime-probe",
            "l32_real_programs_cache.sh",
            "l32_runtime_image_cache.sh",
            "l32_software_artifact_cache.sh busybox-payload",
            "l32_busybox_runtime_freeze.sh",
            "build/l32-busybox-rv32imac",
            "build/l32-runtime-probe-rv32imac",
            "build/l32-real-programs-rv32imac",
            "build/l32-linux-busybox-rv32imac",
            "build/l32-busybox-shell-boot-rv32imac",
        ):
            self.assertIn(required, text)

    def test_every_executable_userspace_layer_has_static_c_and_low_address_proof(self) -> None:
        text = WORKFLOW.read_text()
        self.assertIn("--require-c", text)
        self.assertIn("--max-exec-vaddr-exclusive 0x80000000", text)
        expected = (
            "musl-probe",
            "busybox",
            "runtime-probe",
            "lua",
            "sqlite",
            "bash",
            "busybox-real",
            "zlib",
            "libpng",
        )
        for name in expected:
            self.assertIn(f"audit {name} ", text)
        self.assertIn("-eq 9", text)
        self.assertIn("exec_vaddr_limit=0x80000000", text)

    def test_runtime_reuses_exact_unchanged_25_case_suite_and_adds_per_case_c(self) -> None:
        workflow = WORKFLOW.read_text()
        suite = SUITE.read_text()
        self.assertIn("python3 tools/ci/l32_linux_runtime_suite.py write-batch", workflow)
        self.assertIn("python3 tools/ci/l32_linux_runtime_suite.py verify-log", workflow)
        self.assertIn("from tools.ci.l32_linux_runtime_suite import CASES", workflow)
        self.assertIn("L32_FORKSERVER_USER_COMPRESSED_PASS", workflow)
        self.assertIn("userspace C case set mismatch", workflow)
        self.assertIn("userspace_compressed_total=", workflow)
        self.assertIn("L32_RV32C_USERSPACE_RUNTIME_PASS", workflow)
        self.assertIn("libpng-real", suite)
        self.assertIn("busybox-vi", suite)
        self.assertIn("futex", suite)
        self.assertEqual(suite.count("RuntimeCase("), 25)

    def test_workflow_keeps_exact_handoff_and_uploads_all_qualification_layers(self) -> None:
        text = WORKFLOW.read_text()
        for required in (
            "next_addr=0x80400000",
            "fdt_addr=0x87f00000",
            "runtime-freeze.txt",
            "l32-userspace-rv32imac-evidence",
            "l32-busybox-forkserver-rv32imac/logs/**",
            "rv32c-linux-userspace-${{ github.run_id }}-${{ github.run_attempt }}",
        ):
            self.assertIn(required, text)


if __name__ == "__main__":
    unittest.main()
