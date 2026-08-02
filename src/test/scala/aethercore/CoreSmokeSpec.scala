package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.sim.AetherCoreSimTop

class CoreSmokeSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore"

  it should "forward dependent results, write UART, and enter a breakpoint trap" in {
    val base = BigInt("80000000", 16)
    val breakpointPc = base + 24
    val program = Map[BigInt, BigInt](
      base + 0  -> BigInt("100002b7", 16),
      base + 4  -> BigInt("04100313", 16),
      base + 8  -> BigInt("00628023", 16),
      base + 12 -> BigInt("00700093", 16),
      base + 16 -> BigInt("00500113", 16),
      base + 20 -> BigInt("002081b3", 16),
      breakpointPc -> BigInt("00100073", 16)
    )

    simulate(new AetherCoreSimTop) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)
      dut.io.memFault.poke(false.B)

      var uart = ""
      var sawX3 = false
      var sawTrap = false
      var cycles = 0

      while (!sawTrap && cycles < 100) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, BigInt("00100073", 16)).U)

        if (dut.io.uartValid.peek().litToBoolean) {
          uart += dut.io.uartByte.peek().litValue.toChar
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          if (dut.io.commit.rdWrite.peek().litToBoolean &&
              dut.io.commit.rd.peek().litValue == 3) {
            dut.io.commit.rdData.expect(12.U)
            sawX3 = true
          }
          if (dut.io.commit.exception.peek().litToBoolean) {
            dut.io.commit.pc.expect(breakpointPc.U)
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.Breakpoint.U)
            dut.io.commit.exceptionValue.expect(breakpointPc.U)
            sawTrap = true
          }
        }
      }

      uart shouldBe "A"
      sawX3 shouldBe true
      sawTrap shouldBe true
      dut.io.halted.expect(false.B)
    }
  }

  it should "preserve a WB operand while a younger load stalls EX" in {
    val base = BigInt("80000000", 16)
    val breakpointPc = base + 28
    val expected = BigInt("fffffffffffffb63", 16)
    val program = Map[BigInt, BigInt](
      base + 0  -> BigInt("6a200b93", 16), // addi x23, x0, 0x6a2: visible stale value
      base + 4  -> BigInt("b6300693", 16), // addi x13, x0, -1181
      base + 8  -> BigInt("00100793", 16), // addi x15, x0, 1
      base + 12 -> BigInt("00200113", 16), // addi x2, x0, 2
      base + 16 -> BigInt("0227cbb3", 16), // div x23, x15, x2 => 0
      base + 20 -> BigInt("00003b03", 16), // ld x22, 0(x0): force one-cycle MEM stall
      base + 24 -> BigInt("00db8833", 16), // add x16, x23, x13 => -1181
      breakpointPc -> BigInt("00100073", 16)
    )

    simulate(new AetherCoreSimTop) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memRdata.poke(0.U)
      dut.io.memFault.poke(false.B)
      dut.io.memReady.poke(true.B)

      var stalledLoad = false
      var sawX16 = false
      var sawTrap = false
      var cycles = 0

      while (!sawTrap && cycles < 100) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, BigInt("00100073", 16)).U)

        val loadInMem = dut.io.memValid.peek().litToBoolean &&
          !dut.io.memWrite.peek().litToBoolean
        val injectStall = loadInMem && !stalledLoad
        dut.io.memReady.poke((!injectStall).B)
        if (injectStall) stalledLoad = true

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          if (dut.io.commit.rdWrite.peek().litToBoolean &&
              dut.io.commit.rd.peek().litValue == 16) {
            dut.io.commit.rdData.expect(expected.U(64.W))
            sawX16 = true
          }
          if (dut.io.commit.exception.peek().litToBoolean) {
            dut.io.commit.pc.expect(breakpointPc.U)
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.Breakpoint.U)
            dut.io.commit.exceptionValue.expect(breakpointPc.U)
            sawTrap = true
          }
        }
      }

      stalledLoad shouldBe true
      sawX16 shouldBe true
      sawTrap shouldBe true
      dut.io.halted.expect(false.B)
    }
  }
}
