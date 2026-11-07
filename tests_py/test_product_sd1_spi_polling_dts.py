"""Fail-closed DTS-only SPI-OC-Tiny polling candidate tests."""

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools/ci"))
from product_sd1_spi_polling_dts import make_polling_candidate


DTS = """
/dts-v1/;
/ {
    soc {
        serial@1000 {
            interrupt-parent = <&plic>;
            interrupts = <1>;
        };
        sd_cs_gpio: gpio@2000 {
            gpio-controller;
        };
        spi@3000 {
            compatible = "opencores,tiny-spi-rtlsvn2";
            reg = <0 0x3000 0 0x100>;
            clock-frequency = <50000000>;
            cs-gpios = <&sd_cs_gpio 0 1>;
            interrupt-parent = <&plic>;
            interrupts = <52>;
            mmc@0 {
                compatible = "mmc-spi-slot";
                spi-max-frequency = <12500000>;
            };
        };
    };
};
"""


class PollingDtsCandidate(unittest.TestCase):
    def test_only_spi_irq_properties_are_removed(self):
        candidate = make_polling_candidate(DTS)
        self.assertEqual(candidate.count("interrupt-parent"), 1)
        self.assertIn("serial@1000 {\n            interrupt-parent = <&plic>;\n            interrupts = <1>;", candidate)
        self.assertNotIn("interrupts = <52>;", candidate)
        self.assertIn("spi-max-frequency = <12500000>;", candidate)
        self.assertIn("cs-gpios = <&sd_cs_gpio 0 1>;", candidate)
        self.assertEqual(DTS.count("\n") - candidate.count("\n"), 2)

    def test_refuse_unqualified_no_spi_node(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            make_polling_candidate(DTS.replace("spi@3000 {", "dummy@3000 {"))

    def test_refuse_two_spi_nodes(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            make_polling_candidate(DTS.replace("spi@3000 {", "spi@3000 {\n        spi@4000 {"))

    def test_refuse_missing_optional_irq(self):
        with self.assertRaisesRegex(ValueError, "two direct"):
            make_polling_candidate(DTS.replace("interrupts = <52>;", "interrupts = <52>; // malformed"))

    def test_refuse_transforming_same_candidate_twice(self):
        with self.assertRaises(ValueError):
            make_polling_candidate(make_polling_candidate(DTS))


if __name__ == "__main__":
    unittest.main()
