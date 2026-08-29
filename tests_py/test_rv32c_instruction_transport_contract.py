from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32cInstructionTransportContract(unittest.TestCase):
    def test_physical_instruction_bus_carries_profile_selected_transaction_width(self):
        interfaces = (ROOT / "src/main/scala/aethercore/common/Interfaces.scala").read_text()
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        self.assertIn("val bytes = Output(UInt(3.W))", interfaces)
        self.assertIn("if (config.isa.hasC) 2.U(3.W) else 4.U(3.W)", core)
        self.assertIn("io.imem.bytes := instructionTransactionBytes", core)
        self.assertIn("instructionPmp.io.bytes := instructionTransactionBytes", core)
        self.assertNotIn("io.imem.bytes := fetchedInstBytes", core)

    def test_all_instruction_memory_drivers_honor_transaction_width(self):
        for relative in (
            "sim/sim_main.cpp",
            "sim/nuttx_paging_boot_main.cpp",
            "sim/l32_opensbi_runtime.h",
            "sim/linux_handoff_main.cpp",
        ):
            with self.subTest(path=relative):
                text = (ROOT / relative).read_text()
                self.assertIn("top.io_imemBytes", text)
                self.assertIn("memory.readInstruction(iaddr, ibytes)", text)
                self.assertIn("ibytes != 2 && ibytes != 4", text)

    def test_architectural_length_and_physical_width_remain_separate(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        self.assertIn("fetchedInstBytes := parcel.io.instructionBytes", core)
        self.assertIn("val instructionTransactionBytes", core)
        self.assertNotIn("io.imem.bytes := fetchedInstBytes", core)


if __name__ == "__main__":
    unittest.main()
