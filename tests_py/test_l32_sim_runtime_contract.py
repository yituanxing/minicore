from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "sim/l32_opensbi_runtime.h"
COLD = ROOT / "sim/opensbi_boot_main.cpp"
FORKSERVER = ROOT / "sim/opensbi_forkserver_main.cpp"
MAKEFILE = ROOT / "Makefile.l32-linux-boot"
ARCH_AB = ROOT / "tools/ci/v2_p8_arch_ab.sh"


class L32SimRuntimeContractTest(unittest.TestCase):
    def test_shared_runtime_owns_the_physical_memory_protocol(self):
        text = RUNTIME.read_text()
        for required in (
            "class Memory",
            "kRamBase = 0x80000000ULL",
            "kRamSize = 256ULL * 1024ULL * 1024ULL",
            "std::uint32_t readInstruction",
            "std::uint64_t readData",
            "void writeMasked",
            # Historical RV32 callers retain the narrow compatibility wrapper,
            # while the shared transport itself is width-generic.
            "void write32Masked",
            "dataBytesFromMemSize",
            "void driveMemory",
            "bool dataReady = true",
            "top.io_imemAddr",
            "top.io_imemBytes",
            "memory.readInstruction(iaddr, ibytes)",
            "top.io_memValid",
            "top.io_memSize",
            "memory.contains(daddr, dbytes)",
            "top.io_memReady = dataReady",
            "memory.readData(daddr, dbytes)",
            "top.io_ptwValid",
            "constexpr std::size_t ptwBytes = sizeof(top.io_ptwRdata);",
            "memory.contains(ptwAddr, ptwBytes)",
            "top.io_ptwReady = true",
            "memory.readData(ptwAddr, ptwBytes)",
            "AETHERCORE_DATA_MEM_WAIT_CYCLES",
            "dataReadyThisLowPhase",
            "observedStallCycles",
            "AETHERCORE_DATA_MEM_WAIT_QUALIFIED",
            "bool step",
            "void initialize",
        ):
            self.assertIn(required, text)

        # Preserve the qualified simulator ordering while requiring accepted
        # stores to use the same architectural MemSize-derived width as reads.
        low = text.index("top.clock = 0;")
        first_eval = text.index("top.eval();", low)
        second_eval = text.index("top.eval();", first_eval + 1)
        store_width = text.index("const auto dbytes = dataBytesFromMemSize", second_eval)
        store = text.index("memory.writeMasked", store_width)
        high = text.index("top.clock = 1;", store)
        high_eval = text.index("top.eval();", high)
        time_inc = text.index("context.timeInc(1);", high_eval)
        self.assertLess(low, first_eval)
        self.assertLess(first_eval, second_eval)
        self.assertLess(second_eval, store_width)
        self.assertLess(store_width, store)
        self.assertLess(store, high)
        self.assertLess(high, high_eval)
        self.assertLess(high_eval, time_inc)

        store_call = text[store:high]
        self.assertIn("static_cast<std::uint64_t>(top.io_memAddr)", store_call)
        self.assertIn("static_cast<std::uint64_t>(top.io_memWdata)", store_call)
        self.assertIn("static_cast<std::uint64_t>(top.io_memWmask)", store_call)
        self.assertIn("dbytes", store_call)

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

    def test_arch_ab_refuses_an_unqualified_memory_wait_overlay(self):
        text = ARCH_AB.read_text()
        self.assertIn("install_memory_wait_overlay", text)
        self.assertIn(
            "env AETHERCORE_DATA_MEM_WAIT_CYCLES={wait} $(OBJ_DIR)/V$(TOP)",
            text,
        )
        self.assertIn("AETHERCORE_ARCH_AB_MEMORY_EXEC_OVERLAY", text)
        self.assertIn("AETHERCORE_DATA_MEM_WAIT_QUALIFIED configured=", text)
        self.assertIn("observed_stall_cycles=$DATA_MEM_WAIT_CYCLES", text)
        self.assertIn("AETHERCORE_ARCH_AB_MEMORY_QUALIFIED", text)
        self.assertIn("exit 15", text)

    def test_arch_ab_persists_qualified_linux_software_cache(self):
        workflow = (ROOT / ".github/workflows/v2-p8-architecture-ab.yml").read_text()
        self.assertIn("id: rv64-software-cache", workflow)
        self.assertIn("uses: actions/cache/save@v4", workflow)
        self.assertIn("steps.rv64-software-cache.outputs.cache-hit != 'true'", workflow)
        self.assertGreaterEqual(workflow.count("~/.cache/aethercore/rv64/linux-build"), 2)
        self.assertGreaterEqual(workflow.count("~/.cache/aethercore/l32/opensbi"), 2)

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
