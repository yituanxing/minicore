from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32cFetchFaultAddressContract(unittest.TestCase):
    def test_fetch_fault_address_is_an_explicit_if_id_fact(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        self.assertIn("val faultAddress = UInt(xlen.W)", core)
        self.assertIn("val fetchFaultAddress = WireDefault(pc)", core)
        self.assertEqual(core.count("ifId.faultAddress := fetchFaultAddress"), 2)

    def test_instruction_fault_traps_consume_fault_address_not_start_pc(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        self.assertEqual(core.count("decodedTrap.value := ifId.faultAddress"), 2)
        page_block = core.split("when(ifId.pageFault)", 1)[1].split("}.elsewhen(ifIdSfenceVma", 1)[0]
        self.assertNotIn("decodedTrap.value := ifId.pc", page_block)

    def test_current_frontend_behavior_remains_four_byte_and_c_fail_closed(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        config = (ROOT / "src/main/scala/aethercore/config/CoreConfig.scala").read_text()
        self.assertIn("val fetchedInstBytes = 4.U(3.W)", core)
        self.assertIn("val instructionTransactionBytes = 4.U(3.W)", core)
        self.assertIn("val instructionExtensions: Set[Char] = Set('I', 'M', 'A')", config)


if __name__ == "__main__":
    unittest.main()
