package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.config.{CoreProfiles, PageTableGeometry}
import aethercore.core.v2.TinyBareCore
import aethercore.memory.AetherMemOp

trait V2F7BareCoreChecks { this: AnyFlatSpec with Matchers with ChiselSim =>
  private val Config = CoreProfiles.rv64imsuSv39PmpSoftware
  private val Reset = Config.platform.resetVector

  private def pokeEnvironment(dut: TinyBareCore): Unit = {
    dut.io.imem.inst.poke("h00000013".U) // NOP
    dut.io.imem.fault.poke(false.B)
    dut.io.time.foreach(_.poke(0.U))

    dut.io.ptw.ready.poke(false.B)
    dut.io.ptw.rdata.poke(0.U)
    dut.io.ptw.fault.poke(false.B)

    dut.io.resolvedAttributes.cacheable.poke(true.B)
    dut.io.resolvedAttributes.idempotent.poke(true.B)
    dut.io.resolvedAttributes.sideEffecting.poke(false.B)
    dut.io.resolvedAttributes.ordered.poke(false.B)
    dut.io.resolvedAttributes.executable.poke(false.B)
    dut.io.resolvedAttributes.supportsAtomic.poke(false.B)
    dut.io.resolvedAttributes.supportsPartial.poke(true.B)

    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.txnId.poke(0.U)
    dut.io.memoryResponse.bits.rdata.poke(0.U)
    dut.io.memoryResponse.bits.fault.poke(false.B)
    dut.io.memoryResponse.bits.last.poke(true.B)
  }

  private def driveInstruction(dut: TinyBareCore, program: Map[BigInt, BigInt]): Unit = {
    val address = dut.io.imem.addr.peek().litValue
    val inst = program.getOrElse(address, BigInt("00000013", 16))
    dut.io.imem.inst.poke(inst.U)
    dut.io.imem.fault.poke(false.B)
  }

  behavior of "AetherCore v2 F7 bare real-instruction-flow core"

  it should "execute a dependent instruction stream and squash a taken-branch wrong path" in {
    simulate(new TinyBareCore(Config, PageTableGeometry.Sv39)) { dut =>
      pokeEnvironment(dut)

      val program = Map(
        (Reset + 0x00) -> BigInt("00500093", 16), // addi x1,x0,5
        (Reset + 0x04) -> BigInt("00308113", 16), // addi x2,x1,3
        (Reset + 0x08) -> BigInt("002081b3", 16), // add  x3,x1,x2 = 13
        (Reset + 0x0c) -> BigInt("00318463", 16), // beq  x3,x3,+8
        (Reset + 0x10) -> BigInt("06300213", 16), // wrong path: addi x4,x0,99
        (Reset + 0x14) -> BigInt("00700213", 16), // target: addi x4,x0,7
        (Reset + 0x18) -> BigInt("00120293", 16)  // addi x5,x4,1
      )

      val commits = mutable.ArrayBuffer.empty[(BigInt, Int, BigInt, Boolean)]
      var cycles = 0
      var sawFinal = false
      while (cycles < 160 && !sawFinal) {
        driveInstruction(dut, program)

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          val rd = dut.io.commit.rd.peek().litValue.toInt
          val data = dut.io.commit.rdData.peek().litValue
          val rdWrite = dut.io.commit.rdWrite.peek().litToBoolean
          dut.io.commit.exception.expect(false.B)
          commits += ((pc, rd, data, rdWrite))
          if (pc == Reset + 0x18) {
            rdWrite shouldBe true
            rd shouldBe 5
            data shouldBe 8
            sawFinal = true
          }
        }

        dut.clock.step()
        cycles += 1
      }

      withClue("final target instruction never retired: ") { sawFinal shouldBe true }
      commits.map(_._1) should contain allOf (
        Reset + 0x00,
        Reset + 0x04,
        Reset + 0x08,
        Reset + 0x0c,
        Reset + 0x14,
        Reset + 0x18
      )
      withClue("taken-branch wrong path retired: ") {
        commits.exists(_._1 == Reset + 0x10) shouldBe false
      }

      val x1 = commits.find(_._1 == Reset + 0x00).get
      x1._4 shouldBe true
      x1._2 shouldBe 1
      x1._3 shouldBe 5
      val x2 = commits.find(_._1 == Reset + 0x04).get
      x2._3 shouldBe 8
      val x3 = commits.find(_._1 == Reset + 0x08).get
      x3._3 shouldBe 13
      val x4 = commits.find(_._1 == Reset + 0x14).get
      x4._3 shouldBe 7
    }
  }

  it should "execute real SW/LW encodings through decode, LSU, AetherMem and precise Commit" in {
    simulate(new TinyBareCore(Config, PageTableGeometry.Sv39)) { dut =>
      pokeEnvironment(dut)

      val program = Map(
        (Reset + 0x00) -> BigInt("10000093", 16), // addi x1,x0,0x100
        (Reset + 0x04) -> BigInt("02a00113", 16), // addi x2,x0,42
        (Reset + 0x08) -> BigInt("0020a023", 16), // sw x2,0(x1)
        (Reset + 0x0c) -> BigInt("0000a183", 16)  // lw x3,0(x1)
      )

      val bytes = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      var pendingResponse: Option[(BigInt, BigInt)] = None
      var storeCommitSeen = false
      var loadCommitSeen = false
      var cycles = 0

      while (cycles < 200 && !loadCommitSeen) {
        driveInstruction(dut, program)

        pendingResponse match {
          case Some((txn, data)) =>
            dut.io.memoryResponse.valid.poke(true.B)
            dut.io.memoryResponse.bits.txnId.poke(txn.U)
            dut.io.memoryResponse.bits.rdata.poke(data.U)
            dut.io.memoryResponse.bits.fault.poke(false.B)
            dut.io.memoryResponse.bits.last.poke(true.B)
          case None =>
            dut.io.memoryResponse.valid.poke(false.B)
            dut.io.memoryResponse.bits.txnId.poke(0.U)
            dut.io.memoryResponse.bits.rdata.poke(0.U)
            dut.io.memoryResponse.bits.fault.poke(false.B)
            dut.io.memoryResponse.bits.last.poke(true.B)
        }

        val responseWillFire = pendingResponse.nonEmpty && dut.io.memoryResponse.ready.peek().litToBoolean
        val requestWillFire = dut.io.memoryRequest.valid.peek().litToBoolean &&
          dut.io.memoryRequest.ready.peek().litToBoolean

        var nextResponse: Option[(BigInt, BigInt)] = None
        if (requestWillFire) {
          val txn = dut.io.memoryRequest.bits.txnId.peek().litValue
          val address = dut.io.memoryRequest.bits.paddr.peek().litValue
          dut.io.memoryRequest.bits.op.peek().litValue match {
            case op if op == AetherMemOp.Write.litValue =>
              val data = dut.io.memoryRequest.bits.wdata.peek().litValue
              val mask = dut.io.memoryRequest.bits.wmask.peek().litValue
              for (lane <- 0 until 8 if ((mask >> lane) & 1) != 0) {
                bytes(address + lane) = ((data >> (8 * lane)) & 0xff).toInt
              }
              nextResponse = Some(txn -> BigInt(0))
            case op if op == AetherMemOp.Read.litValue =>
              var data = BigInt(0)
              for (lane <- 0 until 8) {
                data |= BigInt(bytes(address + lane)) << (8 * lane)
              }
              nextResponse = Some(txn -> data)
            case other => fail(s"unexpected AetherMem op $other")
          }
        }

        if (dut.io.commit.valid.peek().litToBoolean) {
          val pc = dut.io.commit.pc.peek().litValue
          dut.io.commit.exception.expect(false.B)
          if (pc == Reset + 0x08) {
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(true.B)
            dut.io.commit.memAddr.expect(0x100.U)
            dut.io.commit.memWdata.expect(42.U)
            dut.io.commit.memWmask.expect("hf".U)
            storeCommitSeen = true
          }
          if (pc == Reset + 0x0c) {
            dut.io.commit.memValid.expect(true.B)
            dut.io.commit.memWrite.expect(false.B)
            dut.io.commit.rdWrite.expect(true.B)
            dut.io.commit.rd.expect(3.U)
            dut.io.commit.rdData.expect(42.U)
            loadCommitSeen = true
          }
        }

        dut.clock.step()
        cycles += 1

        // The old response is consumed at this edge. A newly accepted request
        // becomes the one response offered on the next cycle.
        pendingResponse = if (requestWillFire) nextResponse
          else if (responseWillFire) None
          else pendingResponse
      }

      withClue("store never reached architectural retirement: ") { storeCommitSeen shouldBe true }
      withClue("load never returned the value written by the real SW encoding: ") { loadCommitSeen shouldBe true }
      bytes(0x100) shouldBe 42
      bytes(0x101) shouldBe 0
      bytes(0x102) shouldBe 0
      bytes(0x103) shouldBe 0
    }
  }
}
