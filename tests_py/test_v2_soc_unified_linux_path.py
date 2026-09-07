import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
HOST = ROOT / "src/main/scala/aethercore/sim/AetherSoCUnifiedHostMemoryAdapter.scala"
COMPAT = ROOT / "src/main/scala/aethercore/sim/AetherCoreV2UnifiedMemoryCompatSimTop.scala"
LEGACY = ROOT / "src/main/scala/aethercore/sim/AetherCoreV2OpenSbiRV64SimTop.scala"


class V2UnifiedLinuxPathSourceContract(unittest.TestCase):
    def test_host_adapter_preserves_source_identity_and_product_read_concurrency(self):
        source = HOST.read_text(encoding="utf-8")
        self.assertIn("private val DataTxnCount = 1 << localTxnIdBits", source)
        self.assertIn("private val dataReadActive =", source)
        self.assertIn("RegInit(VecInit(Seq.fill(DataTxnCount)(false.B)))", source)
        self.assertIn("private val dataSerialActive = RegInit(false.B)", source)
        self.assertIn("private val ptwActive = RegInit(false.B)", source)
        self.assertIn("private val instructionActive = RegInit(false.B)", source)
        self.assertIn("!dataSerialActive && !dataReadActive(incomingLocalTxn)", source)
        self.assertIn("!dataSerialActive && allNormalReadsDrained", source)
        self.assertIn("PtwSource.U -> (!dataSerialActive && !ptwActive)", source)
        self.assertIn("InstructionSource.U -> (!dataSerialActive && !instructionActive)", source)
        self.assertIn("new RRArbiter(new AetherMemResponse", source)

    def test_compat_top_keeps_old_host_ports_but_contains_unified_soc(self):
        source = COMPAT.read_text(encoding="utf-8")
        self.assertIn("new AetherCoreV2UnifiedMemorySoC", source)
        self.assertIn("new AetherSoCUnifiedHostMemoryAdapter", source)
        for port in (
            "val imemValid = Output(Bool())",
            "val ptwValid = Output(Bool())",
            "val memValid = Output(Bool())",
            "val uartValid = Output(Bool())",
            "val commit = Output(new CommitTrace",
        ):
            self.assertIn(port, source)

    def test_historical_top_name_routes_only_through_unified_compat_top(self):
        source = LEGACY.read_text(encoding="utf-8")
        self.assertIn("extends AetherCoreV2UnifiedMemoryCompatSimTop", source)
        self.assertNotIn("AetherCoreV2LinuxSoC", source)
        self.assertNotIn("AetherCoreV2UnifiedMemorySoC extends", source)


if __name__ == "__main__":
    unittest.main()
