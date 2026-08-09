from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class L32LinuxBuildContractTest(unittest.TestCase):
    def test_linux_source_and_rv32_config_are_pinned(self):
        manifest = (ROOT / "software/l32/manifest.env").read_text()
        self.assertIn("LINUX_VERSION=6.6.143", manifest)
        self.assertIn(
            "LINUX_SHA256=dace1f8dc9c0dbf5df14f47e3229cd62c298e83049681731ef229f2ba7592932",
            manifest,
        )
        self.assertIn("LINUX_RV32_DEFCONFIG=rv32_defconfig", manifest)

    def test_build_starts_from_upstream_rv32_defconfig_and_stays_inside_frozen_isa(self):
        text = (ROOT / "tools/ci/l32_linux_build.sh").read_text()
        self.assertIn('"${LINUX_RV32_DEFCONFIG}"', text)
        self.assertIn("-d RISCV_ISA_C", text)
        self.assertIn("-d FPU", text)
        self.assertIn("CONFIG_32BIT=y", text)
        self.assertIn("CONFIG_MMU=y", text)
        self.assertIn("# CONFIG_RISCV_ISA_C is not set", text)
        self.assertIn("# CONFIG_FPU is not set", text)
        self.assertIn("-j\"${JOBS}\" Image", text)
        self.assertIn("vmlinux", text)
        self.assertIn("arch/riscv/boot/Image", text)
        self.assertIn("retained unsupported F/D/C extension", text)
        self.assertIn("L32_LINUX_BUILD_RESULT: status=PASS", text)

    def test_linux_build_does_not_touch_opensbi_or_rtl(self):
        text = (ROOT / "tools/ci/l32_linux_build.sh").read_text()
        self.assertNotIn("OpenSBI", text)
        self.assertNotIn("Verilator", text)
        self.assertNotIn("mill ", text)
        self.assertNotIn("src/main/scala", text)

    def test_cache_requires_exact_build_inputs_and_real_outputs(self):
        text = (ROOT / "tools/ci/l32_linux_cache_key.sh").read_text()
        self.assertIn("software/l32/manifest.env", text)
        self.assertIn("tools/ci/l32_linux_build.sh", text)
        self.assertIn("tools/ensure_l32_riscv32_linux_gcc.sh", text)
        self.assertIn("obj/vmlinux", text)
        self.assertIn("arch/riscv/boot/Image", text)
        self.assertIn("L32_LINUX_BUILD_RESULT: status=PASS", text)
        self.assertIn("input-key.txt", text)

    def test_workflow_is_software_only_and_reuses_persistent_build(self):
        text = (ROOT / ".github/workflows/l32-linux-build.yml").read_text()
        self.assertIn("L32 Linux 6.6.143 Build", text)
        self.assertIn("tools/ci/l32_linux_cache_key.sh check", text)
        self.assertIn("tools/ci/l32_linux_cache_key.sh mark", text)
        self.assertIn("tools/ci/l32_linux_build.sh", text)
        self.assertIn("tools/ensure_l32_riscv32_linux_gcc.sh", text)
        self.assertNotIn("Verilator", text)
        self.assertNotIn("Makefile.l32-opensbi-probe", text)


if __name__ == "__main__":
    unittest.main()
