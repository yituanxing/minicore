package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.common.AtomicOp
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.v2.TinyPagedCore
import aethercore.memory.AetherMemOp

/** Real machine-code A-extension stream through the complete F7 frontend/backend. */
trait V2F7AtomicCoreChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val Config = CoreProfiles.rv32imasuSv32PmpSoftware
  private val Geometry = PageTableGeometry.Sv32
  private val Reset = Config.platform.resetVector
  private val DataAddress = BigInt("00000100", 16)
  private val Nop = BigInt("00000013", 16)

  private case class PendingResponse(txnId: BigInt, rdata: BigInt)

  private def initialize(dut: TinyPagedCore): Unit = {
    dut.io.imem.inst.poke(Nop.U)
    dut.io.imem.fault.poke(false.B)
    dut.io.time.foreach(_.poke(0.U))

    dut.io.ptw.ready.poke(true.B)
    dut.io.ptw.rdata.poke(0.U)
    dut.io.ptw.fault.poke(false.B)

    dut.io.resolvedAttributes.cacheable.poke(true.B)
    dut.io.resolvedAttributes.idempotent.poke(true.B)
    dut.io.resolvedAttributes.sideEffecting.poke(false.B)
    dut.io.resolvedAttributes.ordered.poke(false.B)
    dut.io.resolvedAttributes.executable.poke(false.B)
    dut.io.resolvedAttributes.supportsAtomic.poke(true.B)
    dut.io.resolvedAttributes.supportsPartial.poke(true.B)

    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  private val Program: Map[BigInt, BigInt] = Seq(
    BigInt("10000093", 16), // addi x1,x0,0x100
    BigInt("00700213", 16), // addi x4,x0,7
    BigInt("1000a12f", 16), // lr.w      x2,(x1)
    BigInt("1840a1af", 16), // sc.w      x3,x4,(x1) -- memory side succeeds
    BigInt("0040a2af", 16), // amoadd.w  x5,x4,(x1)
    BigInt("1000a32f", 16), // lr.w      x6,(x1)
    BigInt("1840a3af", 16), // sc.w      x7,x4,(x1) -- memory side rejects
    BigInt("0000a403", 16)  // lw        x8,0(x1)
  ).zipWithIndex.map { case (inst, index) => (Reset + index * 4) -> inst }.toMap

  behavior of "AetherCore v2 F7 real A-extension instruction flow"

  it should "execute LR/SC and AMOADD from Decoder through AetherMem and precise Commit" in {
    simulate(new TinyPagedCore(Config, Geometry)) { dut =>
      initialize(dut)

      var memoryValue = BigInt(5)
      var externalReservation = false
      var scAttempts = 0
      var pending: Option[PendingResponse] = None
      val requests = mutable.ArrayBuffer.empty[(AetherMemOp.Type, AtomicOp.Type)]
      val commits = mutable.Set.empty[BigInt]

      def driveInstruction(): Unit = {
        val address = dut.io.imem.addr.peek().litValue
        dut.io.imem.inst.poke(Program.getOrElse(address, Nop).U)
        dut.io.imem.fault.poke(false.B)
      }

      def driveResponse(): Unit = pending match {
        case Some(response) =>
          dut.io.memoryResponse.valid.poke(true.B)
          dut.io.memoryResponse.bits.txnId.poke(response.txnId.U)
          dut.io.memoryResponse.bits.rdata.poke(response.rdata.U)
          dut.io.memoryResponse.bits.fault.poke(false.B)
          dut.io.memoryResponse.bits.last.poke(true.B)
        case None =>
          dut.io.memoryResponse.valid.poke(false.B)
          dut.io.memoryResponse.bits.txnId.poke(0.U)
          dut.io.memoryResponse.bits.rdata.poke(0.U)
          dut.io.memoryResponse.bits.fault.poke(false.B)
          dut.io.memoryResponse.bits.last.poke(true.B)
      }

      def checkCommit(): Unit = if (dut.io.commit.valid.peek().litToBoolean) {
        val pc = dut.io.commit.pc.peek().litValue
        dut.io.commit.exception.expect(false.B)
        pc match {
          case p if p == Reset + 8 =>
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(2.U)
            dut.io.commit.rdData.expect(5.U)
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(false.B)
            dut.io.commit.memAddr.expect(DataAddress.U)
            commits += pc
          case p if p == Reset + 12 =>
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(3.U)
            dut.io.commit.rdData.expect(0.U)
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(true.B)
            dut.io.commit.memAddr.expect(DataAddress.U)
            dut.io.commit.memWdata.expect(7.U)
            dut.io.commit.memWmask.expect("hf".U)
            commits += pc
          case p if p == Reset + 16 =>
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(5.U)
            dut.io.commit.rdData.expect(7.U)
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(true.B)
            dut.io.commit.memAddr.expect(DataAddress.U)
            dut.io.commit.memWdata.expect(14.U)
            dut.io.commit.memWmask.expect("hf".U)
            commits += pc
          case p if p == Reset + 20 =>
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(6.U)
            dut.io.commit.rdData.expect(14.U)
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(false.B)
            commits += pc
          case p if p == Reset + 24 =>
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(7.U)
            dut.io.commit.rdData.expect(1.U)
            // The memory system remains the final SC authority. Its failed SC
            // produces no architectural memory-write trace.
            dut.io.commit.memValid.expect(false.B)
            commits += pc
          case p if p == Reset + 28 =>
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(8.U)
            dut.io.commit.rdData.expect(14.U)
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(false.B)
            dut.io.commit.memAddr.expect(DataAddress.U)
            commits += pc
          case _ =>
        }
      }

      var cycles = 0
      while (cycles < 1200 && !commits.contains(Reset + 28)) {
        driveInstruction()
        driveResponse()
        dut.io.memoryRequest.ready.poke(true.B)

        checkCommit()

        val responseFire = pending.nonEmpty && dut.io.memoryResponse.ready.peek().litToBoolean
        val requestFire = dut.io.memoryRequest.valid.peek().litToBoolean
        var newResponse: Option[PendingResponse] = None

        if (requestFire) {
          dut.io.memoryRequest.bits.paddr.expect(DataAddress.U)
          val txn = dut.io.memoryRequest.bits.txnId.peek().litValue
          val op = dut.io.memoryRequest.bits.op.peekValue()
          val atomic = dut.io.memoryRequest.bits.atomicOp.peekValue()
          requests += ((op, atomic))

          op match {
            case AetherMemOp.Atomic =>
              atomic match {
                case AtomicOp.Lr =>
                  dut.io.memoryRequest.bits.wmask.expect(0.U)
                  externalReservation = true
                  newResponse = Some(PendingResponse(txn, memoryValue))
                case AtomicOp.Sc =>
                  dut.io.memoryRequest.bits.wmask.expect("hf".U)
                  val succeed = externalReservation && scAttempts == 0
                  if (succeed) {
                    memoryValue = dut.io.memoryRequest.bits.wdata.peek().litValue & BigInt("ffffffff", 16)
                    newResponse = Some(PendingResponse(txn, 0))
                  } else {
                    newResponse = Some(PendingResponse(txn, 1))
                  }
                  scAttempts += 1
                  externalReservation = false
                case AtomicOp.Add =>
                  dut.io.memoryRequest.bits.wmask.expect("hf".U)
                  val old = memoryValue
                  val operand = dut.io.memoryRequest.bits.wdata.peek().litValue & BigInt("ffffffff", 16)
                  memoryValue = (old + operand) & BigInt("ffffffff", 16)
                  externalReservation = false
                  newResponse = Some(PendingResponse(txn, old))
                case other =>
                  fail(s"unexpected atomic operation in real stream: $other")
              }
            case AetherMemOp.Read =>
              dut.io.memoryRequest.bits.atomicOp.expect(AtomicOp.None)
              newResponse = Some(PendingResponse(txn, memoryValue))
            case other =>
              fail(s"unexpected physical memory operation in real stream: $other")
          }
        }

        dut.clock.step()
        cycles += 1

        pending = if (responseFire) None else pending
        if (newResponse.nonEmpty) {
          withClue("one-outstanding LSU issued a new request while a response was still pending: ") {
            pending shouldBe None
          }
          pending = newResponse
        }
      }

      val expectedCommits = Set(
        Reset + 8,
        Reset + 12,
        Reset + 16,
        Reset + 20,
        Reset + 24,
        Reset + 28
      )
      withClue("real A instruction stream did not retire every expected atomic/load instruction: ") {
        commits should contain allElementsOf expectedCommits
      }
      memoryValue shouldBe BigInt(14)
      scAttempts shouldBe 2

      val expectedRequests = Seq(
        (AetherMemOp.Atomic, AtomicOp.Lr),
        (AetherMemOp.Atomic, AtomicOp.Sc),
        (AetherMemOp.Atomic, AtomicOp.Add),
        (AetherMemOp.Atomic, AtomicOp.Lr),
        (AetherMemOp.Atomic, AtomicOp.Sc),
        (AetherMemOp.Read, AtomicOp.None)
      )
      requests.toSeq shouldBe expectedRequests
    }
  }
}
