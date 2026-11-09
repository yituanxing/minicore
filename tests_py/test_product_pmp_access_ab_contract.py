"""Fail-closed contracts for the isolated PMP priority-network XC7 A/B."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "src/main/scala/aethercore/core/PmpChecker.scala"
PROBE = ROOT / "src/main/scala/aethercore/ElaboratePmpAccessCheckerRV64.scala"
EXPERIMENT_PROBE = ROOT / "src/main/scala/aethercore/ElaboratePmpAccessCheckerOneHotRV64.scala"
SPEC = ROOT / "src/test/scala/aethercore/PmpAccessCheckerPrioritySpec.scala"
WORKFLOW = ROOT / ".github/workflows/product-pmp-local-xc7.yml"


class ProductPmpAccessAbContract(unittest.TestCase):
    def test_preserves_first_hit_permission_and_partial_overlap(self):
        checker = CHECKER.read_text()
        self.assertIn("oneHotPriorityExperiment: Boolean = false", checker)
        self.assertIn("if (oneHotPriorityExperiment)", checker)
        self.assertIn("for (entry <- (0 until entries).reverse)", checker)
        self.assertIn("PriorityEncoderOH(overlaps.asUInt)", checker)
        self.assertIn("OHToUInt(firstHit)", checker)
        self.assertIn("(firstHit & entryAllows.asUInt).orR", checker)
        self.assertIn("fullyContained && (machineBypass || permission)", checker)
        self.assertIn("io.allow := Mux(matched, allowedByFirstHit,", checker)
        self.assertIn("&& !invalidRange", checker)

    def test_exposes_independent_checker_without_claiming_board_fit(self):
        probe = PROBE.read_text()
        experimental = EXPERIMENT_PROBE.read_text()
        self.assertIn("new PmpAccessChecker(PmpConstants.MaxEntries, 56)", probe)
        self.assertIn("oneHotPriorityExperiment = true", experimental)
        self.assertIn("ChiselStage.emitSystemVerilogFile", experimental)
        self.assertIn("ChiselStage.emitSystemVerilogFile", probe)
        self.assertNotIn("ProductSmallPagedCore", probe)

    def test_priority_oracle_and_same_mapper_comparison_are_wired(self):
        spec = SPEC.read_text()
        workflow = WORKFLOW.read_text()
        self.assertIn("for (trial <- 0 until 300)", spec)
        self.assertIn("Seq(false, true)", spec)
        self.assertIn("exercise(8, oneHot)", spec)
        self.assertIn("exercise(56, oneHot)", spec)
        self.assertIn("e.active && !invalid && address < e.upper && end >= e.lower", spec)
        self.assertIn("aethercore.PmpAccessCheckerPrioritySpec", workflow)
        self.assertIn("ElaboratePmpAccessCheckerRV64", workflow)
        self.assertIn("ElaboratePmpAccessCheckerOneHotRV64", workflow)
        self.assertIn("aethercore.runMain aethercore.ElaboratePmpAccessCheckerOneHotRV64", workflow)
        self.assertIn("aethercore.runMain aethercore.ElaboratePmpAccessCheckerRV64", workflow)
        self.assertIn("5166bfcc144d7419e1c4eeb354237a457b13680c", workflow)
        self.assertIn("yosys-checker-baseline.log", workflow)
        self.assertIn("yosys-checker.log", workflow)
        self.assertIn("synth_xilinx -family xc7 -noiopad -top PmpAccessChecker", workflow)
        self.assertIn("checker-lut-comparison.txt", workflow)
        self.assertIn("no whole-SoC saving or Fmax claim", workflow)
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("  push:", workflow)


if __name__ == "__main__":
    unittest.main()
