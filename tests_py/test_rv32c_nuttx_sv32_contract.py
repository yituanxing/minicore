from pathlib import Path
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[1]
AUDITOR_PATH = ROOT / "tools/ci/audit_riscv_elf_profile.py"
_spec = importlib.util.spec_from_file_location("audit_riscv_elf_profile_c", AUDITOR_PATH)
_auditor = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(_auditor)


class Rv32cNuttXSv32ContractTest(unittest.TestCase):
    def test_core_profile_adds_only_c_to_the_frozen_n5_system_shape(self):
        text = (ROOT / "src/main/scala/aethercore/config/CoreConfig.scala").read_text()
        self.assertIn("val rv32imacsuSv32PmpSoftware", text)
        self.assertIn('name = "rv32imacsu-sv32-pmp-software"', text)
        self.assertIn("extensions = Set('I', 'M', 'A', 'C')", text)
        self.assertIn("privilegeModes = Set('M', 'S', 'U')", text)
        self.assertIn('virtualMemoryModes = Set("Sv32")', text)
        self.assertIn("pmpEntries = 16", text)
        self.assertIn("sstc = true", text)

    def test_c_top_is_a_peer_of_historical_n5_top(self):
        historical = (ROOT / "src/main/scala/aethercore/sim/AetherCoreNuttXPagingSimTop.scala").read_text()
        compressed = (ROOT / "src/main/scala/aethercore/sim/AetherCoreNuttXPagingCSimTop.scala").read_text()
        self.assertIn("CoreProfiles.rv32imasuSv32PmpSoftware", historical)
        self.assertIn("CoreProfiles.rv32imacsuSv32PmpSoftware", compressed)
        for setting in (
            "stopOnTrap = false",
            "withMachineInterruptPlatform = true",
            "stopOnWfi = false",
            "withNs16550Uart = true",
            "interruptPlatformSourceCount = 52",
            "interruptUartSourceId = 10",
        ):
            self.assertIn(setting, historical)
            self.assertIn(setting, compressed)

    def test_rv32imac_software_profile_is_explicit_and_preserves_real_requirements(self):
        text = (ROOT / "tools/ci/nuttx_sv32_paging_aether_profile_build.sh").read_text()
        self.assertIn("rv32imac)", text)
        self.assertIn("ENABLE_C=1", text)
        self.assertIn("rv32imac_zicsr_zifencei+Sv32+Sstc", text)
        self.assertIn("C=required-by-real-kernel-and-userspace", text)
        self.assertIn("RV32A=required-by-real-kernel-and-userspace", text)
        self.assertIn("Sstc=retained-as-real-supervisor-timer-requirement", text)
        self.assertIn("AUDIT_ARGS+=(--require-c)", text)

    def test_auditor_requires_real_c_in_both_attribute_and_encoding(self):
        arch = "rv32i2p1_m2p0_a2p1_c2p0_zicsr2p0_zifencei2p0_zmmul1p0"
        attributes = f'  Tag_RISCV_arch: "{arch}"\n'
        disassembly = (
            "80000000: 1000202f lr.w x0,(x0)\n"
            "80000004: 8082 ret\n"
        )
        parsed, atomics, compressed = _auditor.audit_image(
            "kernel", attributes, disassembly, require_c=True
        )
        self.assertEqual(parsed, arch)
        self.assertEqual(atomics, 1)
        self.assertEqual(compressed, 1)

        with self.assertRaisesRegex(ValueError, "lost required extensions"):
            _auditor.audit_image(
                "kernel",
                'Tag_RISCV_arch: "rv32i2p1_m2p0_a2p1_zicsr2p0_zifencei2p0"\n',
                disassembly,
                require_c=True,
            )
        with self.assertRaisesRegex(ValueError, "no real 16-bit instruction encoding"):
            _auditor.audit_image(
                "kernel",
                attributes,
                "80000000: 1000202f lr.w x0,(x0)\n",
                require_c=True,
            )

    def test_c_boot_wrapper_keeps_handoff_non_c_and_requires_runtime_c(self):
        wrapper = (ROOT / "Makefile.nuttx-sv32c-probe").read_text()
        base = (ROOT / "Makefile.nuttx-sv32-probe").read_text()
        self.assertIn("TOP := AetherCoreNuttXPagingCSimTop", wrapper)
        self.assertIn("ELABORATE_MAIN := aethercore.ElaborateNuttXPagingC", wrapper)
        self.assertIn("HANDOFF_MARCH := rv32ima_zicsr_zifencei", wrapper)
        self.assertIn("NUTTX_RUNNER_CFLAGS := -DAETHERCORE_NUTTX_C_TOP=1", wrapper)
        self.assertIn("REQUIRE_COMPRESSED := 1", wrapper)
        self.assertIn("TOP ?= AetherCoreNuttXPagingSimTop", base)
        self.assertIn("HANDOFF_MARCH ?= rv32ima_zicsr_zifencei", base)
        self.assertIn("compressed-commits=[1-9][0-9]*", base)

    def test_runner_counts_architectural_two_byte_retirements(self):
        runner = (ROOT / "sim/nuttx_paging_boot_main.cpp").read_text()
        self.assertIn("AETHERCORE_NUTTX_C_TOP", runner)
        self.assertIn("VAetherCoreNuttXPagingCSimTop", runner)
        self.assertIn("VAetherCoreNuttXPagingSimTop", runner)
        self.assertIn("top.io_commit_instBytes", runner)
        self.assertIn("compressedCommits", runner)
        self.assertIn("N5C_COMPRESSED_EXECUTION_MISSING", runner)
        self.assertIn("compressed-commits=", runner)

    def test_c_cache_is_separate_and_replays_exact_kernel_and_userspace_evidence(self):
        cache = (ROOT / "tools/ci/nuttx_sv32_paging_cache_key.sh").read_text()
        self.assertIn("rv32ima|rv32imac", cache)
        self.assertIn("profile=%s", cache)
        self.assertIn("cache_isa_audit", cache)
        self.assertIn("cache_user_elf", cache)
        self.assertIn("cache_user_sha", cache)
        self.assertIn("user-init.elf.sha256", cache)
        self.assertIn("kernel_compressed_instructions=[1-9][0-9]*", cache)
        self.assertIn("user_compressed_instructions=[1-9][0-9]*", cache)
        self.assertIn('cp -f "$cache_user_elf" "${TARGET_DIR}/user-init.elf"', cache)

    def test_formal_workflow_requires_static_and_runtime_compressed_execution(self):
        workflow = (ROOT / ".github/workflows/rv32c-nuttx-sv32.yml").read_text()
        self.assertIn("AETHERCORE_NUTTX_N5_PROFILE: rv32imac", workflow)
        self.assertIn("NuttX 13.0.0 RV32IMAC Sv32 real paging boot", workflow)
        self.assertIn("kernel_compressed_instructions=[1-9][0-9]*", workflow)
        self.assertIn("user_compressed_instructions=[1-9][0-9]*", workflow)
        self.assertIn("Makefile.nuttx-sv32c-probe", workflow)
        self.assertIn("compressed-commits=[1-9][0-9]*", workflow)
        self.assertIn("N5C_BOOT_REACHED_NSH", workflow)
        self.assertIn("local M-mode handoff remains `rv32ima_zicsr_zifencei`", workflow)
        self.assertIn("user-init.elf", workflow)


if __name__ == "__main__":
    unittest.main()
