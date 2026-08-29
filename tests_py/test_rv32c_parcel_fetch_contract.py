from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32cParcelFetchContract(unittest.TestCase):
    def test_state_stays_above_mmu_and_pmp(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        parcel = (ROOT / "src/main/scala/aethercore/core/Rv32CParcelController.scala").read_text()
        self.assertIn("fetch.io.virtualAddress := fetchVirtualAddress", core)
        self.assertNotIn("fetch.io.virtualAddress := fetchVirtualAddress(31, 0)", core)
        self.assertIn("fetchVirtualAddress := parcel.io.parcelRequestAddress", core)
        self.assertIn("instructionPmp.io.bytes := instructionTransactionBytes", core)
        self.assertIn("io.instructionPc + 2.U", parcel)
        self.assertIn("when(io.kill)", parcel)

    def test_completed_instruction_facts_feed_if_id(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        self.assertEqual(core.count("when(fetchInstructionValid)"), 2)
        self.assertEqual(core.count("ifId.inst := fetchedInst"), 2)
        self.assertEqual(core.count("ifId.rawInst := fetchedRawInst"), 2)
        self.assertEqual(core.count("ifId.fault := fetchInstructionAccessFault"), 2)

    def test_c_frontend_is_cross_xlen_while_rv64_profiles_remain_unqualified(self):
        config = (ROOT / "src/main/scala/aethercore/config/CoreConfig.scala").read_text()
        parcel = (ROOT / "src/main/scala/aethercore/core/Rv32CParcelController.scala").read_text()
        matrix = (ROOT / "src/test/scala/aethercore/V2ArchitecturalProfileMatrixSpec.scala").read_text()

        self.assertIn("Set('I', 'M', 'A', 'C')", config)
        self.assertNotIn("!isa.hasC || isa.xlen == 32", config)
        self.assertIn("class RvcParcelController(val xlen: Int = 32) extends Module", parcel)
        self.assertIn("require(Set(32, 64).contains(xlen)", parcel)
        self.assertIn("val decompressor = Module(new RvcDecompressor(xlen))", parcel)
        self.assertIn("val rv32imcSoftware", config)

        # CoreConfig/RTL capability is broader than the named qualification matrix.
        self.assertIn("CoreProfiles.rv64imCurrent.isa.hasC shouldBe false", matrix)
        self.assertIn("CoreProfiles.rv64imasuSv39PmpSoftware.isa.hasC shouldBe false", matrix)


if __name__ == "__main__":
    unittest.main()
