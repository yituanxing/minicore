package aethercore.core.v2

import chisel3._
import chisel3.util._
import aethercore.common.CommitTrace
import aethercore.core.RegisterFile

/**
  * F2 source readiness.
  *
  * ready=true means value is immediately consumable. ready=false means the
  * source is waiting on exactly producerTag. RobToken and ValueRef are not
  * dependency identities and deliberately do not appear in this decision.
  */
class OperandState(val xlen: Int, val identityBits: Int, val generationBits: Int) extends Bundle {
  require(xlen == 32 || xlen == 64, s"operand-state XLEN must be 32 or 64, got $xlen")

  val ready = Bool()
  val value = UInt(xlen.W)
  val producerTag = new ProducerTag(identityBits, generationBits)
}

private class TinyRenameEntry(val identityBits: Int, val generationBits: Int) extends Bundle {
  val valid = Bool()
  val producerTag = new ProducerTag(identityBits, generationBits)
}

private class TinyProducerState(
    val xlen: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  val valid = Bool()
  val producerTag = new ProducerTag(identityBits, generationBits)
  val ready = Bool()
  val value = UInt(xlen.W)
}

private class TinyDependencyEntry(
    val xlen: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  val valid = Bool()
  val robToken = new RobToken(identityBits, generationBits)
  val rs1 = new OperandState(xlen, identityBits, generationBits)
  val rs2 = new OperandState(xlen, identityBits, generationBits)
}

/** Read-only physical-slot projection of dependency readiness. */
class TinyDependencySlotView(
    val xlen: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  val valid = Bool()
  val robToken = new RobToken(identityBits, generationBits)
  val rs1 = new OperandState(xlen, identityBits, generationBits)
  val rs2 = new OperandState(xlen, identityBits, generationBits)
}

/**
  * Read-only composition of ROB age/lifetime with dependency readiness.
  *
  * This bundle is the A8 scheduler seam. It contains no duplicated queue state:
  * order/complete/uOp come from TinyRob, while operand readiness/value comes
  * from TinyDependencyState after an exact RobToken match.
  */
class TinySchedulingEntry(val xlen: Int) extends Bundle {
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val valid = Bool()
  val complete = Bool()
  val uop = new BackendUop(xlen, IdentityBits, GenerationBits)
  val dependenciesValid = Bool()
  val rs1 = new OperandState(xlen, IdentityBits, GenerationBits)
  val rs2 = new OperandState(xlen, IdentityBits, GenerationBits)
  val operandsReady = Bool()
}

/**
  * Fixed dependency tracker.
  *
  * P8.2 normal recovery preserves every older survivor plus the recovering
  * Branch, clears only killed younger dependency/producer state, then rebuilds
  * the speculative rename map from the surviving ROB window in age order.
  * Privileged recovery remains different: the precise head trap/xRET boundary
  * discards all speculative dependency state.
  */
class TinyDependencyState(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"tiny-dependency XLEN must be 32 or 64, got $xlen")

  private val Entries = TinyRobGeometry.Entries
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val CountBits = log2Ceil(Entries + 1)

  val io = IO(new Bundle {
    val allocate = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))
    val committedRs1 = Input(UInt(xlen.W))
    val committedRs2 = Input(UInt(xlen.W))
    val completion = Flipped(Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits)))
    val recovery = Flipped(Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits)))
    val recoverySurvivorCount = Input(UInt(CountBits.W))
    val recoveryWindow = Input(Vec(Entries, new TinyRobWindowEntry(xlen)))
    val privilegedRecovery = Flipped(Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits)))
    val retire = Flipped(Valid(new RobRetirement(xlen)))
    val head = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))

    val recoveryBusy = Output(Bool())
    val slotView = Output(Vec(
      Entries,
      new TinyDependencySlotView(xlen, IdentityBits, GenerationBits)
    ))
    val headDependenciesValid = Output(Bool())
    val headRs1 = Output(new OperandState(xlen, IdentityBits, GenerationBits))
    val headRs2 = Output(new OperandState(xlen, IdentityBits, GenerationBits))
    val headOperandsReady = Output(Bool())
  })

  private val rename = RegInit(
    VecInit(Seq.fill(32)(0.U.asTypeOf(new TinyRenameEntry(IdentityBits, GenerationBits))))
  )
  private val producers = RegInit(
    VecInit(
      Seq.fill(Entries)(
        0.U.asTypeOf(new TinyProducerState(xlen, IdentityBits, GenerationBits))
      )
    )
  )
  private val dependencies = RegInit(
    VecInit(
      Seq.fill(Entries)(
        0.U.asTypeOf(new TinyDependencyEntry(xlen, IdentityBits, GenerationBits))
      )
    )
  )

  private val rebuildBusy = RegInit(false.B)
  private val rebuildAge = RegInit(0.U(IdentityBits.W))
  private val rebuildCount = RegInit(0.U(CountBits.W))
  io.recoveryBusy := rebuildBusy

  private def sameProducer(a: ProducerTag, b: ProducerTag): Bool =
    a.id === b.id && a.generation === b.generation

  private def sameRobToken(a: RobToken, b: RobToken): Bool =
    a.index === b.index && a.generation === b.generation

  private def createsProducer(uop: BackendUop): Bool =
    uop.decoded.writesRd && uop.decoded.rd =/= 0.U && uop.producesValue

  private def resolveSource(
      address: UInt,
      used: Bool,
      committedValue: UInt
  ): OperandState = {
    val resolved = Wire(new OperandState(xlen, IdentityBits, GenerationBits))
    resolved := 0.U.asTypeOf(new OperandState(xlen, IdentityBits, GenerationBits))
    resolved.ready := true.B

    when(used && address =/= 0.U) {
      val mapping = rename(address)
      when(mapping.valid) {
        val producer = producers(mapping.producerTag.id)
        val completionBypass = io.completion.valid &&
          io.completion.bits.hasValue &&
          !io.completion.bits.exception.valid &&
          sameProducer(io.completion.bits.producerTag, mapping.producerTag)
        val retainedValue = producer.valid &&
          producer.ready &&
          sameProducer(producer.producerTag, mapping.producerTag)

        resolved.producerTag := mapping.producerTag
        when(completionBypass) {
          resolved.ready := true.B
          resolved.value := io.completion.bits.value
        }.elsewhen(retainedValue) {
          resolved.ready := true.B
          resolved.value := producer.value
        }.otherwise {
          resolved.ready := false.B
          resolved.value := 0.U
        }
      }.otherwise {
        resolved.ready := true.B
        resolved.value := committedValue
      }
    }

    resolved
  }

  private val allocateRs1 = resolveSource(
    io.allocate.bits.decoded.rs1,
    io.allocate.bits.decoded.usesRs1,
    io.committedRs1
  )
  private val allocateRs2 = resolveSource(
    io.allocate.bits.decoded.rs2,
    io.allocate.bits.decoded.usesRs2,
    io.committedRs2
  )

  for (index <- 0 until Entries) {
    io.slotView(index) := 0.U.asTypeOf(
      new TinyDependencySlotView(xlen, IdentityBits, GenerationBits)
    )
    io.slotView(index).valid := dependencies(index).valid
    io.slotView(index).robToken := dependencies(index).robToken
    io.slotView(index).rs1 := dependencies(index).rs1
    io.slotView(index).rs2 := dependencies(index).rs2
  }

  private val headEntry = dependencies(io.head.bits.robToken.index)
  private val headMatches = io.head.valid &&
    headEntry.valid &&
    sameRobToken(headEntry.robToken, io.head.bits.robToken)

  io.headDependenciesValid := headMatches
  io.headRs1 := 0.U.asTypeOf(new OperandState(xlen, IdentityBits, GenerationBits))
  io.headRs2 := 0.U.asTypeOf(new OperandState(xlen, IdentityBits, GenerationBits))
  when(headMatches) {
    io.headRs1 := headEntry.rs1
    io.headRs2 := headEntry.rs2
  }
  io.headOperandsReady := headMatches && headEntry.rs1.ready && headEntry.rs2.ready

  private val completionProducer = producers(io.completion.bits.producerTag.id)
  private val completionCanWake = io.completion.valid &&
    io.completion.bits.hasValue &&
    !io.completion.bits.exception.valid &&
    completionProducer.valid &&
    sameProducer(completionProducer.producerTag, io.completion.bits.producerTag)

  when(completionCanWake) {
    producers(io.completion.bits.producerTag.id).ready := true.B
    producers(io.completion.bits.producerTag.id).value := io.completion.bits.value

    for (index <- 0 until Entries) {
      when(
        dependencies(index).valid &&
          !dependencies(index).rs1.ready &&
          sameProducer(dependencies(index).rs1.producerTag, io.completion.bits.producerTag)
      ) {
        dependencies(index).rs1.ready := true.B
        dependencies(index).rs1.value := io.completion.bits.value
      }
      when(
        dependencies(index).valid &&
          !dependencies(index).rs2.ready &&
          sameProducer(dependencies(index).rs2.producerTag, io.completion.bits.producerTag)
      ) {
        dependencies(index).rs2.ready := true.B
        dependencies(index).rs2.value := io.completion.bits.value
      }
    }
  }

  when(io.retire.valid) {
    val retiring = io.retire.bits.uop
    val retiringRd = retiring.decoded.rd
    val retiringProducer = retiring.producerTag
    val producer = producers(retiringProducer.id)

    when(
      retiring.decoded.writesRd &&
        retiringRd =/= 0.U &&
        rename(retiringRd).valid &&
        sameProducer(rename(retiringRd).producerTag, retiringProducer)
    ) {
      rename(retiringRd).valid := false.B
    }

    when(producer.valid && sameProducer(producer.producerTag, retiringProducer)) {
      producers(retiringProducer.id).valid := false.B
      producers(retiringProducer.id).ready := false.B
    }

    val retiringDependency = dependencies(retiring.robToken.index)
    when(
      retiringDependency.valid &&
        sameRobToken(retiringDependency.robToken, retiring.robToken)
    ) {
      dependencies(retiring.robToken.index).valid := false.B
    }
  }

  when(io.allocate.valid) {
    val allocated = io.allocate.bits
    val slot = allocated.robToken.index
    val allocatedCreatesProducer = createsProducer(allocated)

    dependencies(slot).valid := true.B
    dependencies(slot).robToken := allocated.robToken
    dependencies(slot).rs1 := allocateRs1
    dependencies(slot).rs2 := allocateRs2

    producers(allocated.producerTag.id).valid := allocatedCreatesProducer
    producers(allocated.producerTag.id).producerTag := allocated.producerTag
    producers(allocated.producerTag.id).ready := false.B
    producers(allocated.producerTag.id).value := 0.U

    when(allocatedCreatesProducer) {
      rename(allocated.decoded.rd).valid := true.B
      rename(allocated.decoded.rd).producerTag := allocated.producerTag
    }
  }

  // Recovery/rebuild control has explicit priority over the age-by-age rebuild.
  // The recovery cycle itself replays age0 immediately. This preserves the old
  // head-only F4 timing when survivorCount==1 while middle-aged branches rebuild
  // the remaining mappings sequentially with bounded state.
  when(io.privilegedRecovery.valid) {
    assert(io.head.valid, "privileged recovery must retain a live ROB head until retirement")
    assert(sameRobToken(io.head.bits.robToken, io.privilegedRecovery.bits.robToken),
      "privileged recovery must name the surviving ROB head")

    for (register <- 0 until 32) {
      rename(register).valid := false.B
    }
    for (index <- 0 until Entries) {
      dependencies(index).valid := false.B
      producers(index).valid := false.B
      producers(index).ready := false.B
    }
    rebuildBusy := false.B
    rebuildAge := 0.U
    rebuildCount := 0.U
  }.elsewhen(io.recovery.valid) {
    assert(io.recoverySurvivorCount > 0.U,
      "normal recovery must retain at least the recovering Branch")
    assert(io.recoverySurvivorCount <= Entries.U,
      "normal recovery survivor count must fit the bounded ROB")

    val recoveryTokenSeen = WireDefault(false.B)
    for (age <- 0 until Entries) {
      val entry = io.recoveryWindow(age)
      when(entry.valid && sameRobToken(entry.uop.robToken, io.recovery.bits.robToken)) {
        recoveryTokenSeen := true.B
        assert(io.recoverySurvivorCount === (age + 1).U,
          "normal recovery survivor count must end at the recovering Branch")
        // The recovery response is the authoritative completion of the
        // surviving Branch. Refresh its producer explicitly instead of relying
        // on same-cycle generic wakeup ordering; older survivor producers remain
        // untouched and younger producers are cleared below.
        when(createsProducer(entry.uop)) {
          producers(entry.uop.producerTag.id).valid := true.B
          producers(entry.uop.producerTag.id).producerTag := entry.uop.producerTag
          producers(entry.uop.producerTag.id).ready := io.recovery.bits.hasValue
          producers(entry.uop.producerTag.id).value := io.recovery.bits.value
        }
      }
    }
    assert(recoveryTokenSeen, "normal recovery must name a live entry in the ROB window")

    for (register <- 0 until 32) {
      rename(register).valid := false.B
    }

    // Kill only dependency/producer ownership belonging to younger ROB ages.
    // ProducerTag is used for producer state; do not infer it from RobToken.
    for (age <- 0 until Entries) {
      val entry = io.recoveryWindow(age)
      when(entry.valid && age.U >= io.recoverySurvivorCount) {
        dependencies(entry.uop.robToken.index).valid := false.B
        producers(entry.uop.producerTag.id).valid := false.B
        producers(entry.uop.producerTag.id).ready := false.B
      }
    }

    val oldest = io.recoveryWindow(0).uop
    when(io.recoveryWindow(0).valid && createsProducer(oldest)) {
      rename(oldest.decoded.rd).valid := true.B
      rename(oldest.decoded.rd).producerTag := oldest.producerTag
    }

    rebuildCount := io.recoverySurvivorCount
    when(io.recoverySurvivorCount > 1.U) {
      rebuildBusy := true.B
      rebuildAge := 1.U
    }.otherwise {
      rebuildBusy := false.B
      rebuildAge := 0.U
    }
  }.elsewhen(rebuildBusy) {
    val replay = io.recoveryWindow(rebuildAge)
    assert(replay.valid, "recovery rebuild age must remain a live ROB survivor")

    when(createsProducer(replay.uop)) {
      val producer = producers(replay.uop.producerTag.id)
      assert(producer.valid && sameProducer(producer.producerTag, replay.uop.producerTag),
        "recovery rebuild must retain exact survivor producer state")
      rename(replay.uop.decoded.rd).valid := true.B
      rename(replay.uop.decoded.rd).producerTag := replay.uop.producerTag
    }

    val nextAge = rebuildAge +& 1.U
    when(nextAge >= rebuildCount) {
      rebuildBusy := false.B
      rebuildAge := 0.U
    }.otherwise {
      rebuildAge := rebuildAge + 1.U
    }
  }
}

/**
  * F2 integration substrate, extended with P8.2 recovery/rebuild observability.
  * Ordering/lifetime remains owned by TinyRob and architectural integer state
  * remains owned by V2Commit.
  */
class TinyDependencyBackend(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"v2 F2 backend XLEN must be 32 or 64, got $xlen")

  private val Entries = TinyRobGeometry.Entries
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits
  private val CountBits = log2Ceil(Entries + 1)

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RobDispatch(xlen)))
    val allocated = Valid(new BackendUop(xlen, IdentityBits, GenerationBits))
    val completion = Flipped(Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits)))
    val acceptedRecovery = Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
    val acceptedRecoverySurvivorCount = Output(UInt(CountBits.W))
    val acceptedPrivilegedRecovery = Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
    val recoveryBusy = Output(Bool())
    val retiring = Valid(new RobRetirement(xlen))
    val commit = Output(new CommitTrace(xlen = xlen))
    val head = Valid(new BackendUop(xlen, IdentityBits, GenerationBits))
    val schedulingWindow = Output(Vec(Entries, new TinySchedulingEntry(xlen)))
    val headDependenciesValid = Output(Bool())
    val headRs1 = Output(new OperandState(xlen, IdentityBits, GenerationBits))
    val headRs2 = Output(new OperandState(xlen, IdentityBits, GenerationBits))
    val headOperandsReady = Output(Bool())
    val occupancy = Output(UInt(CountBits.W))
  })

  val rob = Module(new TinyRob(xlen))
  val dependencyState = Module(new TinyDependencyState(xlen))
  val commitStage = Module(new V2Commit(xlen))
  val registerFile = Module(new RegisterFile(xlen))

  private val recoveryBusy = dependencyState.io.recoveryBusy

  rob.io.dispatch.valid := io.dispatch.valid && !recoveryBusy
  rob.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := rob.io.dispatch.ready && !recoveryBusy
  io.allocated := rob.io.allocated

  rob.io.completion.valid := io.completion.valid
  rob.io.completion.bits := io.completion.bits
  io.acceptedRecovery := rob.io.acceptedRecovery
  io.acceptedPrivilegedRecovery := rob.io.acceptedPrivilegedRecovery

  private val recoverySurvivorCount = WireDefault(0.U(CountBits.W))
  for (age <- 0 until Entries) {
    val entry = rob.io.window(age)
    val matchesRecovery = rob.io.acceptedRecovery.valid && entry.valid &&
      entry.uop.robToken.index === rob.io.acceptedRecovery.bits.robToken.index &&
      entry.uop.robToken.generation === rob.io.acceptedRecovery.bits.robToken.generation
    when(matchesRecovery) {
      recoverySurvivorCount := (age + 1).U
    }
  }
  when(rob.io.acceptedRecovery.valid) {
    assert(recoverySurvivorCount =/= 0.U,
      "accepted normal recovery must resolve an exact survivor boundary")
  }
  io.acceptedRecoverySurvivorCount := recoverySurvivorCount
  io.recoveryBusy := recoveryBusy

  // P8.2 rebuild is a short backend barrier. The recovery cycle itself is
  // already ROB-atomic; while the sequential rebuild is active, hold retirement
  // and dispatch so the surviving age-ordered window remains stable.
  commitStage.io.retire.valid := rob.io.retire.valid && !recoveryBusy
  commitStage.io.retire.bits := rob.io.retire.bits
  rob.io.retire.ready := commitStage.io.retire.ready && !recoveryBusy
  io.retiring.valid := rob.io.retire.valid && rob.io.retire.ready
  io.retiring.bits := rob.io.retire.bits
  io.commit := commitStage.io.commit
  io.occupancy := rob.io.occupancy
  io.head := rob.io.headView

  registerFile.io.writeEnable := commitStage.io.rfWriteEnable
  registerFile.io.rdAddr := commitStage.io.rfWriteAddress
  registerFile.io.rdData := commitStage.io.rfWriteData
  registerFile.io.rs1Addr := io.dispatch.bits.decoded.rs1
  registerFile.io.rs2Addr := io.dispatch.bits.decoded.rs2

  dependencyState.io.allocate := rob.io.allocated
  dependencyState.io.committedRs1 := registerFile.io.rs1Data
  dependencyState.io.committedRs2 := registerFile.io.rs2Data
  dependencyState.io.completion := rob.io.acceptedCompletion
  dependencyState.io.recovery := rob.io.acceptedRecovery
  dependencyState.io.recoverySurvivorCount := recoverySurvivorCount
  dependencyState.io.recoveryWindow := rob.io.window
  dependencyState.io.privilegedRecovery := rob.io.acceptedPrivilegedRecovery
  dependencyState.io.retire.valid := rob.io.retire.valid && rob.io.retire.ready
  dependencyState.io.retire.bits := rob.io.retire.bits
  dependencyState.io.head := rob.io.headView

  // Compose order and readiness only at this read-only seam. The dependency
  // slot is accepted only when it names the exact ROB lifetime currently shown
  // at that age; stale physical-slot state therefore cannot enter scheduling.
  for (age <- 0 until Entries) {
    val robEntry = rob.io.window(age)
    val dependencyEntry = dependencyState.io.slotView(robEntry.uop.robToken.index)
    val dependencyMatches = robEntry.valid &&
      dependencyEntry.valid &&
      dependencyEntry.robToken.index === robEntry.uop.robToken.index &&
      dependencyEntry.robToken.generation === robEntry.uop.robToken.generation

    io.schedulingWindow(age) := 0.U.asTypeOf(new TinySchedulingEntry(xlen))
    io.schedulingWindow(age).valid := robEntry.valid
    io.schedulingWindow(age).complete := robEntry.complete
    io.schedulingWindow(age).uop := robEntry.uop
    io.schedulingWindow(age).dependenciesValid := dependencyMatches
    when(dependencyMatches) {
      io.schedulingWindow(age).rs1 := dependencyEntry.rs1
      io.schedulingWindow(age).rs2 := dependencyEntry.rs2
    }
    io.schedulingWindow(age).operandsReady := dependencyMatches &&
      dependencyEntry.rs1.ready && dependencyEntry.rs2.ready
  }

  io.headDependenciesValid := dependencyState.io.headDependenciesValid
  io.headRs1 := dependencyState.io.headRs1
  io.headRs2 := dependencyState.io.headRs2
  io.headOperandsReady := dependencyState.io.headOperandsReady
}
