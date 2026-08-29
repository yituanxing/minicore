from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROVIDER_SHA = "22dd74006570af19495c6b5449eec908d246c0c6d700f7eabda16001a0ca62df"


class Rv32cFreeRtosContract(unittest.TestCase):
    def test_existing_freertos_defaults_remain_rv32im(self):
        makefile = (ROOT / "Makefile.freertos").read_text()
        self.assertIn("FREERTOS_MARCH ?= rv32im_zicsr", makefile)
        self.assertIn("TOP ?= AetherCoreRV32IMTrapSimTop", makefile)
        self.assertIn("ELABORATE_MAIN ?= aethercore.ElaborateRV32IMTrap", makefile)
        self.assertIn("-march=$(FREERTOS_MARCH)", makefile)

    def test_imc_wrapper_changes_only_isa_and_trap_top_profile(self):
        wrapper = (ROOT / "Makefile.freertos-rv32imc").read_text()
        self.assertIn("FREERTOS_MARCH := rv32imc_zicsr", wrapper)
        self.assertNotIn("rv32imac", wrapper)
        self.assertIn("TOP := AetherCoreRV32IMCTrapSimTop", wrapper)
        self.assertIn("ELABORATE_MAIN := aethercore.ElaborateRV32IMCTrap", wrapper)
        self.assertIn("DIFFTEST_RUNNER_TOOL := tools/make_freertos_rv32imc_difftest_runner.py", wrapper)
        self.assertIn("DIFFTEST_ADAPTER_POSTPROCESSOR := tools/apply_rv32_difftest_isa_profile.py", wrapper)
        self.assertIn("include Makefile.freertos-difftest", wrapper)

    def test_local_reset_bootstrap_stays_non_rvc_and_all_real_layers_use_c(self):
        startup = (ROOT / "software/freertos/aethercore/startup.S").read_text()
        wrapper = (ROOT / "Makefile.freertos-rv32imc").read_text()
        self.assertIn(".option norvc", startup)
        self.assertIn('test "$$startup" -eq 0', wrapper)
        for label in (
            "kernel_c_compressed",
            "app_c_compressed",
            "port_c_compressed",
            "port_asm_compressed",
            "linked_compressed",
        ):
            self.assertIn(label, wrapper)
        self.assertIn('test "$$kernel" -gt 0', wrapper)
        self.assertIn('test "$$app" -gt 0', wrapper)
        self.assertIn('test "$$port_c" -gt 0', wrapper)
        self.assertIn('test "$$port_asm" -gt 0', wrapper)
        self.assertIn('test "$$linked" -gt 0', wrapper)

    def test_imc_trap_top_preserves_machine_interrupt_platform(self):
        top = (ROOT / "src/main/scala/aethercore/sim/AetherCoreRV32IMCTrapSimTop.scala").read_text()
        elaborate = (ROOT / "src/main/scala/aethercore/ElaborateRV32IMCTrap.scala").read_text()
        self.assertIn("CoreProfiles.rv32imcSoftware", top)
        self.assertIn("stopOnTrap = false", top)
        self.assertIn("withMachineInterruptPlatform = true", top)
        self.assertIn("new AetherCoreRV32IMCTrapSimTop", elaborate)

    def test_rv32_difftest_profile_keeps_im_default_and_explicit_imc_opt_in(self):
        header = (ROOT / "sim/nemu_difftest.h").read_text()
        standalone_runner = (ROOT / "tools/make_freertos_rv32imc_runner.py").read_text()
        difftest_runner = (ROOT / "tools/make_freertos_rv32imc_difftest_runner.py").read_text()
        profile_adapter = (ROOT / "tools/apply_rv32_difftest_isa_profile.py").read_text()

        self.assertIn("Rv32DifftestIsaProfile", header)
        self.assertIn("0x40001100U, 4", header)
        self.assertIn("0x40001104U, 2", header)
        self.assertNotIn("Rv32DifftestIsaProfile::rv32imc()", standalone_runner)
        self.assertIn("Rv32DifftestIsaProfile::rv32imc()", difftest_runner)
        self.assertIn("profile_.misa", profile_adapter)
        self.assertIn("profile_.ialignBytes", profile_adapter)
        self.assertIn("alignInstructionAddress", profile_adapter)
        self.assertIn("isInstructionAddressAligned", profile_adapter)
        self.assertIn("Rv32DifftestIsaProfile::rv32im()", profile_adapter)
        self.assertNotIn("FreeRTOS has C", profile_adapter)

    def test_profile_postprocessor_fails_closed_on_exact_anchors(self):
        profile_adapter = (ROOT / "tools/apply_rv32_difftest_isa_profile.py").read_text()
        self.assertIn("expected exactly one", profile_adapter)
        self.assertIn("C bit and IALIGN disagree", profile_adapter)
        self.assertIn("ECALL trap PC violates active RV32 IALIGN", profile_adapter)
        self.assertIn("machine timer interrupt EPC violates active RV32 IALIGN", profile_adapter)

    def test_formal_workflow_consumes_provider_v2_without_reowning_its_abi(self):
        workflow = (ROOT / ".github/workflows/rv32c-freertos.yml").read_text()
        self.assertIn("resolve_rv32c_spike_reference.sh", workflow)
        self.assertIn(PROVIDER_SHA, workflow)
        self.assertNotIn("ensure_rv32_spike_single_step.sh", workflow)
        self.assertNotIn("probe_rv32_reference_abi.py", workflow)
        self.assertNotIn("spike_rv32_reference_shim.cpp", workflow)

    def test_formal_workflow_freezes_real_encoding_distribution_and_retirement_counts(self):
        workflow = (ROOT / ".github/workflows/rv32c-freertos.yml").read_text()
        for line in (
            "startup_compressed=0",
            "kernel_c_compressed=2763",
            "app_c_compressed=273",
            "port_c_compressed=29",
            "port_asm_compressed=356",
            "linked_compressed=1988",
            "difftest=175228",
            "zicsr-shadow=3435",
            "trap-shadow=217",
            "fence-shadow=1",
            "wfi-shadow=1",
            "mret-shadow=410",
            "interrupt-shadow=193",
        ):
            self.assertIn(line, workflow)
        self.assertIn("175228 committed instructions", workflow)
        self.assertIn("FreeRTOS Kernel V11.3.0, unchanged", workflow)


if __name__ == "__main__":
    unittest.main()
