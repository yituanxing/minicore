from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "software/l32_busybox/manifest.env"
FIRMWARE_MANIFEST = ROOT / "software/l32/manifest.env"
BUILD = ROOT / "tools/ci/l32_busybox_build.sh"


class L32BusyBoxContract(unittest.TestCase):
    def test_frozen_userspace_inputs(self):
        text = MANIFEST.read_text()
        self.assertIn("L32_USERSPACE_CROSS_COMPILE_PREFIX=riscv64-unknown-elf-", text)
        self.assertIn("L32_USERSPACE_ISA=rv32ima_zicsr_zifencei", text)
        self.assertIn("L32_USERSPACE_ABI=ilp32", text)
        self.assertNotIn("riscv32-ilp32d", text)
        self.assertIn("MUSL_VERSION=1.2.5", text)
        self.assertIn(
            "MUSL_SHA256=a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4",
            text,
        )
        self.assertIn("BUSYBOX_VERSION=1.36.1", text)
        self.assertIn(
            "BUSYBOX_SHA256=b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314",
            text,
        )

    def test_userspace_freeze_isolated_from_firmware_and_minimal_init_paths(self):
        firmware = FIRMWARE_MANIFEST.read_text()
        self.assertNotIn("MUSL_VERSION=", firmware)
        self.assertNotIn("BUSYBOX_VERSION=", firmware)
        build = BUILD.read_text()
        self.assertIn('software/l32_busybox/manifest.env', build)
        self.assertNotIn('software/l32_userspace/manifest.env', build)
        self.assertNotIn('source "${ROOT_DIR}/software/l32/manifest.env"', build)

    def test_musl_is_built_as_separate_soft_float_sysroot(self):
        text = BUILD.read_text()
        self.assertIn("--target=riscv32-linux-musl", text)
        self.assertIn("--disable-shared", text)
        self.assertIn("--enable-static", text)
        self.assertIn('L32_CC="${BUILD_DIR}/l32-rv32ima-ilp32-gcc"', text)
        self.assertIn('-march="${L32_USERSPACE_ISA}"', text)
        self.assertIn('-mabi="${L32_USERSPACE_ABI}"', text)
        self.assertIn('"CC=${L32_CC}"', text)
        self.assertIn("toolchain-probe.o", text)
        self.assertIn("soft-float ABI", text)
        self.assertNotIn("--sysroot=/", text)

    def test_busybox_is_minimal_static_ash_and_rejects_fdc(self):
        text = BUILD.read_text()
        self.assertIn("make ARCH=riscv allnoconfig", text)
        self.assertNotIn("make ARCH=riscv defconfig", text)
        for symbol in (
            "CONFIG_STATIC",
            "CONFIG_ASH",
            "CONFIG_SH_IS_ASH",
            "CONFIG_ECHO",
            "CONFIG_PRINTF",
            "CONFIG_TEST",
        ):
            self.assertIn(f"'{symbol}'", text)
        self.assertIn("minimal shell config unexpectedly enabled kbd_mode", text)
        self.assertIn("statically linked", text)
        self.assertIn("BusyBox retained unsupported F/D/C extension", text)
        self.assertIn("L32_BUSYBOX_BUILD_RESULT: status=PASS", text)


if __name__ == "__main__":
    unittest.main()
