package aethercore

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.core.product._
import aethercore.core.semantic.SemanticDecode

/** Real Chisel scheduling/rename/ROB/rollback, using an external completion FU
  * to inject independent execution latencies and precise failure races.
  */
private class SmallOoOTestHarness extends Module {
  private val g = ProductCoreProfiles.smallGeometry
  private val I = log2Ceil(g.robEntries)
  private val P = log2Ceil(g.integerPhysicalRegisters)
  private val G = 16
  val io = IO(new Bundle {
    val pc = Input(UInt(64.W))
    val inst = Input(UInt(32.W))
    val predictedNextPc = Input(UInt(64.W))
    val dispatchValid = Input(Bool())
    val dispatchReady = Output(Bool())
    val issue = Decoupled(new ProductIssue(64, I, P, G))
    val completion = Flipped(Decoupled(new ProductCompletion(64, I, P, G)))
    val retire = Decoupled(new ProductRetirement(64, I, P, G))
    val redirect = Valid(new ProductRedirect(64, I, G))
    val trap = Valid(new ProductRetirement(64, I, P, G))
    val occupancy = Output(UInt(log2Ceil(g.robEntries + 1).W))
    val recovering = Output(Bool())
    val acceptedCompletion = Output(Bool())
    val outOfOrderCompletion = Output(Bool())
    val staleCompletion = Output(Bool())
    val unsupported = Output(Bool())
  })
  val d = Module(new SemanticDecode(ProductCoreProfiles.smallEngineering.software.isa))
  d.io.pc := io.pc
  d.io.inst := io.inst
  d.io.rawInst := io.inst
  d.io.instBytes := 4.U
  d.io.fetchException := 0.U.asTypeOf(d.io.fetchException)
  val core = Module(new ProductSmallOoOBackend(g))
  core.io.dispatch.valid := io.dispatchValid
  core.io.dispatch.bits.decoded := d.io.decoded
  core.io.dispatch.bits.predictedNextPc := io.predictedNextPc
  io.dispatchReady := core.io.dispatch.ready

  io.issue.valid := core.io.issue.valid
  io.issue.bits := core.io.issue.bits
  core.io.issue.ready := io.issue.ready
  core.io.completion.valid := io.completion.valid
  core.io.completion.bits := io.completion.bits
  io.completion.ready := core.io.completion.ready
  io.retire.valid := core.io.retire.valid
  io.retire.bits := core.io.retire.bits
  core.io.retire.ready := io.retire.ready
  io.redirect := core.io.redirect
  io.trap := core.io.trap
  io.occupancy := core.io.occupancy
  io.recovering := core.io.recovering
  io.acceptedCompletion := core.io.acceptedCompletion
  io.outOfOrderCompletion := core.io.outOfOrderCompletion
  io.staleCompletion := core.io.staleCompletion
  io.unsupported := core.io.unsupported
}

class ProductSmallOoOBackendSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Product SmallOoO integer/branch backend"

  private case class Ticket(index: BigInt, generation: BigInt,
                            phys: BigInt, physGeneration: BigInt, writes: Boolean)

  private def init(d: SmallOoOTestHarness): Unit = {
    d.io.pc.poke("h80000000".U)
    d.io.inst.poke("h00000013".U)
    d.io.predictedNextPc.poke("h80000004".U)
    d.io.dispatchValid.poke(false.B)
    d.io.issue.ready.poke(true.B)
    d.io.retire.ready.poke(false.B)
    d.io.completion.valid.poke(false.B)
    d.io.completion.bits.poke(0.U.asTypeOf(d.io.completion.bits))
    d.clock.step()
  }

  private def dispatch(d: SmallOoOTestHarness, instruction: Long, pc: Long): Unit = {
    d.io.inst.poke(instruction.U)
    d.io.pc.poke(pc.U)
    d.io.predictedNextPc.poke((pc + 4).U)
    d.io.dispatchValid.poke(true.B)
    d.io.dispatchReady.expect(true.B)
    d.clock.step()
    d.io.dispatchValid.poke(false.B)
  }

  private def issue(d: SmallOoOTestHarness): Ticket = {
    d.io.issue.valid.expect(true.B)
    val bits = d.io.issue.bits
    val ticket = Ticket(bits.token.index.peek().litValue,
      bits.token.generation.peek().litValue,
      bits.destination.index.peek().litValue,
      bits.destination.generation.peek().litValue,
      bits.hasDestination.peek().litToBoolean)
    d.clock.step()
    ticket
  }

  private def complete(d: SmallOoOTestHarness, t: Ticket, value: BigInt,
                       branchTarget: Option[BigInt] = None): Unit = {
    val c = d.io.completion.bits
    c.token.index.poke(t.index.U)
    c.token.generation.poke(t.generation.U)
    c.destination.index.poke(t.phys.U)
    c.destination.generation.poke(t.physGeneration.U)
    c.hasValue.poke(t.writes.B)
    c.value.poke(value.U)
    c.branchValid.poke(branchTarget.nonEmpty.B)
    c.nextPc.poke(branchTarget.getOrElse(BigInt(0)).U)
    c.exception.valid.poke(false.B)
    c.exception.cause.poke(0.U)
    c.exception.value.poke(0.U)
    d.io.completion.valid.poke(true.B)
    d.io.completion.ready.expect(true.B)
    d.clock.step()
    d.io.completion.valid.poke(false.B)
  }

  private def untilRecovered(d: SmallOoOTestHarness): Unit = {
    for (_ <- 0 until 12) {
      if (d.io.recovering.peek().litToBoolean) d.clock.step()
    }
    d.io.recovering.expect(false.B)
  }

  it should "allow a younger independent result to complete before older, but never retire first" in {
    simulate(new SmallOoOTestHarness) { d =>
      init(d)
      dispatch(d, 0x00500093L, 0x80000000L) // addi x1,x0,5
      val older = issue(d)
      dispatch(d, 0x00700113L, 0x80000004L) // addi x2,x0,7
      val younger = issue(d)
      complete(d, younger, 7)
      d.io.retire.valid.expect(false.B)
      complete(d, older, 5)
      d.io.retire.valid.expect(true.B)
      d.io.retire.bits.rd.expect(1.U)
      d.io.retire.bits.value.expect(5.U)
      d.io.retire.ready.poke(true.B)
      d.clock.step()
      d.io.retire.bits.rd.expect(2.U)
      d.io.retire.bits.value.expect(7.U)
      d.clock.step()
      d.io.retire.valid.expect(false.B)
      d.io.occupancy.expect(0.U)
    }
  }

  it should "use PRF readiness for true RAW dependencies and forward completed values" in {
    simulate(new SmallOoOTestHarness) { d =>
      init(d)
      dispatch(d, 0x00500093L, 0x80000000L) // x1 = 5
      val producer = issue(d)
      dispatch(d, 0x00108193L, 0x80000004L) // addi x3,x1,1
      d.io.issue.valid.expect(false.B)
      complete(d, producer, 5)
      d.io.issue.valid.expect(true.B)
      d.io.issue.bits.lhs.expect(5.U)
      d.io.issue.bits.rhs.expect(1.U)
      val child = issue(d)
      complete(d, child, 6)
      d.io.retire.ready.poke(true.B)
      d.io.retire.bits.rd.expect(1.U)
      d.clock.step()
      d.io.retire.bits.rd.expect(3.U)
      d.io.retire.bits.value.expect(6.U)
      d.clock.step()
      d.io.occupancy.expect(0.U)
    }
  }

  it should "recover a non-head mispredicted branch and reject killed completion" in {
    simulate(new SmallOoOTestHarness) { d =>
      init(d)
      dispatch(d, 0x00500093L, 0x80000000L)
      val older = issue(d)
      dispatch(d, 0x00000463L, 0x80000004L) // beq x0,x0,+8
      val branch = issue(d)
      dispatch(d, 0x00700113L, 0x80000008L) // speculative younger
      val killed = issue(d)
      complete(d, branch, 0, Some(BigInt("8000000c", 16)))
      d.io.redirect.valid.expect(false.B) // redirect was a pulse on accepted completion
      d.io.recovering.expect(true.B)
      untilRecovered(d)
      d.io.occupancy.expect(2.U)
      // Same branch survived; killed old result now rejected even if slot is reused.
      val c = d.io.completion.bits
      c.token.index.poke(killed.index.U)
      c.token.generation.poke(killed.generation.U)
      c.destination.index.poke(killed.phys.U)
      c.destination.generation.poke(killed.physGeneration.U)
      c.hasValue.poke(true.B)
      c.value.poke(9.U)
      c.branchValid.poke(false.B)
      c.exception.valid.poke(false.B)
      d.io.completion.valid.poke(true.B)
      d.io.staleCompletion.expect(true.B)
      d.clock.step()
      d.io.completion.valid.poke(false.B)

      complete(d, older, 5)
      d.io.retire.ready.poke(true.B)
      d.io.retire.bits.rd.expect(1.U)
      d.clock.step()
      d.io.retire.valid.expect(true.B)
      d.io.retire.bits.rd.expect(0.U)
      d.clock.step()
      d.io.occupancy.expect(0.U)
    }
  }

  it should "trap precisely on an illegal oldest instruction without committing it" in {
    simulate(new SmallOoOTestHarness) { d =>
      init(d)
      dispatch(d, 0xffffffffL, 0x80000000L)
      d.io.retire.bits.exception.valid.expect(true.B)
      d.io.retire.bits.wroteRegister.expect(false.B)
      d.io.retire.ready.poke(true.B)
      d.io.trap.valid.expect(true.B)
      d.clock.step()
      d.io.recovering.expect(true.B)
      untilRecovered(d)
      d.io.occupancy.expect(0.U)
      d.io.retire.valid.expect(false.B)
    }
  }

  it should "refuse memory/system operations without silently making them integer instructions" in {
    simulate(new SmallOoOTestHarness) { d =>
      init(d)
      d.io.inst.poke("h0040a183".U) // lw x3,4(x1)
      d.io.dispatchValid.poke(true.B)
      d.io.unsupported.expect(true.B)
      d.io.dispatchReady.expect(false.B)
      d.clock.step()
      d.io.occupancy.expect(0.U)
      d.io.inst.poke("h3001d2f3".U) // csrrwi
      d.io.unsupported.expect(true.B)
      d.io.dispatchReady.expect(false.B)
    }
  }
}
