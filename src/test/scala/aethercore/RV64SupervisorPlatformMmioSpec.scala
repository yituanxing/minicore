package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class RV64SupervisorPlatformMmioSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore RV64 Supervisor platform MMIO bridge"

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(opcode & 0x7f)

  private def sType(imm: Int, rs2: Int, rs1: Int, funct3: Int): BigInt =
    (BigInt((imm >> 5) & 0x7f) << 25) |
      (BigInt(rs2 & 0x1f) << 20) |
      (BigInt(rs1 & 0x1f) << 15) |
      (BigInt(funct3 & 0x7) << 12) |
      (BigInt(imm & 0x1f) << 7) |
      BigInt(0x23)

  private def uType(imm20: Int, rd: Int): BigInt =
    (BigInt(imm20 & 0xfffff) << 12) |
      (BigInt(rd & 0x1f) << 7) |
      BigInt(0x37)

  private val rv64SupervisorPlatform = CoreProfiles.rv64imsuSoftware.copy(
    name = "rv64-supervisor-platform-mmio-test"
  )

  it should "bridge RV64 accesses into 32-bit PLIC/UART registers and 64-bit timer state" in {
    val base = BigInt("80000000", 16)

    val plicPriorityStore = sType(0x28, rs2 = 2, rs1 = 1, funct3 = 2) // sw x2,40(x1)
    val plicEnableStore = sType(0x80, rs2 = 7, rs1 = 3, funct3 = 2)   // sw x7,128(x3)
    val plicThresholdStore = sType(0, rs2 = 0, rs1 = 4, funct3 = 2)  // sw x0,0(x4)
    val uartIerStore = sType(1, rs2 = 2, rs1 = 5, funct3 = 0)        // sb x2,1(x5)
    val timerStore = sType(0, rs2 = 8, rs1 = 6, funct3 = 3)          // sd x8,0(x6)

    val program = Seq(
      uType(0x0c000, 1),                         // x1 = PLIC base 0x0c000000
      iType(1, 0, 0, 2, 0x13),                  // x2 = 1
      plicPriorityStore,                         // priority(source 10) = 1
      uType(0x0c002, 3),                         // x3 = 0x0c002000
      iType(1024, 0, 0, 7, 0x13),               // x7 = enable bit for source 10
      plicEnableStore,                           // S-context enable word
      uType(0x0c201, 4),                         // x4 = S-context threshold 0x0c201000
      plicThresholdStore,                        // threshold = 0
      uType(0x10000, 5),                         // x5 = UART/exit base 0x10000000
      uartIerStore,                              // IER.RDI = 1 via byte store
      uType(0x02004, 6),                         // x6 = mtimecmp 0x02004000
      uType(0x00001, 8),                         // x8 = 0x1000
      timerStore,                                // 64-bit mtimecmp write
      iType(0, 6, 3, 9, 0x03),                  // ld x9,0(x6)
      iType(0x28, 1, 2, 10, 0x03),              // lw x10,40(x1)
      iType(0x80, 3, 2, 11, 0x03),              // lw x11,128(x3)
      iType(1, 5, 4, 12, 0x03),                 // lbu x12,1(x5)
      iType(0, 0, 0, 0, 0x13),
      iType(0, 0, 0, 0, 0x13),
      iType(0, 0, 0, 0, 0x13),
      iType(0, 0, 0, 0, 0x13),
      iType(0, 0, 0, 0, 0x13),
      sType(8, 0, 5, 3),                         // sd x0,8(x5): exit 0
      BigInt("00100073", 16)
    ).zipWithIndex.map { case (inst, index) => base + index * 4 -> inst }.toMap

    simulate(new AetherCoreSimTop(
      config = rv64SupervisorPlatform,
      stopOnTrap = false,
      withSupervisorInterruptPlatform = true,
      stopOnWfi = false,
      withNs16550Uart = true,
      supervisorPlicSourceCount = 52,
      supervisorUartSourceId = 10
    )) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)
      dut.io.memFault.poke(false.B)
      dut.io.rxValid.get.poke(false.B)
      dut.io.rxByte.get.poke(0.U)

      val retired = mutable.Map.empty[Int, BigInt]
      var injectRx = false
      var rxAccepted = false
      var sawSupervisorInterrupt = false
      var sawUartRxInterrupt = false
      var sawExternalRamDataRequest = false
      var sawExit = false
      var cycles = 0

      while (!sawExit && cycles < 500) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, BigInt("00100073", 16)).U)

        val driveRx = injectRx && !rxAccepted
        dut.io.rxValid.get.poke(driveRx.B)
        dut.io.rxByte.get.poke("h41".U)

        if (dut.io.memValid.peek().litToBoolean) sawExternalRamDataRequest = true
        if (dut.io.supervisorExternalInterrupt.get.peek().litToBoolean)
          sawSupervisorInterrupt = true
        if (dut.io.uartRxInterrupt.get.peek().litToBoolean)
          sawUartRxInterrupt = true
        if (driveRx && dut.io.rxReady.get.peek().litToBoolean)
          rxAccepted = true

        if (dut.io.exitValid.peek().litToBoolean) {
          sawExit = true
          dut.io.exitCode.peek().litValue shouldBe 0
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          if (dut.io.commit.rdWrite.peek().litToBoolean)
            retired(dut.io.commit.rd.peek().litValue.toInt) = dut.io.commit.rdData.peek().litValue
          if (dut.io.commit.inst.peek().litValue == uartIerStore)
            injectRx = true
        }
      }

      sawExit shouldBe true
      rxAccepted shouldBe true
      sawUartRxInterrupt shouldBe true
      sawSupervisorInterrupt shouldBe true
      sawExternalRamDataRequest shouldBe false

      retired(9) shouldBe BigInt(0x1000)
      retired(10) shouldBe BigInt(1)
      retired(11) shouldBe BigInt(1024)
      retired(12) shouldBe BigInt(1)

      dut.io.mtimecmp.peek().litValue shouldBe BigInt(0x1000)
    }
  }
}
