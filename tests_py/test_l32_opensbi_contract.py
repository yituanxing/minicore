from pathlib import Path
import importlib.util
import struct
import unittest

ROOT = Path(__file__).resolve().parents[1]
DTB_BUILDER_PATH = ROOT / "tools/ci/make_l32_dtb.py"
_spec = importlib.util.spec_from_file_location("make_l32_dtb", DTB_BUILDER_PATH)
_dtb = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(_dtb)


class L32OpenSBIContractTest(unittest.TestCase):
    def test_versions_are_pinned(self):
        text = (ROOT / "software/l32/manifest.env").read_text()
        self.assertIn("LINUX_VERSION=6.6.143", text)
        self.assertIn("linux-6.6.143.tar.xz", text)
        self.assertIn("OPENSBI_VERSION=1.6", text)
        self.assertIn("OPENSBI_COMMIT=bd613dd92113f683052acfb23d9dc8ba60029e0a", text)
        self.assertIn("OPENSBI_RV32_ISA=rv32ima_zicsr_zifencei", text)
        self.assertIn("OPENSBI_RV32_ABI=ilp32", text)
        self.assertIn("L32_TOOLCHAIN_VERSION=riscv32-ilp32d--glibc--stable-2024.05-1", text)
        self.assertIn("L32_TOOLCHAIN_SHA256=00112418e6d4b0733019a673b682a39f1ce6300b9448cd840f1194aa4b064192", text)
        self.assertIn("L32_CROSS_COMPILE_PREFIX=riscv32-buildroot-linux-gnu-", text)
        self.assertIn("L32_XLEN=32", text)
        self.assertIn("L32_PLATFORM=generic", text)

    def test_build_accepts_only_real_pie_capable_toolchains(self):
        text = (ROOT / "tools/ci/l32_opensbi_build.sh").read_text()
        self.assertIn("PLATFORM_RISCV_XLEN=", text)
        self.assertIn('PLATFORM_RISCV_ISA="${OPENSBI_RV32_ISA}"', text)
        self.assertIn('PLATFORM_RISCV_ABI="${OPENSBI_RV32_ABI}"', text)
        self.assertIn('-march="${OPENSBI_RV32_ISA}"', text)
        self.assertIn('-mabi="${OPENSBI_RV32_ABI}"', text)
        self.assertIn("LLVM=1", text)
        self.assertIn("--target=riscv32-unknown-elf", text)
        self.assertIn("-Wl,-pie", text)
        self.assertIn("L32_TOOLCHAIN_MODE", text)
        self.assertIn("L32_CROSS_COMPILE", text)
        self.assertIn("probe_gcc_prefix", text)
        self.assertIn("L32_EXPLICIT_GCC_PIE_FAILED", text)
        self.assertIn("riscv64-linux-gnu-", text)
        self.assertNotIn("riscv64-unknown-elf-", text)
        self.assertNotIn("riscv32-unknown-elf-", text)
        self.assertIn("fw_payload.elf", text)
        self.assertIn('FW_TEXT_START="0x80000000"', text)
        self.assertIn('FW_FDT_PATH="${DTB}"', text)
        self.assertIn("make_l32_dtb.py", text)
        self.assertIn("Tag_RISCV_arch", text)
        self.assertIn("retained unsupported F/D/C extension", text)
        self.assertIn("L32_OPENSBI_RESULT: status=PASS", text)

    def test_bootlin_provisioner_is_pinned_and_validates_real_pie(self):
        text = (ROOT / "tools/ensure_l32_riscv32_linux_gcc.sh").read_text()
        for required in (
            "L32_TOOLCHAIN_URL",
            "L32_TOOLCHAIN_SHA256",
            "L32_CROSS_COMPILE_PREFIX",
            "13.3.0",
            "-march=rv32ima_zicsr_zifencei",
            "-mabi=ilp32",
            "-Wl,-pie",
            "Type:[[:space:]]*DYN",
            "L32_BOOTLIN_PIE_LINK_FAILED",
            "GITHUB_PATH",
        ):
            self.assertIn(required, text)

    def test_workflow_uses_only_pinned_bootlin_linux_gcc(self):
        text = (ROOT / ".github/workflows/l32-opensbi.yml").read_text()
        self.assertIn("tools/ensure_l32_riscv32_linux_gcc.sh", text)
        self.assertIn("L32_TOOLCHAIN_MODE: gcc", text)
        self.assertNotIn("ensure_riscv_none_elf_gcc_15_2.sh", text)
        self.assertNotIn("riscv-none-elf-", text)

    def test_minimal_fdt_matches_frozen_platform(self):
        text = (ROOT / "software/l32/aethercore-rv32.dts").read_text()
        for required in (
            'compatible = "aethercore,l32"',
            'riscv,isa = "rv32ima_zicsr_zifencei_sstc"',
            'mmu-type = "riscv,sv32"',
            'compatible = "ns16550a"',
            '0x10000000',
            'compatible = "riscv,aclint-mtimer"',
            '0x0200bff8',
            '0x02004000',
            'interrupts-extended = <&cpu0_intc 7>',
            'timebase-frequency = <10000000>',
        ):
            self.assertIn(required, text)
        self.assertNotIn("mswi", text.lower())
        self.assertNotIn("clint0", text.lower())

    def test_generated_dtb_has_valid_header_and_key_contract_strings(self):
        blob = _dtb.build_l32_dtb()
        header = struct.unpack(">10I", blob[:40])
        magic, total_size, off_struct, off_strings, off_reserve, version, last_version, _, strings_size, struct_size = header
        self.assertEqual(magic, 0xD00DFEED)
        self.assertEqual(total_size, len(blob))
        self.assertEqual(version, 17)
        self.assertEqual(last_version, 16)
        self.assertEqual(off_reserve, 40)
        self.assertEqual(off_strings, off_struct + struct_size)
        self.assertEqual(total_size, off_strings + strings_size)
        for value in (
            b"aethercore,l32\0",
            b"rv32ima_zicsr_zifencei_sstc\0",
            b"riscv,sv32\0",
            b"ns16550a\0",
            b"riscv,aclint-mtimer\0",
            b"mtime\0mtimecmp\0",
        ):
            self.assertIn(value, blob)

    def test_first_gate_does_not_build_linux_yet(self):
        text = (ROOT / "tools/ci/l32_opensbi_build.sh").read_text()
        self.assertNotIn("linux-${LINUX_VERSION}", text)
        self.assertNotIn("rv32_defconfig", text)
        self.assertNotIn("arch/riscv/boot/Image", text)


if __name__ == "__main__":
    unittest.main()
