"""Keep the optional FPGA floating commit trace an exact retirement observer.

Scala simulation owns functional proof; these source guards protect the
architectural boundary between legacy no-F SoCs and a future RV64GC product.
"""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / "src/main/scala/aethercore/common/Interfaces.scala"
PRODUCT = ROOT / "src/main/scala/aethercore/core/product"
SOC = ROOT / "src/main/scala/aethercore/soc"


class ProductFpCommitTraceContract(unittest.TestCase):
    def test_floating_observer_is_optional_and_legacy_defaults_remain_intact(self):
        source = COMMON.read_text()
        self.assertIn("val withFloating: Boolean = false", source)
        self.assertIn("val floating = if (withFloating) Some(new FloatingCommitTrace(xlen)) else None", source)
        self.assertIn("class FloatingCommitTrace(val xlen: Int) extends Bundle", source)
        for field in ("rdWrite", "rd", "rdData", "fflags", "stateDirty"):
            self.assertIn(f"val {field} =", source)
        for name in ("AetherCore.scala", "v2/TinyRobCommit.scala"):
            legacy = ROOT / "src/main/scala/aethercore/core" / name
            self.assertNotIn("withFloating = true", legacy.read_text())

    def test_product_paged_and_three_soc_wrappers_share_exact_feature_switch(self):
        paged = (PRODUCT / "ProductSmallPagedCore.scala").read_text()
        self.assertIn("withFloating = isa.extensions.contains('F')", paged)
        for name in (
            "AetherCoreProductUnifiedMemorySoC.scala",
            "AetherCoreProductAxi4SoC.scala",
            "AetherCoreProductFpgaSoC.scala",
        ):
            source = (SOC / name).read_text()
            self.assertIn("withFloating = config.software.isa.extensions.contains('F')", source)

    def test_only_retired_and_nonfaulting_instruction_can_emit_float_trace(self):
        helper = (PRODUCT / "ProductFloatingRetirementTrace.scala").read_text()
        self.assertIn("val cleanRetirement = retiring && !retirement.exception.valid", helper)
        self.assertIn("retirement.wroteFloatingRegister", helper)
        self.assertIn("trace.rd := Mux(writesFloating, retirement.rd, 0.U(5.W))", helper)
        self.assertIn("trace.fflags := Mux(cleanRetirement, retirement.fflags, 0.U(5.W))", helper)
        self.assertIn("retirement.fpDirty || fpCsrWrite", helper)
        self.assertNotIn("io.completion", helper)

    def test_exact_observer_has_dynamic_tests_in_product_g2(self):
        spec = (ROOT / "src/test/scala/aethercore/ProductFloatingCommitTraceSpec.scala").read_text()
        for scenario in (
            "preserve the exact legacy no-F bundle geometry",
            "report writable f0",
            "retain flags for FP to integer results",
            "trace legal fflags frm fcsr CSR writes",
            "drop speculative and faulting FP trace side effects",
        ):
            self.assertIn(scenario, spec)
        workflow = (ROOT / ".github/workflows/product-g2-soc.yml").read_text()
        self.assertIn("aethercore.ProductFloatingCommitTraceSpec", workflow)


if __name__ == "__main__":
    unittest.main()
