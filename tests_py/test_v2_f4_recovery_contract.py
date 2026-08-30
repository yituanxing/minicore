from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ROB = ROOT / "src/main/scala/aethercore/core/v2/TinyRobCommit.scala"
DEPENDENCY = ROOT / "src/main/scala/aethercore/core/v2/TinyDependency.scala"
RECOVERY = ROOT / "src/main/scala/aethercore/core/v2/TinyRecovery.scala"
WIDTH_SPEC = ROOT / "src/test/scala/aethercore/DatapathWidthSpec.scala"
F4_CHECKS = ROOT / "src/test/scala/aethercore/V2F4RecoveryChecks.scala"


def test_recovery_is_derived_only_after_full_rob_completion_identity_validation() -> None:
    rob = ROB.read_text(encoding="utf-8")
    assert "val completionMatches = io.completion.valid" in rob
    assert "completionEntry.uop.robToken.generation === io.completion.bits.robToken.generation" in rob
    assert "completionEntry.uop.producerTag.generation === io.completion.bits.producerTag.generation" in rob
    assert "completionEntry.uop.valueRef.generation === io.completion.bits.valueRef.generation" in rob
    assert "val recoveryMatches = completionMatches" in rob
    assert "completionIndex === head" in rob
    assert "completionEntry.uop.executionClass === ExecutionClass.Branch" in rob
    assert "completionEntry.uop.decoded.controlFlow.kind =/= ControlFlowKind.None" in rob
    assert "!completionEntry.exception.valid" in rob
    assert "!io.completion.bits.exception.valid" in rob
    assert "io.acceptedRecovery.valid := recoveryMatches" in rob


def test_head_only_recovery_squashes_younger_and_renews_killed_lifetimes() -> None:
    rob = ROB.read_text(encoding="utf-8")
    assert "index.U =/= head && entries(index).valid" in rob
    assert "slotGenerations(index) := slotGenerations(index) + 1.U" in rob
    assert "tail := head + 1.U" in rob
    assert "count := 1.U" in rob
    assert "&& !recoveryMatches" in rob
    forbidden = ("ageCompare", "branchMask", "checkpoint", "Checkpoint")
    for name in forbidden:
        assert name not in rob


def test_dependency_repair_uses_rob_accepted_recovery_as_its_only_trigger() -> None:
    dep = DEPENDENCY.read_text(encoding="utf-8")
    assert "dependencyState.io.completion := rob.io.acceptedCompletion" in dep
    assert "dependencyState.io.recovery := rob.io.acceptedRecovery" in dep
    assert "io.acceptedRecovery := rob.io.acceptedRecovery" in dep
    assert "when(io.recovery.valid)" in dep
    recovery = dep.split("when(io.recovery.valid)", 1)[1]
    assert "branchTaken" not in recovery
    assert "branchValid" not in recovery
    assert "private val rename" not in dep
    assert "index.U =/= survivor.robToken.index" in recovery
    assert "index.U =/= survivor.producerTag.id" in recovery
    assert "producers(survivor.producerTag.id).rd := survivor.decoded.rd" in recovery


def test_redirect_seam_carries_only_surviving_order_identity_and_target() -> None:
    text = RECOVERY.read_text(encoding="utf-8")
    redirect = text.split("class RecoveryRedirect", 1)[1].split("/**", 1)[0]
    assert "RobToken" in redirect
    assert "target" in redirect
    for forbidden in ("ProducerTag", "ValueRef", "checkpoint", "port", "issueSlot"):
        assert forbidden not in redirect
    backend = text.split("class TinyRecoveryBackend", 1)[1]
    assert "Module(new TinyDependencyBackend" in backend
    assert "Module(new TinyOldestIssue" in backend
    assert "Module(new TinyExecutionCluster" in backend
    assert "dependencyBackend.io.acceptedRecovery.valid" in backend
    assert "execution.io.response" not in backend.split("io.redirect.valid :=", 1)[1]
    assert "Module(new TinyRob" not in backend
    assert "Module(new RegisterFile" not in backend


def test_f4_does_not_pull_trap_memory_or_ooo_machinery_forward() -> None:
    text = RECOVERY.read_text(encoding="utf-8")
    forbidden = (
        "TrapController",
        "CsrFile",
        "IssueQueue",
        "ReservationStation",
        "PhysicalRegister",
        "FreeList",
        "LoadQueue",
        "StoreQueue",
        "MSHR",
        "Predictor",
        "AXI",
        "TileLink",
    )
    for name in forbidden:
        assert name not in text


def test_fast_gate_suite_contains_f4_recovery_behavior_checks() -> None:
    width = WIDTH_SPEC.read_text(encoding="utf-8")
    checks = F4_CHECKS.read_text(encoding="utf-8")
    assert "with V2F4RecoveryChecks" in width
    assert "squash younger WAW state and redirect exactly once" in checks
    assert "not-taken conditional branch" in checks
    assert "reject stale branch completion" in checks
    assert "exceptional branch completion" in checks
