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

    def test_historical_checkpoint_and_canonical_recipe_are_separate(self):
        freeze = (ROOT / "software/l32/linux-freeze.env").read_text()
        self.assertIn("L32_LINUX_LEGACY_VMLINUX_SHA256=2b0307994ca640b2fead335f690ea6d4ce6e1a5fb67857a31f13999308aa50d7", freeze)
        self.assertIn("L32_LINUX_LEGACY_IMAGE_SHA256=5a3c7e2579330b4277e664391c74f966146188a2da07d3bd37fbd99aa7761048", freeze)
        self.assertIn("L32_LINUX_LEGACY_CONFIG_SHA256=c344bc909736ad9c8932d077467c473022be69bc4bbeea658c5b743e8b923e27", freeze)
        self.assertIn("L32_LINUX_LEGACY_BUILD_CACHE_KEY=e91bff604ed9d93d2fcb2f6049fd104281e2c05ae9e2756af69e6af477c68822", freeze)
        self.assertIn("L32_LINUX_LEGACY_RUN_ID=31290514249", freeze)
        self.assertIn("L32_LINUX_LEGACY_ARTIFACT_ID=9031239454", freeze)
        self.assertIn("L32_LINUX_RECIPE_VERSION=canonical-v1", freeze)
        self.assertIn("L32_LINUX_BUILD_USER=aethercore", freeze)
        self.assertIn("L32_LINUX_BUILD_HOST=builder", freeze)
        self.assertIn("L32_LINUX_BUILD_VERSION=1", freeze)
        self.assertIn("L32_LINUX_BUILD_TIMESTAMP='Sun Aug  9 02:34:43 UTC 2026'", freeze)
        self.assertIn("L32_LINUX_BUILD_TZ=UTC", freeze)

    def test_build_starts_from_upstream_rv32_defconfig_and_stays_inside_platform(self):
        text = (ROOT / "tools/ci/l32_linux_build.sh").read_text()
        self.assertIn('"${LINUX_RV32_DEFCONFIG}"', text)
        self.assertIn("-d EFI", text)
        self.assertIn("-d RISCV_ISA_C", text)
        self.assertIn("-d FPU", text)
        self.assertIn("-d VGA_CONSOLE", text)
        self.assertIn("RISC-V EFI", text)
        self.assertIn("NS16550 serial console", text)
        self.assertIn("CONFIG_32BIT=y", text)
        self.assertIn("CONFIG_MMU=y", text)
        self.assertIn("# CONFIG_EFI is not set", text)
        self.assertIn("# CONFIG_RISCV_ISA_C is not set", text)
        self.assertIn("# CONFIG_FPU is not set", text)
        self.assertIn("# CONFIG_VGA_CONSOLE is not set", text)
        self.assertIn("-j\"${JOBS}\" Image", text)
        self.assertIn("vmlinux", text)
        self.assertIn("arch/riscv/boot/Image", text)
        self.assertIn("retained unsupported F/D/C extension", text)
        self.assertIn("L32_LINUX_BUILD_RESULT: status=PASS", text)

    def test_build_replays_neutral_canonical_kbuild_identity(self):
        text = (ROOT / "tools/ci/l32_linux_build.sh").read_text()
        self.assertIn('source "${ROOT_DIR}/software/l32/linux-freeze.env"', text)
        self.assertIn('export KBUILD_BUILD_USER="${L32_LINUX_BUILD_USER}"', text)
        self.assertIn('export KBUILD_BUILD_HOST="${L32_LINUX_BUILD_HOST}"', text)
        self.assertIn('export KBUILD_BUILD_VERSION="${L32_LINUX_BUILD_VERSION}"', text)
        self.assertIn('export KBUILD_BUILD_TIMESTAMP="${L32_LINUX_BUILD_TIMESTAMP}"', text)
        self.assertIn('export TZ="${L32_LINUX_BUILD_TZ}"', text)
        self.assertIn("recipe_version=${L32_LINUX_RECIPE_VERSION}", text)
        self.assertIn("kbuild_user=${KBUILD_BUILD_USER}", text)
        self.assertIn("kbuild_timestamp=${KBUILD_BUILD_TIMESTAMP}", text)
        self.assertIn("kbuild_tz=${TZ}", text)

    def test_canonical_base_rebuild_never_reuses_kbuild_objects(self):
        text = (ROOT / "tools/ci/l32_linux_build.sh").read_text()
        self.assertIn('rm -rf "${OBJ_DIR}" "${EVIDENCE_DIR}"', text)
        self.assertIn("never try to repair the canonical root", text)
        self.assertIn("build-inputs.txt", text)
        self.assertNotIn(".aethercore-object-inputs", text)

    def test_linux_build_does_not_touch_rtl(self):
        text = (ROOT / "tools/ci/l32_linux_build.sh").read_text()
        self.assertNotIn("Verilator", text)
        self.assertNotIn("mill ", text)
        self.assertNotIn("src/main/scala", text)

    def test_cache_requires_exact_recipe_and_canonical_output_identity(self):
        text = (ROOT / "tools/ci/l32_linux_cache_key.sh").read_text()
        self.assertIn("software/l32/manifest.env", text)
        self.assertIn("software/l32/linux-freeze.env", text)
        self.assertIn('"${FREEZE_ENV}"', text)
        self.assertIn("tools/ci/l32_linux_build.sh", text)
        self.assertIn("tools/ensure_l32_riscv32_linux_gcc.sh", text)
        self.assertIn("obj/vmlinux", text)
        self.assertIn("arch/riscv/boot/Image", text)
        self.assertIn("evidence/resolved.config", text)
        self.assertIn("L32_LINUX_VMLINUX_SHA256", text)
        self.assertIn("L32_LINUX_IMAGE_SHA256", text)
        self.assertIn("L32_LINUX_CONFIG_SHA256", text)
        self.assertIn("SHA drift", text)
        self.assertIn("L32_LINUX_BUILD_RESULT: status=PASS", text)
        self.assertIn("input-key.txt", text)

    def test_workflow_is_software_only_and_reuses_only_validated_outputs(self):
        text = (ROOT / ".github/workflows/l32-linux-build.yml").read_text()
        self.assertIn("L32 Linux 6.6.143 Build", text)
        self.assertIn("software/l32/linux-freeze.env", text)
        self.assertIn("tools/ci/l32_linux_cache_key.sh check", text)
        self.assertIn("tools/ci/l32_linux_cache_key.sh mark", text)
        self.assertIn("tools/ci/l32_linux_build.sh", text)
        self.assertIn("tools/ensure_l32_riscv32_linux_gcc.sh", text)
        self.assertNotIn("Verilator", text)
        self.assertNotIn("Makefile.l32-opensbi-probe", text)


if __name__ == "__main__":
    unittest.main()
