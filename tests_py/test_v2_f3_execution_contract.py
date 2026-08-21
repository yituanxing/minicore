from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXECUTION = ROOT / "src/main/scala/aethercore/core/v2/TinyExecution.scala"
WIDTH_SPEC = ROOT / "src/test/scala/aethercore/DatapathWidthSpec.scala"
F3_CHECKS = ROOT / "src/test/scala/aethercore/V2F3ExecutionChecks.scala"


def test_f3_composes_the_frozen_dependency_backend_instead_of_forking_it() -> None:
    text = EXECUTION.read_text(encoding="utf-8")
    backend = text.split("class TinyExecutionBackend", 1)[1]
    assert "Module(new TinyDependencyBackend" in backend
    assert "Module(new TinyOldestIssue" in backend
    assert "Module(new TinyExecutionCluster" in backend
    assert "Module(new TinyRob" not in backend
    assert "Module(new TinyDependencyState" not in backend
    assert "Module(new RegisterFile" not in backend


def test_oldest_issue_owns_once_only_dispatch_by_rob_token() -> None:
    text = EXECUTION.read_text(encoding="utf-8")
    issue = text.split("class TinyOldestIssue", 1)[1].split("/** One-entry registered integer unit", 1)[0]
    assert "issuedValid" in issue
    assert "issuedToken" in issue
    assert "sameRobToken" in issue
    assert "alreadyIssued" in issue
    assert "!alreadyIssued" in issue
    assert "when(io.request.fire)" in issue
    assert "when(!io.head.valid)" in issue
    assert "completion" not in issue


def test_execution_units_use_tagged_decoupled_contracts_without_raw_redecode() -> None:
    text = EXECUTION.read_text(encoding="utf-8")
    for unit in (
        "class V2IntegerUnit",
        "class V2MulUnit",
        "class V2IterativeDivider",
        "class V2BranchUnit",
    ):
        assert unit in text
    assert "Decoupled(new ExecutionRequest" in text
    assert "Decoupled(new ExecutionResponse" in text
    assert ".decoded.inst" not in text
    assert "rawInst" not in text
    assert "OpASel" not in text
    assert "OpBSel" not in text
    assert "WbSel" not in text


def test_divider_is_iterative_and_does_not_inherit_combinational_division() -> None:
    text = EXECUTION.read_text(encoding="utf-8")
    divider = text.split("class V2IterativeDivider", 1)[1].split("/** One-entry registered branch", 1)[0]
    assert "private val busy = RegInit(false.B)" in divider
    assert "val nextRemainder" in divider
    assert "val nextQuotient" in divider
    assert "count := count + 1.U" in divider
    assert '" / "' not in divider
    assert '" % "' not in divider
    assert " / " not in divider
    assert " % " not in divider


def test_f3_does_not_pull_future_scheduling_recovery_or_memory_machinery_forward() -> None:
    text = EXECUTION.read_text(encoding="utf-8")
    forbidden = (
        "IssueQueue",
        "ReservationStation",
        "PhysicalRegister",
        "FreeList",
        "oldestReady",
        "BranchCheckpoint",
        "LoadQueue",
        "StoreQueue",
        "MSHR",
        "AXI",
        "TileLink",
    )
    for name in forbidden:
        assert name not in text


def test_fast_gate_suite_contains_f3_behavior_checks() -> None:
    width = WIDTH_SPEC.read_text(encoding="utf-8")
    checks = F3_CHECKS.read_text(encoding="utf-8")
    assert "with V2F3ExecutionChecks" in width
    assert "RAW chain automatically" in checks
    assert "remember an issued RobToken" in checks
    assert "genuinely iterative divider" in checks
    assert "compressed jump links" in checks
