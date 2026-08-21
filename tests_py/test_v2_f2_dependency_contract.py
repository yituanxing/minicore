from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEPENDENCY = ROOT / "src/main/scala/aethercore/core/v2/TinyDependency.scala"
ROB = ROOT / "src/main/scala/aethercore/core/v2/TinyRobCommit.scala"
WIDTH_SPEC = ROOT / "src/test/scala/aethercore/DatapathWidthSpec.scala"
F2_CHECKS = ROOT / "src/test/scala/aethercore/V2F2DependencyChecks.scala"


def test_f2_owns_only_tiny_rat_and_operand_readiness() -> None:
    text = DEPENDENCY.read_text(encoding="utf-8")
    assert "class OperandState" in text
    assert "val ready = Bool()" in text
    assert "val value = UInt(xlen.W)" in text
    assert "val producerTag = new ProducerTag" in text
    assert "Seq.fill(32)" in text
    assert "private val Entries = 4" in text
    assert "class TinyDependencyState" in text
    assert "class TinyDependencyBackend" in text


def test_dependency_identity_is_producer_tag_not_order_or_storage_identity() -> None:
    text = DEPENDENCY.read_text(encoding="utf-8")
    assert "sameProducer" in text
    assert "mapping.producerTag" in text
    assert "sameProducer(dependencies(index).rs1.producerTag" in text
    assert "sameProducer(dependencies(index).rs2.producerTag" in text
    assert "new ValueRef" not in text
    assert ".valueRef" not in text


def test_rob_remains_the_completion_identity_authority() -> None:
    rob = ROB.read_text(encoding="utf-8")
    dependency = DEPENDENCY.read_text(encoding="utf-8")
    assert "val acceptedCompletion = Valid(new ExecutionResponse" in rob
    assert "io.acceptedCompletion.valid := completionMatches" in rob
    assert "val headView = Valid(new BackendUop" in rob
    assert "dependencyState.io.completion := rob.io.acceptedCompletion" in dependency
    assert "dependencyState.io.completion := io.completion" not in dependency


def test_waw_retirement_clears_only_the_matching_latest_mapping() -> None:
    text = DEPENDENCY.read_text(encoding="utf-8")
    assert "sameProducer(rename(retiringRd).producerTag, retiringProducer)" in text
    assert "rename(retiringRd).valid := false.B" in text
    assert "when(io.allocate.valid)" in text
    assert "rename(allocated.decoded.rd).producerTag := allocated.producerTag" in text


def test_f2_does_not_pull_future_ooo_machinery_forward() -> None:
    text = DEPENDENCY.read_text(encoding="utf-8")
    forbidden = (
        "IssueQueue",
        "ReservationStation",
        "PhysicalRegister",
        "FreeList",
        "oldestReady",
        "issueWidth",
        "robEntries",
        "AXI",
        "TileLink",
    )
    for name in forbidden:
        assert name not in text


def test_existing_fast_gate_executes_f2_behavior_checks() -> None:
    width = WIDTH_SPEC.read_text(encoding="utf-8")
    checks = F2_CHECKS.read_text(encoding="utf-8")
    assert "with V2F1RobCommitChecks" in width
    assert "with V2F2DependencyChecks" in width
    assert "for (xlen <- Seq(32, 64))" in checks
    assert "capture a RAW dependency and wake it by ProducerTag" in checks
    assert "preserve a younger WAW mapping" in checks
    assert "fall back to committed RF state" in checks
    assert "stale completion" in checks
