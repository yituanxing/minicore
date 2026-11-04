package aethercore

import aethercore.common.MachineExceptionCode
import aethercore.core.MachineCsrBit
import aethercore.core.product._
import aethercore.memory.AetherMemOp
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Full Small RV64GC fetch -> RVC -> privilege -> rename -> HardFloat -> ROB
  * retirement.  Unlike a floating execution-slice harness, these tests own
  * neither the FCSR nor mstatus.FS: those must pass through the one real
  * MachineCsrFile, including the exception and memory paths.
  */
class ProductSmallPagedFloatingSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Small Product RV64GC paged floating integration"

  private val profile = ProductCoreProfiles.smallDebianTarget
  private val boot = profile.platform.resetVector
  private val nop = BigInt("00000013", 16)
  private val sevenDouble = BigInt("401c000000000000", 16)
  private val fourteenDouble = BigInt("402c000000000000", 16)

  private def addi(rd: Int, rs1: Int, imm: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) | (BigInt(rs1) << 15) |
      (BigInt(rd) << 7) | 0x13

  private def slli(rd: Int, rs1: Int, shift: Int): BigInt =
    (BigInt(shift) << 20) | (BigInt(rs1) << 15) |
      (BigInt(1) << 12) | (BigInt(rd) << 7) | 0x13

  private def csrrs(rd: Int, csr: Int, rs1: Int): BigInt =
    (BigInt(csr) << 20) | (BigInt(rs1) << 15) |
      (BigInt(2) << 12) | (BigInt(rd) << 7) | 0x73

  private def csrrw(rd: Int, csr: Int, rs1: Int): BigInt =
    (BigInt(csr) << 20) | (BigInt(rs1) << 15) |
      (BigInt(1) << 12) | (BigInt(rd) << 7) | 0x73

  private def fp(funct7: Int, rs2: Int, rs1: Int, rm: Int, rd: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(rm) << 12) |
      (BigInt(rd) << 7) | 0x53

  private def fld(rd: Int, rs1: Int, imm: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) | (BigInt(rs1) << 15) |
      (BigInt(3) << 12) | (BigInt(rd) << 7) | 0x07

  private def fsd(rs2: Int, rs1: Int, imm: Int): BigInt =
    (BigInt((imm >> 5) & 0x7f) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(3) << 12) |
      (BigInt(imm & 31) << 7) | 0x27

  /** With RV64C enabled the physical instruction bus returns 16-bit parcels.
    * In particular, PC+2 is NOT the next 32-bit instruction.
    */
  private def parcels(words: Map[BigInt, BigInt]): Map[BigInt, BigInt] =
    words.toSeq.flatMap { case (pc, inst) =>
      Seq(pc -> (inst & 0xffff), (pc + 2) -> ((inst >> 16) & 0xffff))
    }.toMap

  private def init(d: ProductSmallPagedCore, rom: Map[BigInt, BigInt]): Unit = {
    // The RVC parcel controller may accept the very first halfword on this
    // initialization clock; give it the real ROM byte pair, not a dummy NOP.
    d.io.imem.inst.poke(rom.getOrElse(boot, nop & 0xffff).U)
    d.io.imem.fault.poke(false.B)
    d.io.ptw.ready.poke(false.B)
    d.io.ptw.rdata.poke(0.U)
    d.io.ptw.fault.poke(false.B)
    d.io.resolvedAttributes.cacheable.poke(true.B)
    d.io.resolvedAttributes.idempotent.poke(true.B)
    d.io.resolvedAttributes.sideEffecting.poke(false.B)
    d.io.resolvedAttributes.ordered.poke(false.B)
    d.io.resolvedAttributes.executable.poke(true.B)
    d.io.resolvedAttributes.supportsAtomic.poke(true.B)
    d.io.resolvedAttributes.supportsPartial.poke(true.B)
    d.io.memoryRequest.ready.poke(true.B)
    d.io.memoryResponse.valid.poke(false.B)
    d.io.memoryResponse.bits.poke(0.U.asTypeOf(d.io.memoryResponse.bits))
    if (d.config.software.isa.hasTimeCounter) d.io.time.get.poke(0.U)
    d.io.timerInterrupt.poke(false.B)
    if (d.withMachineExternalInterrupt) d.io.machineExternalInterrupt.get.poke(false.B)
    if (d.withSupervisorExternalInterrupt) d.io.supervisorExternalInterrupt.get.poke(false.B)
    if (d.enableInstructionBackpressure) d.io.imemReady.get.poke(true.B)
    d.clock.step()
  }

  private def fetch(d: ProductSmallPagedCore, rom: Map[BigInt, BigInt]): Unit = {
    val addr = d.io.imem.addr.peek().litValue
    d.io.imem.inst.poke(rom.getOrElse(addr, nop & 0xffff).U)
    d.io.imem.fault.poke(false.B)
  }

  it should "raise precise illegal instruction for arithmetic and FLD while FS=Off" in {
    val first = fp(0x01, rs2 = 1, rs1 = 1, rm = 0, rd = 2) // FADD.D
    val second = fld(rd = 4, rs1 = 0, imm = 0)
    val rom = parcels(Map(boot -> first, BigInt(0) -> second))
    simulate(new ProductSmallPagedCore(profile)) { d =>
      init(d, rom)
      var arithmeticFault = false
      var loadFault = false
      for (_ <- 0 until 190 if !loadFault) {
        fetch(d, rom)
        d.io.memoryRequest.valid.expect(false.B)
        if (d.io.retire.valid.peek().litToBoolean) {
          val pc = d.io.retire.bits.pc.peek().litValue
          if (pc == boot || pc == 0) {
            d.io.retire.bits.exception.valid.expect(true.B)
            d.io.retire.bits.exception.cause.expect(MachineExceptionCode.IllegalInstruction.U)
            d.io.retire.bits.exception.value.expect((if (pc == boot) first else second).U)
            d.io.retire.bits.wroteFloatingRegister.expect(false.B)
            if (pc == boot) arithmeticFault = true else loadFault = true
          }
        }
        d.clock.step()
      }
      arithmeticFault shouldBe true
      loadFault shouldBe true
    }
  }

  it should "preserve one architectural FCSR through fetched arithmetic, FSD/FLD and exact ROB retirement" in {
    val instructions = Seq(
      addi(1, 0, 1),             // x1 = 1
      slli(1, 1, 13),            // mstatus.FS = Initial
      csrrs(0, 0x300, 1),        // only the real CSR owner enables FP
      addi(2, 0, 7),
      fp(0x69, 0, 2, 0, 1),      // FCVT.D.W f1,x2 (7.0)
      fsd(1, 0, 0),              // FSD f1,0(x0)
      fld(2, 0, 0),              // FLD f2,0(x0)
      fp(0x01, 1, 2, 0, 3),      // FADD.D f3,f2,f1 (14.0)
      fp(0x61, 0, 3, 1, 3),      // FCVT.W.D x3,f3,RTZ
      csrrs(4, 0x300, 0),        // mstatus: FS=Dirty, SD=1
      csrrs(5, 0x100, 0),        // sstatus exposes the same architectural FS
      csrrs(6, 0x001, 0),        // fflags are still zero for exact arithmetic
      addi(7, 0, 3),
      csrrw(0, 0x002, 7),        // frm = RUP
      csrrs(8, 0x002, 0),        // read back frm
      fp(0x0d, 0, 2, 7, 4),      // FDIV.D f4,f2,f0,dynamic frm: +inf, DZ
      csrrs(9, 0x001, 0),        // fflags must become DZ on exact retirement
      nop
    )
    val rom = parcels(instructions.zipWithIndex.map { case (inst, n) =>
      (boot + 4 * n) -> inst
    }.toMap)
    simulate(new ProductSmallPagedCore(profile)) { d =>
      init(d, rom)
      val retired = scala.collection.mutable.Set.empty[BigInt]
      var physicalStore = false
      var physicalLoad = false
      val replies = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      var storedData: Option[BigInt] = None
      for (_ <- 0 until 680 if !retired.contains(boot + 64)) {
        fetch(d, rom)
        d.io.memoryResponse.valid.poke(false.B)
        replies.headOption.foreach { case (txn, data) =>
          d.io.memoryResponse.bits.txnId.poke(txn.U)
          d.io.memoryResponse.bits.rdata.poke(data.U)
          d.io.memoryResponse.bits.fault.poke(false.B)
          d.io.memoryResponse.bits.last.poke(true.B)
          d.io.memoryResponse.valid.poke(true.B)
        }
        val responseAccepted = replies.nonEmpty &&
          d.io.memoryResponse.ready.peek().litToBoolean
        var nextReply: Option[(BigInt, BigInt)] = None
        if (d.io.memoryRequest.valid.peek().litToBoolean) {
          val req = d.io.memoryRequest.bits
          req.paddr.expect(0.U)
          val txn = req.txnId.peek().litValue
          val op = req.op.peek().litValue
          if (op == AetherMemOp.Write.litValue) {
            req.op.expect(AetherMemOp.Write)
            req.wdata.expect(sevenDouble.U)
            req.wmask.expect(0xff.U)
            physicalStore = true
            storedData = Some(req.wdata.peek().litValue)
            nextReply = Some(txn -> BigInt(0))
          } else {
            req.op.expect(AetherMemOp.Read)
            storedData shouldBe Some(sevenDouble)
            physicalLoad = true
            nextReply = Some(txn -> sevenDouble)
          }
        }
        if (d.io.retire.valid.peek().litToBoolean) {
          val pc = d.io.retire.bits.pc.peek().litValue
          d.io.retire.bits.exception.valid.expect(false.B)
          retired += pc
          if (pc == boot + 16) {
            d.io.commit.floating.get.rdWrite.expect(true.B)
            d.io.commit.floating.get.rd.expect(1.U)
            d.io.commit.floating.get.rdData.expect(sevenDouble.U)
            d.io.commit.floating.get.stateDirty.expect(true.B)
          }
          if (pc == boot + 24) {
            d.io.commit.floating.get.rdWrite.expect(true.B)
            d.io.commit.floating.get.rd.expect(2.U)
            d.io.commit.floating.get.rdData.expect(sevenDouble.U)
          }
          if (pc == boot + 28) {
            d.io.commit.floating.get.rdWrite.expect(true.B)
            d.io.commit.floating.get.rd.expect(3.U)
            d.io.commit.floating.get.rdData.expect(fourteenDouble.U)
          }
          if (pc == boot + 32) {
            d.io.retire.bits.rd.expect(3.U)
            d.io.retire.bits.value.expect(14.U)
            d.io.commit.floating.get.rdWrite.expect(false.B)
          }
          if (pc == boot + 36 || pc == boot + 40) {
            val status = d.io.retire.bits.value.peek().litValue
            ((status >> MachineCsrBit.MstatusFsLow) & 3) shouldBe BigInt(3)
            ((status >> 63) & 1) shouldBe BigInt(1)
          }
          if (pc == boot + 44) d.io.retire.bits.value.expect(0.U)
          if (pc == boot + 56) d.io.retire.bits.value.expect(3.U)
          if (pc == boot + 60) {
            d.io.commit.floating.get.rdWrite.expect(true.B)
            d.io.commit.floating.get.rd.expect(4.U)
            d.io.commit.floating.get.rdData.expect(BigInt("7ff0000000000000", 16).U)
            d.io.commit.floating.get.fflags.expect(8.U) // exact divide-by-zero flag
          }
          if (pc == boot + 64) d.io.retire.bits.value.expect(8.U)
        }
        d.clock.step()
        // A physical response remains valid with the same ID/data until the
        // LSQ accepts it, even when its completion queue backpressures.
        if (responseAccepted) replies.dequeue()
        nextReply.foreach(reply => replies.enqueue(reply))
      }
      physicalStore shouldBe true
      // Depending on the LSQ's precise forwarding policy a younger FLD may
      // read the retired store locally rather than issue a physical read.
      if (physicalLoad) storedData shouldBe Some(sevenDouble)
      retired.contains(boot + 64) shouldBe true
      Seq(boot + 16, boot + 20, boot + 24, boot + 28, boot + 32,
        boot + 36, boot + 40, boot + 44, boot + 52, boot + 56,
        boot + 60, boot + 64).foreach { pc =>
        withClue(s"missing precise RV64GC retirement at PC 0x${pc.toString(16)}: ") {
          retired.contains(pc) shouldBe true
        }
      }
    }
  }

  it should "preserve FS across a real ECALL trap and MRET before retiring new floating work" in {
    val bootInstructions = Seq(
      addi(1, 0, 1),
      slli(1, 1, 13),
      csrrs(0, 0x300, 1),        // FS=Initial before entering any trap
      BigInt("00000073", 16),   // ECALL from M mode
      addi(2, 0, 9),
      fp(0x69, 0, 2, 0, 1),     // FCVT.D.W f1,x2
      fp(0x61, 0, 1, 1, 3),     // FCVT.W.D x3,f1,RTZ
      csrrs(4, 0x300, 0),      // FS=Dirty after the post-MRET FPR write
      nop
    )
    // Machine mtvec resets to zero.  The handler advances *the architectural*
    // mepc and returns with MRET; the test does not force the frontend PC.
    val handler = Seq(
      csrrs(10, 0x341, 0),      // read mepc
      addi(10, 10, 4),         // ECALL resumes at the following instruction
      csrrw(0, 0x341, 10),     // write the precise return PC
      csrrs(11, 0x300, 0),     // inspect unchanged FS while in trap handler
      BigInt("30200073", 16)  // mret
    )
    val rom = parcels(
      bootInstructions.zipWithIndex.map { case (inst, n) =>
        (boot + 4 * n) -> inst
      }.toMap ++ handler.zipWithIndex.map { case (inst, n) =>
        BigInt(4 * n) -> inst
      }.toMap)
    simulate(new ProductSmallPagedCore(profile)) { d =>
      init(d, rom)
      var ecallSeen = false
      var handlerStatusSeen = false
      var mretSeen = false
      var fpRetired = false
      var conversionRetired = false
      var dirtyStatusSeen = false
      for (_ <- 0 until 490 if !dirtyStatusSeen) {
        fetch(d, rom)
        d.io.memoryRequest.valid.expect(false.B)
        if (d.io.retire.valid.peek().litToBoolean) {
          val pc = d.io.retire.bits.pc.peek().litValue
          if (pc == boot + 12) {
            d.io.retire.bits.exception.valid.expect(true.B)
            ecallSeen = true
          } else {
            d.io.retire.bits.exception.valid.expect(false.B)
          }
          if (pc == 12) {
            val status = d.io.retire.bits.value.peek().litValue
            ((status >> MachineCsrBit.MstatusFsLow) & 3) shouldBe BigInt(1)
            handlerStatusSeen = true
          }
          if (pc == 16) mretSeen = true
          if (pc == boot + 20) {
            d.io.commit.floating.get.rdWrite.expect(true.B)
            d.io.commit.floating.get.rdData.expect(BigInt("4022000000000000", 16).U)
            fpRetired = true
          }
          if (pc == boot + 24) {
            d.io.retire.bits.rd.expect(3.U)
            d.io.retire.bits.value.expect(9.U)
            conversionRetired = true
          }
          if (pc == boot + 28) {
            val status = d.io.retire.bits.value.peek().litValue
            ((status >> MachineCsrBit.MstatusFsLow) & 3) shouldBe BigInt(3)
            dirtyStatusSeen = true
          }
        }
        d.clock.step()
      }
      ecallSeen shouldBe true
      handlerStatusSeen shouldBe true
      mretSeen shouldBe true
      fpRetired shouldBe true
      conversionRetired shouldBe true
      dirtyStatusSeen shouldBe true
    }
  }
}
