from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32cRetireLengthContract(unittest.TestCase):
    def test_commit_trace_carries_architectural_instruction_length(self):
        interfaces = (ROOT / "src/main/scala/aethercore/common/Interfaces.scala").read_text()
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        sim = (ROOT / "sim/sim_main.cpp").read_text()
        header = (ROOT / "sim/nemu_difftest.h").read_text()
        self.assertIn("val instBytes = UInt(3.W)", interfaces)
        self.assertIn("io.commit.instBytes := memWb.instBytes", core)
        self.assertIn("std::uint8_t instBytes = 4", header)
        self.assertIn("top.io_commit_instBytes", sim)

    def test_reference_instruction_reads_and_shadow_pc_are_length_aware(self):
        generic = (ROOT / "sim/nemu_difftest.cpp").read_text()
        rv32 = (ROOT / "sim/nemu_difftest_rv32.cpp").read_text()
        timer = (ROOT / "sim/nemu_difftest_rv32_timer.cpp").read_text()
        self.assertIn("instructionAt(commit.pc, commit.instBytes)", generic)
        self.assertIn("instructionAt(commitPc, commit.instBytes)", rv32)
        self.assertIn("instructionAt(commitPc, commit.instBytes)", timer)
        self.assertIn("after.pc = before.pc + instBytes", rv32)
        self.assertIn("after.pc = before.pc + instBytes", timer)
        self.assertNotIn("after.pc = before.pc + 4", rv32)
        self.assertNotIn("after.pc = before.pc + 4", timer)

    def test_pmp_shadow_uses_architectural_length_for_bare_instruction_coverage(self):
        pmp = (ROOT / "tools/make_rv32imu_pmp_difftest.py").read_text()
        self.assertIn("pmpAllows(pc, commit.instBytes, false, true)", pmp)
        self.assertNotIn("pmpAllows(pc, 4, false, true)", pmp)

    def test_retire_length_does_not_open_c_or_conflate_physical_transport(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        self.assertIn("val fetchedInstBytes = 4.U(3.W)", core)
        self.assertIn("val instructionTransactionBytes = 4.U(3.W)", core)
        self.assertIn("io.imem.bytes := instructionTransactionBytes", core)
        self.assertNotIn("io.imem.bytes := memWb.instBytes", core)


if __name__ == "__main__":
    unittest.main()
