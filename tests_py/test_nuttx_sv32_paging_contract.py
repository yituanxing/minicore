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

    def test_n5b_default_profile_remains_rv32ima_without_optional_cfdv(self):
        text = (ROOT / "tools/ci/nuttx_sv32_paging_aether_profile_build.sh").read_text()
        for required in (
            'PROFILE="${AETHERCORE_NUTTX_N5_PROFILE:-rv32ima}"',
            "rv32ima)",
            "ENABLE_C=0",
            '"CONFIG_ARCH_CHIP_QEMU_RV_ISA_A": True',
            '"CONFIG_ARCH_CHIP_QEMU_RV_ISA_C": enable_c',
            '"CONFIG_ARCH_FPU": False',
            '"CONFIG_ARCH_DPFPU": False',
            '"CONFIG_ARCH_RV_EXT_SSTC": True',
            '"CONFIG_ARCH_USE_S_MODE": True',
            '"CONFIG_ARCH_USE_MMU": True',
            '"CONFIG_PAGING": True',
            "optional_C_F_D_V=disabled",
            "RV32A=required-by-real-kernel-and-userspace",
            "runtime_qualification=not-yet-attempted",
            "audit_riscv_elf_profile.py",
        ):
            self.assertIn(required, text)

    def test_cache_is_profile_keyed_and_preserves_isa_audit(self):
        text = (ROOT / "tools/ci/nuttx_sv32_paging_cache_key.sh").read_text()
        self.assertIn('PROFILE="${AETHERCORE_NUTTX_N5_PROFILE:-rv32ima}"', text)
        self.assertIn("printf 'profile=%s\\n'", text)
        self.assertIn("cache_isa_audit", text)
        self.assertIn("software_profile=$PROFILE", text)
        self.assertIn("profile.txt", text)

    def test_n5c_handoff_enables_required_supervisor_firmware_gates(self):
        text = (ROOT / "Makefile.nuttx-sv32-probe").read_text()
        for required in (
            "HANDOFF_MARCH ?= rv32ima_zicsr_zifencei",
            "csrw pmpaddr0, t0",
            "csrw pmpcfg0, t0",
            "li t0, -1",
            "li t0, 0x1f",
            "csrw medeleg, t0",
            "csrw mideleg, t0",
            "csrw mcounteren, t0",
            "csrw 0x31a, t0",
            "li t0, 0xb100",
            "li t0, 0x20",
            "li t0, 0x2",
            "li t0, 0x80000000",
            "PMP allow-all installed",
            "page faults/U-ecall/STIP delegated, TM/STCE enabled",
        ):
            self.assertIn(required, text)

    def test_n5c_sim_top_uses_the_historical_composed_sv32_pmp_profile(self):
        text = (ROOT / "src/main/scala/aethercore/sim/AetherCoreNuttXPagingSimTop.scala").read_text()
        self.assertIn("CoreProfiles.rv32imasuSv32PmpSoftware", text)
        self.assertNotIn("rv32imacsuSv32PmpSoftware", text)

    def test_n5c_probe_only_succeeds_at_nsh(self):
        makefile = (ROOT / "Makefile.nuttx-sv32-probe").read_text()
        runner = (ROOT / "sim/nuttx_paging_boot_main.cpp").read_text()
        self.assertIn("N5C_BOOT_REACHED_NSH", makefile)
        self.assertIn("N5C_FIRST_EXPECTED_PAGE_FAULT", runner)
        self.assertIn("N5C_FIRST_EXPECTED_USER_ECALL", runner)
        self.assertIn("N5C_FIRST_UNEXPECTED_EXCEPTION", runner)
        self.assertIn("N5C_PAGE_FAULT_LIVELOCK", runner)
        self.assertNotIn("N5C_(FIRST_EXCEPTION|BOOT_REACHED_NSH)", makefile)

    def test_real_n5b_arch_string_parses_as_rv32ima_without_cfdv(self):
        arch = "rv32i2p1_m2p0_a2p1_zicsr2p0_zifencei2p0_zmmul1p0"
        xlen, extensions = _auditor.parse_arch(arch)
        self.assertEqual(xlen, 32)
        self.assertTrue({"i", "m", "a", "zicsr", "zifencei", "zmmul"} <= extensions)
        self.assertFalse({"c", "f", "d", "v"} & extensions)
        attributes = f'  Tag_RISCV_arch: "{arch}"\n'
        parsed_arch, atomics, compressed = _auditor.audit_image(
            "kernel", attributes, "80000000: 1000202f lr.w x0,(x0)\n"
        )
        self.assertEqual(parsed_arch, arch)
        self.assertEqual(atomics, 1)
        self.assertEqual(compressed, 0)

    def test_default_auditor_rejects_c_and_f(self):
        for extension in ("c2p0", "f2p2"):
            arch = f"rv32i2p1_m2p0_a2p1_{extension}_zicsr2p0_zifencei2p0"
            attributes = f'  Tag_RISCV_arch: "{arch}"\n'
            with self.assertRaisesRegex(ValueError, "retained unsupported extensions"):
                _auditor.audit_image(
                    "kernel", attributes, "80000000: 1000202f lr.w x0,(x0)\n"
                )


if __name__ == "__main__":
    unittest.main()
