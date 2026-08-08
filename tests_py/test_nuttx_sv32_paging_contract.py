from pathlib import Path
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[1]
AUDITOR_PATH = ROOT / "tools/ci/audit_riscv_elf_profile.py"
_spec = importlib.util.spec_from_file_location("audit_riscv_elf_profile", AUDITOR_PATH)
_auditor = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(_auditor)


class NuttXSv32PagingContractTest(unittest.TestCase):
    def test_manifest_remains_pinned_to_nuttx_13(self):
        text = (ROOT / "software/nuttx/manifest.env").read_text()
        self.assertIn("NUTTX_VERSION=13.0.0", text)
        self.assertIn("NUTTX_COMMIT=273c77128b6698f0c95f0d7cde1d0bb803782021", text)
        self.assertIn("NUTTX_APPS_COMMIT=20ffb1a3a3b590d52890ee865a28442390e5d16c", text)

    def test_audit_is_upstream_first_and_fail_closed(self):
        text = (ROOT / "tools/ci/nuttx_sv32_paging_build_audit.sh").read_text()
        for required in (
            "rv-virt:${CONFIG_NAME}",
            "knsh_paging knsh32_paging",
            "CONFIG_BUILD_KERNEL",
            "CONFIG_ARCH_ADDRENV",
            "CONFIG_ARCH_USE_MMU",
            "CONFIG_PAGING",
            "riscv_fillpage",
            "up_addrenv_create",
            "runtime_qualification=not-yet-attempted",
        ):
            self.assertIn(required, text)

    def test_stage_does_not_apply_aethercore_overlay_yet(self):
        text = (ROOT / "tools/ci/nuttx_sv32_paging_build_audit.sh").read_text()
        self.assertNotIn("make_aethercore_nuttx_overlay.py", text)
        self.assertNotIn("make_aethercore_nuttx_protected_overlay.py", text)

    def test_n5b_removes_optional_isa_but_keeps_real_requirements(self):
        text = (ROOT / "tools/ci/nuttx_sv32_paging_aether_profile_build.sh").read_text()
        for required in (
            '"CONFIG_ARCH_CHIP_QEMU_RV_ISA_A": True',
            '"CONFIG_ARCH_CHIP_QEMU_RV_ISA_C": False',
            '"CONFIG_ARCH_FPU": False',
            '"CONFIG_ARCH_DPFPU": False',
            '"CONFIG_ARCH_RV_EXT_SSTC": True',
            '"CONFIG_ARCH_USE_S_MODE": True',
            '"CONFIG_ARCH_USE_MMU": True',
            '"CONFIG_PAGING": True',
            "RV32A=required-by-real-kernel-and-userspace",
            "runtime_qualification=not-yet-attempted",
            "audit_riscv_elf_profile.py",
        ):
            self.assertIn(required, text)

    def test_real_n5b_arch_string_parses_as_rv32ima_without_cfdv(self):
        arch = "rv32i2p1_m2p0_a2p1_zicsr2p0_zifencei2p0_zmmul1p0"
        xlen, extensions = _auditor.parse_arch(arch)
        self.assertEqual(xlen, 32)
        self.assertTrue({"i", "m", "a", "zicsr", "zifencei", "zmmul"} <= extensions)
        self.assertFalse({"c", "f", "d", "v"} & extensions)
        attributes = f'  Tag_RISCV_arch: "{arch}"\n'
        parsed_arch, atomics = _auditor.audit_image("kernel", attributes, "80000000: 1000202f lr.w x0,(x0)\n")
        self.assertEqual(parsed_arch, arch)
        self.assertEqual(atomics, 1)

    def test_exact_parser_rejects_a_real_f_extension_component(self):
        arch = "rv32i2p1_m2p0_a2p1_f2p2_zicsr2p0_zifencei2p0"
        attributes = f'  Tag_RISCV_arch: "{arch}"\n'
        with self.assertRaisesRegex(ValueError, "retained unsupported extensions"):
            _auditor.audit_image("kernel", attributes, "80000000: 1000202f lr.w x0,(x0)\n")


if __name__ == "__main__":
    unittest.main()
