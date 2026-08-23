from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "src/main/scala/aethercore/core/v2/TinyMemoryBackend.scala"
SELECTOR = ROOT / "src/main/scala/aethercore/core/v2/TinySelectiveIssue.scala"
EXECUTION = ROOT / "src/main/scala/aethercore/core/v2/TinySelectiveExecution.scala"
DEPENDENCY = ROOT / "src/main/scala/aethercore/core/v2/TinyDependency.scala"
ROB = ROOT / "src/main/scala/aethercore/core/v2/TinyRobCommit.scala"


def test_production_backend_enables_only_selective_compute() -> None:
    text = BACKEND.read_text(encoding="utf-8")
    assert "Module(new TinySelectiveComputeIssue" in text
    assert "Module(new TinySelectiveExecutionCluster" in text
    assert "Module(new TinyOldestIssue" in text
    assert "branchIssue.io.head.bits.executionClass === ExecutionClass.Branch" in text
    assert "head.bits.executionClass === ExecutionClass.Memory" in text
    assert "Module(new TinySystemCompletion" in text
    assert "selectiveIssue.io.window := dependencyBackend.io.schedulingWindow" in text
    assert "selectiveIssue.io.availability := execution.io.computeAvailability" in text


def test_first_selective_slice_remains_single_launch_per_cycle() -> None:
    text = BACKEND.read_text(encoding="utf-8")
    assert "branchIssue.io.request.fire" in text
    assert "selectiveIssue.io.request.fire" in text
    assert "lsu.io.request.fire" in text
    assert "A8 selective backend must remain single-issue per cycle" in text
    assert "selectiveIssue.io.block" in text
    assert "lsu.io.request.valid" in text


def test_selector_is_fail_closed_and_stops_at_serialization_boundaries() -> None:
    text = SELECTOR.read_text(encoding="utf-8")
    assert "older.uop.executionClass === ExecutionClass.System" in text
    assert "older.uop.decoded.ordering =/= OrderingClass.Normal" in text
    assert "older.uop.decoded.exception.valid" in text
    assert "!entry.uop.decoded.exception.valid" in text
    assert "entry.uop.decoded.ordering === OrderingClass.Normal" in text

    safe_class = text.split("val safeClass =", 1)[1].split("eligible(age)", 1)[0]
    assert "ExecutionClass.Integer" in safe_class
    assert "ExecutionClass.MulDiv" in safe_class
    assert "ExecutionClass.Memory" not in safe_class
    assert "ExecutionClass.Branch" not in safe_class
    assert "ExecutionClass.System" not in safe_class


def test_scheduler_view_does_not_duplicate_rob_or_dependency_storage() -> None:
    dependency = DEPENDENCY.read_text(encoding="utf-8")
    rob = ROB.read_text(encoding="utf-8")
    assert "class TinySchedulingEntry" in dependency
    assert "val schedulingWindow" in dependency
    assert "dependencyEntry.robToken.generation === robEntry.uop.robToken.generation" in dependency
    assert "class TinyRobWindowEntry" in rob
    assert "val window = Output(Vec" in rob


def test_execution_availability_is_owned_by_real_compute_units() -> None:
    text = EXECUTION.read_text(encoding="utf-8")
    assert "new V2IntegerUnit" in text
    assert "new V2MulUnit" in text
    assert "new V2IterativeDivider" in text
    assert "io.computeAvailability.integer := integer.io.request.ready" in text
    assert "io.computeAvailability.multiply := multiply.io.request.ready" in text
    assert "io.computeAvailability.divide := divide.io.request.ready" in text


def test_first_selective_slice_does_not_pull_large_ooo_machinery_forward() -> None:
    text = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (BACKEND, SELECTOR, EXECUTION, DEPENDENCY, ROB)
    )
    forbidden = (
        "IssueQueue",
        "ReservationStation",
        "PhysicalRegister",
        "FreeList",
        "LoadQueue",
        "StoreQueue",
        "MemoryDependencePredictor",
        "MSHR",
        "BranchCheckpoint",
    )
    for symbol in forbidden:
        assert symbol not in text
