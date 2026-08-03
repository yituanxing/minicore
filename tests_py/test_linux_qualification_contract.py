from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "docs" / "linux-qualification.md"


class LinuxQualificationContractTest(unittest.TestCase):
    def test_claims_require_architecture_boot_runtime_stress_and_negative_evidence(self) -> None:
        text = CONTRACT.read_text(encoding="utf-8")
        for heading in (
            "Construction",
            "Architectural execution",
            "Linux boot semantics",
            "Userspace behavior",
            "Stress and negative evidence",
        ):
            self.assertIn(heading, text)
        self.assertIn("retirement-by-retirement differential testing", text)
        self.assertIn("upstream `ARCH=riscv defconfig`", text)
        self.assertIn("kselftest and LTP subsets", text)
        self.assertIn("deliberate architectural mismatch", text)

    def test_partial_A_is_not_advertised_and_linux_target_is_rv64(self) -> None:
        text = CONTRACT.read_text(encoding="utf-8")
        self.assertIn("complete RV64 A (`Zalrsc` and `Zaamo`)", text)
        self.assertIn("without advertising partial A support", text)
        self.assertIn("Sv39", text)
        self.assertIn("OpenSBI", text)

    def test_final_claim_is_scoped_to_exact_artifacts_and_suite(self) -> None:
        text = CONTRACT.read_text(encoding="utf-8")
        self.assertIn("Linux `<release>` upstream RISC-V defconfig", text)
        self.assertIn("AetherCore `<profile>` / platform `<version>`", text)
        self.assertIn("qualification suite `<contract>`", text)
        self.assertIn("must never be shortened", text)


if __name__ == "__main__":
    unittest.main()
