import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
COMPLETION = ROOT / "src/main/scala/aethercore/core/v2/TinyCompletion.scala"
EXECUTION = ROOT / "src/main/scala/aethercore/core/v2/TinyExecution.scala"
BACKEND = ROOT / "src/main/scala/aethercore/core/v2/TinyMemoryBackend.scala"
PERFORMANCE = ROOT / "src/main/scala/aethercore/sim/AetherCoreV2Performance.scala"


class V2P83CompletionBandwidthSourceContract(unittest.TestCase):
    def test_top_level_completion_transport_remains_fair_and_decoupled(self):
        source = COMPLETION.read_text(encoding="utf-8")
        self.assertIn("class TinyCompletionArbiter", source)
        self.assertIn("Decoupled(new ExecutionResponse", source)
        self.assertIn("new RRArbiter(", source)
        self.assertIn("sourceCount", source)
        self.assertIn("arbiter.io.in(index) <> io.in(index)", source)
        self.assertIn("io.out <> arbiter.io.out", source)

    def test_backend_keeps_three_independent_completion_sources_on_one_port(self):
        source = BACKEND.read_text(encoding="utf-8")
        self.assertIn("new TinyCompletionArbiter(Xlen, 3)", source)
        self.assertIn("completionMerge.io.in(0) <> system.io.completion", source)
        self.assertIn("completionMerge.io.in(1) <> lsu.io.completion", source)
        self.assertIn("completionMerge.io.in(2) <> execution.io.response", source)
        self.assertIn("dependency.io.complete <> completionMerge.io.out", source)

    def test_execution_cluster_keeps_fair_response_arbitration(self):
        source = EXECUTION.read_text(encoding="utf-8")
        self.assertIn(
            "new RRArbiter(new ExecutionResponse(xlen, IdentityBits, GenerationBits), 4)",
            source,
        )
        self.assertIn("responses.io.in(0) <> integer.io.response", source)
        self.assertIn("responses.io.in(1) <> branch.io.response", source)
        self.assertIn("responses.io.in(2) <> multiply.io.response", source)
        self.assertIn("responses.io.in(3) <> divide.io.response", source)
        self.assertIn("io.response <> responses.io.out", source)

    def test_performance_counters_observe_real_completion_valid_ready_pressure(self):
        source = PERFORMANCE.read_text(encoding="utf-8")
        self.assertIn("systemCompletionValid", source)
        self.assertIn("lsuCompletionValid", source)
        self.assertIn("executionCompletionValid", source)
        self.assertIn("private val completionValidCount = PopCount(Cat(", source)
        self.assertIn("systemCompletionValid && !systemCompletionReady", source)
        self.assertIn("lsuCompletionValid && !lsuCompletionReady", source)
        self.assertIn("executionCompletionValid && !executionCompletionReady", source)
        self.assertIn("events.completionCollision := completionValidCount > 1.U", source)
        self.assertIn("events.completionBackpressure := completionBackpressured", source)


if __name__ == "__main__":
    unittest.main()
