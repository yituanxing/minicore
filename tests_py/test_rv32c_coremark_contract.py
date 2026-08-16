from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32cCoreMarkContract(unittest.TestCase):
    def test_coremark_builder_keeps_rv32im_default_and_allows_rv32imc(self):
        build = (ROOT / "tools/build_rv32im_coremark.sh").read_text()
        self.assertIn('march="${AETHERCORE_RV32_MARCH:-rv32im}"', build)
        self.assertIn("rv32im|rv32imc", build)
        self.assertIn('"-march=$march"', build)
        self.assertIn("compressed_instructions", build)
        self.assertIn("objdump contains no 16-bit instructions", build)

    def test_rv32imc_top_uses_the_existing_profile(self):
        top = (ROOT / "src/main/scala/aethercore/sim/AetherCoreRV32IMCSimTop.scala").read_text()
        elaborate = (ROOT / "src/main/scala/aethercore/ElaborateRV32IMC.scala").read_text()
        self.assertIn("CoreProfiles.rv32imcSoftware", top)
        self.assertIn("new AetherCoreRV32IMCSimTop", elaborate)

    def test_makefile_builds_with_pinned_target_toolchain_and_provider_neutral_reference(self):
        makefile = (ROOT / "Makefile.rv32imc-coremark").read_text()
        self.assertIn("RISCV_PREFIX=riscv-none-elf-", makefile)
        self.assertIn("AETHERCORE_RV32_MARCH=rv32imc", makefile)
        self.assertIn("AetherCoreRV32IMCSimTop", makefile)
        self.assertIn("coremark_rv32imc_O2.bin", makefile)
        self.assertIn("RV32_REFERENCE_SO", makefile)
        self.assertIn("--difftest $(RV32_REFERENCE_SO)", makefile)
        self.assertNotIn("RV32_NEMU_SO", makefile)

    def test_spike_reference_is_exact_reproducible_and_abi_compatible(self):
        ensure = (ROOT / "tools/ensure_rv32_spike_single_step.sh").read_text()
        probe = (ROOT / "tools/probe_rv32_spike_deterministic.sh").read_text()
        shim = (ROOT / "tools/spike_rv32_reference_shim.cpp").read_text()

        self.assertIn('REVISION="530af85d83781a3dae31a4ace84a573ec255fefa"', ensure)
        self.assertIn(
            'EXPECTED_SHA256="85b02befce3e98383080c28f33f18bb4d08282cf97e2e6b7f2f7e334a223c85f"',
            ensure,
        )
        self.assertIn("probe_rv32_spike_deterministic.sh", ensure)
        self.assertIn("rv32imc-spike-single-step", ensure)

        self.assertIn("SOURCE_DATE_EPOCH", probe)
        self.assertIn("-ffile-prefix-map=", probe)
        self.assertIn("--build-id=none", probe)
        self.assertIn("AETHERCORE_DTC_SENTINEL", probe)
        self.assertIn("libdisasm.a", probe)
        self.assertIn("530af85d83781a3dae31a4ace84a573ec255fefa", probe)

        self.assertIn('"RV32IMC_Zicsr"', shim)
        self.assertIn('"M", DEFAULT_VARCH', shim)
        self.assertIn("set_pmp_num(0)", shim)
        self.assertIn('extern "C" void difftest_init()', shim)
        self.assertIn('extern "C" void difftest_exec', shim)
        self.assertIn('extern "C" std::uint8_t* new_space', shim)
        self.assertIn('extern "C" void add_mmio_map', shim)

    def test_workflow_requires_real_compressed_code_and_exact_spike_difftest(self):
        workflow = (ROOT / ".github/workflows/rv32c-coremark.yml").read_text()
        self.assertIn("compressed_instructions=[1-9][0-9]*", workflow)
        self.assertIn("ensure_riscv_none_elf_gcc_15_2.sh", workflow)
        self.assertIn("ensure_rv32_spike_single_step.sh", workflow)
        self.assertIn("Makefile.rv32imc-coremark", workflow)
        self.assertIn("chmod +x ./mill", workflow)
        self.assertIn('RV32_REFERENCE_SO="$RV32_REFERENCE_SO" run', workflow)
        self.assertIn("exact reproducible Spike RV32IMC", workflow)
        self.assertNotIn("ensure_rv32_nemu_single_step.sh", workflow)


if __name__ == "__main__":
    unittest.main()
