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

private class TinyProducerState(
    val xlen: Int,
    val identityBits: Int,
    val generationBits: Int
) extends Bundle {
  val valid = Bool()
  val producerTag = new ProducerTag(identityBits, generationBits)
  val rd = UInt(5.W)
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
  * F4 normal recovery keeps the surviving head producer because JAL/JALR may
  * still publish a link value. F5 privileged recovery is different: the head
  * is already complete and will trap/return at the next retirement boundary,
  * so all speculative producer-ownership and dependency state can be discarded.
  */
class TinyDependencyState(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"tiny-dependency XLEN must be 32 or 64, got $xlen")

  private val Entries = TinyRobGeometry.Entries
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val allocate = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))
    val committedRs1 = Input(UInt(xlen.W))
    val committedRs2 = Input(UInt(xlen.W))
    val completion = Flipped(Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits)))
    val recovery = Flipped(Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits)))
    val privilegedRecovery = Flipped(Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits)))
    val retire = Flipped(Valid(new RobRetirement(xlen)))
    val head = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))

    val slotView = Output(Vec(
      Entries,
      new TinyDependencySlotView(xlen, IdentityBits, GenerationBits)
    ))
    val headDependenciesValid = Output(Bool())
    val headRs1 = Output(new OperandState(xlen, IdentityBits, GenerationBits))
    val headRs2 = Output(new OperandState(xlen, IdentityBits, GenerationBits))
    val headOperandsReady = Output(Bool())
  })

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

  private def sameProducer(a: ProducerTag, b: ProducerTag): Bool =
    a.id === b.id && a.generation === b.generation

  private def sameRobToken(a: RobToken, b: RobToken): Bool =
    a.index === b.index && a.generation === b.generation

  private def resolveSource(
      address: UInt,
      used: Bool,
      committedValue: UInt
  ): OperandState = {
    val resolved = Wire(new OperandState(xlen, IdentityBits, GenerationBits))
    resolved := 0.U.asTypeOf(new OperandState(xlen, IdentityBits, GenerationBits))
    resolved.ready := true.B

    when(used && address =/= 0.U) {
      // ROB4 has at most four live producer lifetimes. Search those bounded
      // producer slots in ROB age order instead of paying for a 32-entry,
      // two-read architectural RAT. Last-connect priority leaves the youngest
      // matching producer as the source owner, preserving WAW semantics.
      val mappingValid = WireDefault(false.B)
      val mappingTag = WireDefault(
        0.U.asTypeOf(new ProducerTag(IdentityBits, GenerationBits))
      )
      val mappingReady = WireDefault(false.B)
      val mappingValue = WireDefault(0.U(xlen.W))

      for (age <- 0 until Entries) {
        val slot = (io.head.bits.robToken.index + age.U)(IdentityBits - 1, 0)
        val producer = producers(slot)
        when(io.head.valid && producer.valid && producer.rd === address) {
          mappingValid := true.B
          mappingTag := producer.producerTag
          mappingReady := producer.ready
          mappingValue := producer.value
        }
      }

      when(mappingValid) {
        val completionBypass = io.completion.valid &&
          io.completion.bits.hasValue &&
          !io.completion.bits.exception.valid &&
          sameProducer(io.completion.bits.producerTag, mappingTag)

        resolved.producerTag := mappingTag
        when(completionBypass) {
          resolved.ready := true.B
          resolved.value := io.completion.bits.value
        }.elsewhen(mappingReady) {
          resolved.ready := true.B
          resolved.value := mappingValue
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
    val retiringProducer = retiring.producerTag
    val producer = producers(retiringProducer.id)

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
    val createsProducer = allocated.decoded.writesRd &&
      allocated.decoded.rd =/= 0.U &&
      allocated.producesValue

    dependencies(slot).valid := true.B
    dependencies(slot).robToken := allocated.robToken
    dependencies(slot).rs1 := allocateRs1
    dependencies(slot).rs2 := allocateRs2

    producers(allocated.producerTag.id).valid := createsProducer
    producers(allocated.producerTag.id).producerTag := allocated.producerTag
    producers(allocated.producerTag.id).rd := allocated.decoded.rd
    producers(allocated.producerTag.id).ready := false.B
    producers(allocated.producerTag.id).value := 0.U

  }

  // Normal branch recovery keeps the surviving head producer/link value.
  when(io.recovery.valid) {
    assert(io.head.valid, "accepted recovery must retain a live ROB head")
    assert(sameRobToken(io.head.bits.robToken, io.recovery.bits.robToken),
      "accepted recovery must name the surviving ROB head")

    val survivor = io.head.bits
    val survivorCreatesProducer = survivor.decoded.writesRd &&
      survivor.decoded.rd =/= 0.U &&
      survivor.producesValue

    for (index <- 0 until Entries) {
      when(index.U =/= survivor.robToken.index) {
        dependencies(index).valid := false.B
      }
      when(index.U =/= survivor.producerTag.id) {
        producers(index).valid := false.B
        producers(index).ready := false.B
      }
    }

    producers(survivor.producerTag.id).valid := survivorCreatesProducer
    producers(survivor.producerTag.id).producerTag := survivor.producerTag
    producers(survivor.producerTag.id).rd := survivor.decoded.rd
    producers(survivor.producerTag.id).ready := survivorCreatesProducer && io.recovery.bits.hasValue
    producers(survivor.producerTag.id).value := io.recovery.bits.value
  }

  // Trap/xRET recovery has no speculative survivor dependency: the head is
  // already complete and will retire on the next boundary. Clear everything so
  // the redirect target starts from committed architectural state only.
  when(io.privilegedRecovery.valid) {
    assert(io.head.valid, "privileged recovery must retain a live ROB head until retirement")
    assert(sameRobToken(io.head.bits.robToken, io.privilegedRecovery.bits.robToken),
      "privileged recovery must name the surviving ROB head")

    for (index <- 0 until Entries) {
      dependencies(index).valid := false.B
      producers(index).valid := false.B
      producers(index).ready := false.B
    }
  }
}

/**
  * F2 integration substrate, extended with read-only F5 retirement/recovery
  * observability. Ordering/lifetime remains owned by TinyRob and architectural
  * integer state remains owned by V2Commit.
  */
class TinyDependencyBackend(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"v2 F2 backend XLEN must be 32 or 64, got $xlen")

  private val Entries = TinyRobGeometry.Entries
  private val IdentityBits = TinyRobGeometry.IndexBits
  private val GenerationBits = TinyRobGeometry.GenerationBits

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RobDispatch(xlen)))
    val allocated = Valid(new BackendUop(xlen, IdentityBits, GenerationBits))
    val completion = Flipped(Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits)))
    val acceptedRecovery = Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
    val acceptedPrivilegedRecovery = Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits))
    val retiring = Valid(new RobRetirement(xlen))
    val commit = Output(new CommitTrace(xlen = xlen))
    val head = Valid(new BackendUop(xlen, IdentityBits, GenerationBits))
    val schedulingWindow = Output(Vec(Entries, new TinySchedulingEntry(xlen)))
    val headDependenciesValid = Output(Bool())
    val headRs1 = Output(new OperandState(xlen, IdentityBits, GenerationBits))
    val headRs2 = Output(new OperandState(xlen, IdentityBits, GenerationBits))
    val headOperandsReady = Output(Bool())
    val occupancy = Output(UInt(log2Ceil(Entries + 1).W))
  })

  val rob = Module(new TinyRob(xlen))
  val dependencyState = Module(new TinyDependencyState(xlen))
  val commitStage = Module(new V2Commit(xlen))
  val registerFile = Module(new RegisterFile(xlen))

  rob.io.dispatch.valid := io.dispatch.valid
  rob.io.dispatch.bits := io.dispatch.bits
  io.dispatch.ready := rob.io.dispatch.ready
  io.allocated := rob.io.allocated

  rob.io.completion.valid := io.completion.valid
  rob.io.completion.bits := io.completion.bits
  io.acceptedRecovery := rob.io.acceptedRecovery
  io.acceptedPrivilegedRecovery := rob.io.acceptedPrivilegedRecovery

  commitStage.io.retire <> rob.io.retire
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
