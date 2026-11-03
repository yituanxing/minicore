"""Hardware G1 integer and M FUs use shared ALU/divider, with no Tiny ownership."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ALU = ROOT / "src/main/scala/aethercore/core/ALU.scala"
INT = ROOT / "src/main/scala/aethercore/core/product/ProductIntegerBranchUnit.scala"
M = ROOT / "src/main/scala/aethercore/core/product/ProductMUnit.scala"
DIV = ROOT / "src/main/scala/aethercore/core/arith/UnsignedRestoringDivider.scala"
V2 = ROOT / "src/main/scala/aethercore/core/v2/TinyExecution.scala"
BACKEND = ROOT / "src/main/scala/aethercore/core/product/ProductSmallOoOBackend.scala"
SPEC = ROOT / "src/test/scala/aethercore/ProductSmallIntegerCoreSpec.scala"
MSPEC = ROOT / "src/test/scala/aethercore/ProductMUnitSpec.scala"


class ProductIntegerExecutionContract(unittest.TestCase):
    def test_shared_integer_alu_elaboration_disables_fpga_div_and_reuses_mul(self):
        source = ALU.read_text()
        self.assertIn("val withMulDiv: Boolean = true", source)
        self.assertIn("val withMultiply: Boolean = true", source)
        self.assertIn("val withDivide: Boolean = true", source)
        for frag in ("if (mulEnabled) (aSignedExt * bSignedExt)",
                     "if (divEnabled) (signedDividend / signedDivisor)",
                     "if (divEnabled) io.a / unsignedDivisor",
                     "if (mulEnabled) a32 * b32"):
            self.assertIn(frag, source)
        self.assertIn("new ALU(Xlen, withMulDiv = false)", INT.read_text())
        self.assertIn("withMultiply = true, withDivide = false", M.read_text())

    def test_only_one_restoring_division_algorithm_with_v2_and_product_adapters(self):
        canonical = DIV.read_text()
        legacy = V2.read_text()
        product = M.read_text()
        self.assertIn("class UnsignedRestoringDivider", canonical)
        self.assertIn("val nextRemainder", canonical)
        self.assertIn("new aethercore.core.arith.UnsignedRestoringDivider(xlen)", legacy)
        self.assertIn("new UnsignedRestoringDivider(Xlen)", product)
        self.assertNotIn("val nextRemainder", legacy)
        self.assertNotIn("val nextRemainder", product)
        self.assertNotIn("import aethercore.core.v2", product)

    def test_fu_availability_is_observed_before_iq_choice(self):
        source = INT.read_text()
        backend = BACKEND.read_text()
        for fragment in ("class ProductSmallIntegerCore", "new ProductMUnit(g, PA)",
                         "new ProductIntegerBranchUnit(g",
                         "completion.io.in(0) <> integer.io.response",
                         "completion.io.in(1) <> m.io.response",
                         "backend.io.completion <> completion.io.out",
                         "backend.io.integerAvailable := integer.io.available",
                         "backend.io.mAvailable := m.io.available",
                         "io.mBusy := !m.io.available",
                         "io.mUnitNeeded := false.B"):
            self.assertIn(fragment, source)
        self.assertIn("val fuAvailable = Mux(e.memoryKind =/= MemoryOperationKind.None", backend)
        self.assertIn("io.mAvailable, io.integerAvailable", backend)
        self.assertIn("iqReady(j) := e.valid && fuAvailable", backend)
        self.assertIn("private val prf = RegInit", backend)

    def test_memory_and_distro_are_still_not_falsely_claimed(self):
        self.assertIn("io.unsupported := backend.io.unsupported", INT.read_text())
        self.assertIn("dispatchDecoded.memory.kind === MemoryOperationKind.None", BACKEND.read_text())
        config = (ROOT / "src/main/scala/aethercore/core/product/ProductCoreConfig.scala").read_text()
        self.assertIn("rtlImplemented: Boolean = false", config)

    def test_real_timing_scenarios_in_chisel_and_legacy_divider_gate(self):
        tests = SPEC.read_text()
        for fragment in ("actually execute ADDI dependency", "real BEQ redirect",
                         "overtake a busy divide", "execute MUL/DIV/REM",
                         "reject memory instructions"):
            self.assertIn(fragment, tests)
        self.assertTrue(MSPEC.is_file())
        gate = (ROOT / ".github/workflows/v2-f7-frontier.yml").read_text()
        for name in ("aethercore.ProductSmallOoOBackendSpec",
                     "aethercore.ProductSmallIntegerCoreSpec",
                     "aethercore.ProductMUnitSpec", "aethercore.V2F3ExecutionSpec",
                     "aethercore.ALUSpec", "aethercore.RV64MALUSpec"):
            self.assertIn(name, gate)


if __name__ == "__main__":
    unittest.main()
