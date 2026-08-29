from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32cFetchFaultAddressContract(unittest.TestCase):
    def test_fetch_fault_address_is_parcel_owned(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        parcel = (ROOT / "src/main/scala/aethercore/core/Rv32CParcelController.scala").read_text()
        self.assertIn("val faultAddress = UInt(xlen.W)", core)
        self.assertIn("fetchFaultAddress := parcel.io.faultAddress", core)
        self.assertEqual(core.count("ifId.faultAddress := fetchFaultAddress"), 2)
        self.assertIn("io.instructionPc + 2.U", parcel)

    def test_instruction_fault_traps_consume_fault_address_not_start_pc(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        self.assertEqual(core.count("decodedTrap.value := ifId.faultAddress"), 2)

    def test_c_frontend_uses_two_byte_physical_parcels(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        self.assertIn("if (config.isa.hasC) 2.U(3.W) else 4.U(3.W)", core)


if __name__ == "__main__":
    unittest.main()
