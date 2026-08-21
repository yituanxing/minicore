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

/**
  * F2 fixed dependency tracker.
  *
  * This is deliberately not an issue queue or physical-register file. The
  * 32-entry RAT names the newest live producer of each architectural register;
  * four ROB-parallel dependency records remember Ready(value) or
  * Pending(ProducerTag). A tiny producer scoreboard retains completed values
  * until retirement so a consumer dispatched after the completion pulse still
  * resolves correctly.
  */
class TinyDependencyState(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"tiny-dependency XLEN must be 32 or 64, got $xlen")

  private val Entries = 4
  private val IdentityBits = 2
  private val GenerationBits = 2

  val io = IO(new Bundle {
    val allocate = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))
    val committedRs1 = Input(UInt(xlen.W))
    val committedRs2 = Input(UInt(xlen.W))
    val completion = Flipped(Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits)))
    val retire = Flipped(Valid(new RobRetirement(xlen)))
    val head = Flipped(Valid(new BackendUop(xlen, IdentityBits, GenerationBits)))

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
    val createsProducer = allocated.decoded.writesRd &&
      allocated.decoded.rd =/= 0.U &&
      allocated.producesValue

    dependencies(slot).valid := true.B
    dependencies(slot).robToken := allocated.robToken
    dependencies(slot).rs1 := allocateRs1
    dependencies(slot).rs2 := allocateRs2

    producers(allocated.producerTag.id).valid := createsProducer
    producers(allocated.producerTag.id).producerTag := allocated.producerTag
    producers(allocated.producerTag.id).ready := false.B
    producers(allocated.producerTag.id).value := 0.U

    when(createsProducer) {
      rename(allocated.decoded.rd).valid := true.B
      rename(allocated.decoded.rd).producerTag := allocated.producerTag
    }
  }
}

/**
  * F2 integration harness.
  *
  * Ordering/lifetime still belongs to TinyRob and architectural state still
  * changes only through V2Commit. This layer adds only RAT/readiness ownership;
  * there is no execution scheduler yet.
  */
class TinyDependencyBackend(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"v2 F2 backend XLEN must be 32 or 64, got $xlen")

  private val IdentityBits = 2
  private val GenerationBits = 2

  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RobDispatch(xlen)))
    val allocated = Valid(new BackendUop(xlen, IdentityBits, GenerationBits))
    val completion = Flipped(Valid(new ExecutionResponse(xlen, IdentityBits, GenerationBits)))
    val commit = Output(new CommitTrace(xlen = xlen))
    val head = Valid(new BackendUop(xlen, IdentityBits, GenerationBits))
    val headDependenciesValid = Output(Bool())
    val headRs1 = Output(new OperandState(xlen, IdentityBits, GenerationBits))
    val headRs2 = Output(new OperandState(xlen, IdentityBits, GenerationBits))
    val headOperandsReady = Output(Bool())
    val occupancy = Output(UInt(3.W))
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

  commitStage.io.retire <> rob.io.retire
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
  dependencyState.io.retire.valid := rob.io.retire.valid && rob.io.retire.ready
  dependencyState.io.retire.bits := rob.io.retire.bits
  dependencyState.io.head := rob.io.headView

  io.headDependenciesValid := dependencyState.io.headDependenciesValid
  io.headRs1 := dependencyState.io.headRs1
  io.headRs2 := dependencyState.io.headRs2
  io.headOperandsReady := dependencyState.io.headOperandsReady
}
