from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "software" / "nuttx" / "manifest.env"
README = ROOT / "software" / "nuttx" / "README.md"
BUILD_SCRIPT = ROOT / "tools" / "ci" / "nuttx_n1_build.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "nuttx-stage.yml"


class NuttxN1ContractTest(unittest.TestCase):
    def test_upstream_sources_are_exactly_pinned(self) -> None:
        text = MANIFEST.read_text()
        self.assertIn("NUTTX_VERSION=13.0.0", text)
        self.assertIn(
            "NUTTX_COMMIT=273c77128b6698f0c95f0d7cde1d0bb803782021", text
        )
        self.assertIn(
            "NUTTX_APPS_COMMIT=20ffb1a3a3b590d52890ee865a28442390e5d16c", text
        )
        self.assertNotIn("master", text)
        self.assertNotIn("main", text)

    def test_n1_is_build_qualification_not_boot_claim(self) -> None:
        text = README.read_text()
        self.assertIn("N1 is build qualification", text)
        self.assertIn("does **not** claim that the image boots", text)
        self.assertIn("freeze/zephyr-v3.7.2-z1-z4", text)

    def test_build_is_fail_closed_on_the_aethercore_isa(self) -> None:
        text = BUILD_SCRIPT.read_text()
        required = (
            "CONFIG_ARCH_CHIP_QEMU_RV_ISA_M y",
            "CONFIG_ARCH_CHIP_QEMU_RV_ISA_A n",
            "CONFIG_ARCH_CHIP_QEMU_RV_ISA_C n",
            "CONFIG_ARCH_RV_ISA_ZICSR_ZIFENCEI y",
            "CONFIG_FS_HOSTFS n",
            "CONFIG_RISCV_SEMIHOSTING_HOSTFS n",
            "Tag_RISCV_arch",
            "forbidden A/C extension present",
        )
        for fragment in required:
            self.assertIn(fragment, text)
        self.assertIn('[[ -s nuttx ]]', text)
        self.assertIn("sha256sum", text)

    def test_workflow_preserves_one_bounded_self_hosted_stage_slot(self) -> None:
        text = WORKFLOW.read_text()
        runner = "runs-on: [self-hosted, Linux, X64, minicore]"
        self.assertEqual(text.count(runner), 1)
        self.assertIn("runs-on: ubuntu-latest", text)
        self.assertIn("needs: source", text)
        self.assertIn("actions/cache@v4", text)
        self.assertIn("actions/download-artifact@v4", text)
        self.assertIn("group: nuttx-stage-${{ github.ref }}", text)
        self.assertIn("cancel-in-progress: true", text)
        self.assertNotIn("full-validation", text)
        self.assertNotIn("Fast Gate", text)


if __name__ == "__main__":
    unittest.main()
