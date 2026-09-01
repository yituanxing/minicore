import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
ADDRESS_MAP = ROOT / "src/main/scala/aethercore/soc/AetherSoCAddressMap.scala"
BOOT_ROM = ROOT / "src/main/scala/aethercore/soc/AetherSoCBootRom.scala"
PLATFORM = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2LinuxSoC.scala"
UNIFIED = ROOT / "src/main/scala/aethercore/soc/AetherCoreV2UnifiedMemorySoC.scala"


class V2SoCBootRomSourceContract(unittest.TestCase):
    def test_reset_vector_targets_the_named_bootrom_window(self):
        address_map = ADDRESS_MAP.read_text(encoding="utf-8")
        platform = PLATFORM.read_text(encoding="utf-8")

        self.assertIn(
            'val QualifiedBootRomBase: BigInt = BigInt("00001000", 16)',
            address_map,
        )
        self.assertIn(
            "resetVector = AetherSoCAddressMap.QualifiedBootRomBase",
            platform,
        )
        self.assertNotIn(
            'resetVector = BigInt("80000000", 16)',
            platform,
        )

    def test_qualified_soc_uses_predecoded_local_offset_bootrom(self):
        rom = BOOT_ROM.read_text(encoding="utf-8")
        unified = UNIFIED.read_text(encoding="utf-8")
        self.assertIn("requestAlreadyDecoded: Boolean = false", rom)
        self.assertIn("private val localOffsetBits =", rom)
        self.assertIn("if (requestAlreadyDecoded) address", rom)
        self.assertIn("requestAlreadyDecoded = true", unified)

    def test_bootrom_is_a_real_unified_memory_target_not_a_sim_wrapper(self):
        rom = BOOT_ROM.read_text(encoding="utf-8")
        unified = UNIFIED.read_text(encoding="utf-8")

        self.assertIn("class AetherSoCBootRom(", rom)
        self.assertIn("val request = Flipped(Decoupled(new AetherMemRequest", rom)
        self.assertIn("val response = Decoupled(new AetherMemResponse", rom)
        self.assertIn("0x93, 0x02, 0x10, 0x00", rom)
        self.assertIn("0x93, 0x92, 0xf2, 0x01", rom)
        self.assertIn("0x67, 0x80, 0x02, 0x00", rom)

        self.assertIn("val bootRom = Module(new AetherSoCBootRom(", unified)
        self.assertIn("val bootRomHit =", unified)
        self.assertIn(
            "bootRom.io.request.valid := hub.io.downstreamRequest.valid && bootRomHit",
            unified,
        )
        self.assertIn(
            "io.memoryRequest.valid := hub.io.downstreamRequest.valid && !bootRomHit",
            unified,
        )
        self.assertIn("responseArbiter.io.in(0) <> bootRom.io.response", unified)
        self.assertIn("hub.io.downstreamResponse <> responseArbiter.io.out", unified)

        self.assertNotIn("package aethercore.sim", rom)


if __name__ == "__main__":
    unittest.main()
