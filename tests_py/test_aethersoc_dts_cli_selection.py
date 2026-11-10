"""Fast source-level guard for the Product SPI-SD DTS CLI contract.

The Scala resolver's behavior is also exercised by AetherSoCDtsSpec; this
check can run without downloading Mill's Scala build dependencies.
"""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
EMITTER = ROOT / "src/main/scala/aethercore/EmitAetherSoCDts.scala"
SPEC = ROOT / "src/test/scala/aethercore/AetherSoCDtsSpec.scala"
PORT_DOC = ROOT / "docs/architecture/PRODUCT_D3_SPI_SD_BOARD_PORT.md"
PAYLOAD = ROOT / "tools/ci/rv64_debian_payload_build.sh"


class ProductSdDtsCliSelection(unittest.TestCase):
    def test_product_spi_is_fail_closed_and_no_option_is_silently_ignored(self):
        source = EMITTER.read_text()
        self.assertIn("require(!spiSd || productBoard,", source)
        self.assertIn('"--spi-sd requires --product', source)
        self.assertIn("require(spiSd || (spiClockHz.isEmpty && spiMaxHz.isEmpty),", source)
        self.assertIn('"--spi-clock-hz and --spi-max-hz require --spi-sd"', source)
        self.assertIn("AetherSoCDtsBoardSelection.select(", source)
        self.assertIn("AetherCoreProductSoCProfiles.OpenSbiPlicSourceCount", source)

    def test_documented_sd_usage_preserves_product_plic_identity(self):
        doc = PORT_DOC.read_text()
        self.assertIn("--mmu sv39 --product --spi-sd", doc)
        payload = PAYLOAD.read_text()
        self.assertIn("export RV64_PAYLOAD_PRODUCT_BOARD=1", payload)
        self.assertIn("export RV64_PAYLOAD_ENABLE_SPI_SD=1", payload)
        spec = SPEC.read_text()
        for contract in (
            "--spi-sd requires --product",
            "select Product PLIC 52",
            "preserve legacy and RAM-only Product",
        ):
            self.assertIn(contract, spec)


if __name__ == "__main__":
    unittest.main()
