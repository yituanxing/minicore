from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32cParcelControllerContract(unittest.TestCase):
    def test_controller_stays_above_translation_and_pmp(self):
        parcel = (ROOT / "src/main/scala/aethercore/core/Rv32CParcelController.scala").read_text()
        self.assertNotIn("CoreConfig", parcel)
        self.assertNotIn("Sv32", parcel)
        self.assertNotIn("Pmp", parcel)
        self.assertNotIn("AetherCore", parcel)
        self.assertIn("io.instructionPc + 2.U", parcel)
        self.assertIn('io.parcelBits(1, 0) =/= "b11".U', parcel)

    def test_one_and_two_parcel_facts_are_explicit(self):
        parcel = (ROOT / "src/main/scala/aethercore/core/Rv32CParcelController.scala").read_text()
        self.assertIn("val secondParcelPending = RegInit(false.B)", parcel)
        self.assertIn("val firstParcel = RegInit(0.U(16.W))", parcel)
        self.assertIn("val assembled32 = Cat(io.parcelBits, firstParcel)", parcel)
        self.assertIn("io.instructionBytes := Mux(secondParcelPending, 4.U, 2.U)", parcel)
        self.assertIn("when(io.kill)", parcel)

    def test_fault_address_tracks_the_failing_parcel(self):
        parcel = (ROOT / "src/main/scala/aethercore/core/Rv32CParcelController.scala").read_text()
        self.assertIn("io.faultAddress := Mux(", parcel)
        self.assertIn("io.instructionPc + 2.U", parcel)
        self.assertIn("io.pageFault := io.parcelPageFault", parcel)
        self.assertIn("io.accessFault := io.parcelAccessFault", parcel)

    def test_controller_is_integrated_without_owning_translation_or_pmp(self):
        core = (ROOT / "src/main/scala/aethercore/core/AetherCore.scala").read_text()
        config = (ROOT / "src/main/scala/aethercore/config/CoreConfig.scala").read_text()
        self.assertIn("Some(Module(new Rv32CParcelController(xlen)))", core)
        self.assertIn("fetchVirtualAddress := parcel.io.parcelRequestAddress", core)
        self.assertIn("parcel.io.parcelPageFault := fetchPageFault", core)
        self.assertIn("parcel.io.parcelAccessFault := physicalParcelAccessFault", core)
        self.assertIn("Set('I', 'M', 'A', 'C')", config)


if __name__ == "__main__":
    unittest.main()
