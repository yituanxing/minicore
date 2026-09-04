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
    assert "Mem(32, new ProducerTag" in text
    assert "class TinyDependencyState" in text
    assert "class TinyDependencyBackend" in text


def test_f2_derives_rob_parallel_geometry_from_the_rob_owner() -> None:
    rob = ROB.read_text(encoding="utf-8")
    dependency = DEPENDENCY.read_text(encoding="utf-8")
    assert "private[v2] object TinyRobGeometry" in rob
    assert "val Entries: Int = 4" in rob
    assert "val IndexBits: Int = 2" in rob
    assert "val GenerationBits: Int = 2" in rob
    assert "private val Entries = TinyRobGeometry.Entries" in dependency
    assert "private val IdentityBits = TinyRobGeometry.IndexBits" in dependency
    assert "private val GenerationBits = TinyRobGeometry.GenerationBits" in dependency
    assert "private val Entries = 4" not in dependency
    assert "private val IdentityBits = 2" not in dependency
    assert "private val GenerationBits = 2" not in dependency
    assert "log2Ceil(TinyRobGeometry.Entries + 1).W" in rob
    assert "log2Ceil(TinyRobGeometry.Entries + 1).W" in dependency


def test_dependency_identity_is_producer_tag_not_order_or_storage_identity() -> None:
    text = DEPENDENCY.read_text(encoding="utf-8")
    assert "sameProducer" in text
    assert "mappingLive" in text
    assert "producer.rd === address" in text
    assert "sameProducer(producer.producerTag, mapping)" in text
    assert "sameProducer(dependencies(index).rs1.producerTag" in text
    assert "sameProducer(dependencies(index).rs2.producerTag" in text
    assert "producers(allocated.producerTag.id).valid := createsProducer" in text
    assert "producers(allocated.producerTag.id).producerTag := allocated.producerTag" in text
    assert "producers(slot).valid := createsProducer" not in text
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


def test_stale_rename_payload_is_validated_by_live_producer_and_destination() -> None:
    text = DEPENDENCY.read_text(encoding="utf-8")
    assert "private val rename = Mem(32, new ProducerTag" in text
    assert "producer.valid" in text
    assert "producer.rd === address" in text
    assert "sameProducer(producer.producerTag, mapping)" in text
    assert "rename.write(renameWriteAddress, renameWriteTag)" in text
    assert "rename(retiringRd).valid := false.B" not in text
    assert "for (register <- 0 until 32)" not in text


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
