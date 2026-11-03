"""Hardware G1 integer FU must reuse shared ALU, without FPGA combinational M."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ALU = ROOT / "src/main/scala/aethercore/core/ALU.scala"
FU = ROOT / "src/main/scala/aethercore/core/product/ProductIntegerBranchUnit.scala"
SPEC = ROOT / "src/test/scala/aethercore/ProductSmallIntegerCoreSpec.scala"

class ProductIntegerExecutionContract(unittest.TestCase):
    def test_shared_integer_alu_hardware_elaboration_is_specialized(self):
        source = ALU.read_text()
        self.assertIn("val withMulDiv: Boolean = true", source)
        for fragment in ("if (withMulDiv) (aSignedExt * bSignedExt)",
                         "if (withMulDiv) (signedDividend / signedDivisor)",
                         "if (withMulDiv) io.a / unsignedDivisor",
                         "if (withMulDiv) a32 * b32"):
            self.assertIn(fragment, source)
        self.assertIn("new ALU(Xlen, withMulDiv = false)", FU.read_text())

    def test_real_fu_and_backend_connection(self):
        source = FU.read_text()
        for fragment in ("class ProductIntegerBranchUnit", "class ProductSmallIntegerCore",
                         "new ProductSmallOoOBackend(g)", "new ProductIntegerBranchUnit(g",
                         "execute.io.request <> backend.io.issue",
                         "backend.io.completion <> execute.io.response",
                         "io.request.ready := (!held || io.response.ready) && !mInstruction",
                         "responseReg.branchValid := branch",
                         "responseReg.exception.valid := misaligned"):
            self.assertIn(fragment, source)

    def test_m_and_memory_fail_closed(self):
        source = FU.read_text()
        self.assertIn("io.unsupportedM := io.request.valid && mInstruction", source)
        self.assertIn("io.mUnitNeeded := execute.io.unsupportedM", source)
        self.assertIn("io.unsupported := backend.io.unsupported", source)
        config = (ROOT / "src/main/scala/aethercore/core/product/ProductCoreConfig.scala").read_text()
        self.assertIn("rtlImplemented: Boolean = false", config)

    def test_real_timing_scenarios_in_chisel_gate(self):
        source = SPEC.read_text()
        for text in ("simulate(new ProductIntegerCoreTestHarness)",
                     "actually execute ADDI dependency", "real BEQ redirect",
                     "fail closed on M", "reject memory instructions"):
            self.assertIn(text, source)
        gate = (ROOT / ".github/workflows/v2-f7-frontier.yml").read_text()
        for name in ("aethercore.ProductSmallOoOBackendSpec",
                     "aethercore.ProductSmallIntegerCoreSpec",
                     "aethercore.ALUSpec", "aethercore.RV64MALUSpec"):
            self.assertIn(name, gate)

if __name__ == "__main__":
    unittest.main()
