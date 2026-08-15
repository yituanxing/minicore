from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "sim/l32_opensbi_runtime.h"
COLD = ROOT / "sim/opensbi_boot_main.cpp"
FORKSERVER = ROOT / "sim/opensbi_forkserver_main.cpp"
MAKEFILE = ROOT / "Makefile.l32-linux-boot"
SIM_TOP = ROOT / "src/main/scala/aethercore/sim/AetherCoreOpenSbiSimTop.scala"


class L32SimRuntimeContractTest(unittest.TestCase):
    def test_shared_runtime_owns_the_physical_memory_protocol(self):
        text = RUNTIME.read_text()
        for required in (
            "class Memory",
            "kRamBase = 0x80000000ULL",
            "kRamSize = 256ULL * 1024ULL * 1024ULL",
            "void driveMemory",
            "top.io_imemAddr",
            "top.io_imemInst",
            "top.io_memValid",
            "top.io_memReady = true",
            "top.io_memRdata",
            "top.io_ptwValid",
            "top.io_ptwReady = true",
            "top.io_ptwRdata",
            "void write32Masked",
            "bool step",
            "void initialize",
        ):
            self.assertIn(required, text)

        low = text.index("top.clock = 0;")
        first_eval = text.index("top.eval();", low)
        second_eval = text.index("top.eval();", first_eval + 1)
        store = text.index("memory.write32Masked", second_eval)
        high = text.index("top.clock = 1;", store)
        high_eval = text.index("top.eval();", high)
        time_inc = text.index("context.timeInc(1);", high_eval)
        self.assertLess(low, first_eval)
        self.assertLess(first_eval, second_eval)
        self.assertLess(second_eval, store)
        self.assertLess(store, high)
        self.assertLess(high, high_eval)
        self.assertLess(high_eval, time_inc)

    def test_cold_and_warm_runners_share_transport_but_keep_their_oracles(self):
        cold = COLD.read_text()
        forkserver = FORKSERVER.read_text()

        for text in (cold, forkserver):
            self.assertIn('#include "l32_opensbi_runtime.h"', text)
            self.assertIn("using aethercore::l32sim::Memory;", text)
            self.assertIn("using aethercore::l32sim::initialize;", text)
            self.assertIn("using aethercore::l32sim::step;", text)
            self.assertIn("loadAtBase", text)
            self.assertIn("initialize(", text)
            self.assertNotIn("class Memory", text)
            self.assertNotIn("void driveMemory", text)

        self.assertIn("const bool rxAccepted = step(", cold)
        for marker in (
            "L32_OPENSBI_BANNER",
            "L32_RUNTIME_MILESTONE_PASS",
            "L32_FIRST_INTERRUPT",
            "L32_FIRST_SUPERVISOR_EXTERNAL_INTERRUPT",
            "L32_UART_INPUT_SEIP",
        ):
            self.assertIn(marker, cold)

        self.assertIn("const bool accepted = step(top, ctx, mem, rxValid, rxByte);", forkserver)
        self.assertIn("record(top, c);", forkserver)
        for marker in (
            "top.prepareClone();",
            "const pid_t pid = ::fork();",
            "top.atClone();",
            "L32_FORKSERVER_READY",
            "L32_FORKSERVER_CASE_PASS",
            "L32_FORKSERVER_PASS",
        ):
            self.assertIn(marker, forkserver)

    def test_linux_shell_uses_composed_sv32_pmp_profile(self):
        text = SIM_TOP.read_text()
        self.assertIn("CoreProfiles.rv32imasuSv32PmpSoftware", text)
        self.assertNotIn("CoreProfiles.rv32imasuSv32Software,", text)

    def test_makefile_keeps_cold_and_forkserver_as_separate_scenarios(self):
        text = MAKEFILE.read_text()
        self.assertIn("sim/opensbi_boot_main.cpp", text)
        self.assertIn("sim/opensbi_forkserver_main.cpp", text)
        self.assertIn("OBJ_DIR := $(BUILD_DIR)/obj", text)
        self.assertIn("FORKSERVER_OBJ_DIR := $(BUILD_DIR)/obj-forkserver", text)
        self.assertIn("run-local: $(RUN_DEPS)", text)
        self.assertIn("forkserver-local: $(FORKSERVER_RUN_DEPS)", text)


if __name__ == "__main__":
    unittest.main()
