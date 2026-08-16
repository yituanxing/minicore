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

    def test_coremark_layer_consumes_the_frozen_reference_provider(self):
        workflow = (ROOT / ".github/workflows/rv32c-coremark.yml").read_text()
        self.assertIn("ensure_rv32c_spike_reference.sh", workflow)
        self.assertNotIn("ensure_rv32_spike_single_step.sh", workflow)
        self.assertNotIn("probe_rv32_spike_deterministic.sh", workflow)
        self.assertIn("frozen exact Spike RV32IMC reference provider", workflow)

    def test_workflow_requires_real_compressed_code_and_exact_spike_difftest(self):
        workflow = (ROOT / ".github/workflows/rv32c-coremark.yml").read_text()
        self.assertIn("compressed_instructions=[1-9][0-9]*", workflow)
        self.assertIn("ensure_riscv_none_elf_gcc_15_2.sh", workflow)
        self.assertIn("ensure_rv32c_spike_reference.sh", workflow)
        self.assertIn("Makefile.rv32imc-coremark", workflow)
        self.assertIn("chmod +x ./mill", workflow)
        self.assertIn('RV32_REFERENCE_SO="$RV32_REFERENCE_SO" run', workflow)
        self.assertNotIn("ensure_rv32_nemu_single_step.sh", workflow)


if __name__ == "__main__":
    unittest.main()
