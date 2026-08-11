package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.common.MachineExceptionCode
import aethercore.config.CoreProfiles
import aethercore.sim.AetherCoreSimTop

class CoreSmokeSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore"

  private val ebreak = BigInt("00100073", 16)

  private def initialize(dut: AetherCoreSimTop): Unit = {
    dut.io.imemFault.poke(false.B)
    dut.io.memReady.poke(true.B)
    dut.io.memRdata.poke(0.U)
    dut.io.memFault.poke(false.B)
  }

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
      breakpointPc -> ebreak
    )

    simulate(new AetherCoreSimTop) { dut =>
      initialize(dut)

      var uart = ""
      var sawX3 = false
      var sawTrap = false
      var cycles = 0

      while (!sawTrap && cycles < 100) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, ebreak).U)

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
      breakpointPc -> ebreak
    )

    simulate(new AetherCoreSimTop) { dut =>
      initialize(dut)

      var stalledLoad = false
      var sawX16 = false
      var sawTrap = false
      var cycles = 0

      while (!sawTrap && cycles < 100) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, ebreak).U)

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

  it should "raise a precise exception for an RV64 JAL to a two-byte-aligned target" in {
    val base = BigInt("80000000", 16)
    val target = base + 2
    val jalX1Plus2 = BigInt("002000ef", 16)
    val program = Map(base -> jalX1Plus2, base + 4 -> ebreak)

    simulate(new AetherCoreSimTop) { dut =>
      initialize(dut)
      var sawTrap = false
      var cycles = 0

      while (!sawTrap && cycles < 80) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.pc.expect(base.U)
          dut.io.commit.inst.expect(jalX1Plus2.U)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAddressMisaligned.U)
          dut.io.commit.exceptionValue.expect(target.U)
          dut.io.commit.rd.expect(1.U)
          dut.io.commit.rdWrite.expect(false.B)
          sawTrap = true
        }
      }

      sawTrap shouldBe true
    }
  }

  it should "check JALR alignment after clearing target bit zero" in {
    val base = BigInt("80000000", 16)
    val target = base + 2
    val auipcX1 = BigInt("00000097", 16)
    val jalrX5X1Plus2 = BigInt("002082e7", 16)
    val program = Map(
      base -> auipcX1,
      base + 4 -> jalrX5X1Plus2,
      base + 8 -> ebreak
    )

    simulate(new AetherCoreSimTop) { dut =>
      initialize(dut)
      var sawTrap = false
      var cycles = 0

      while (!sawTrap && cycles < 100) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.pc.expect((base + 4).U)
          dut.io.commit.inst.expect(jalrX5X1Plus2.U)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAddressMisaligned.U)
          dut.io.commit.exceptionValue.expect(target.U)
          dut.io.commit.rd.expect(5.U)
          dut.io.commit.rdWrite.expect(false.B)
          sawTrap = true
        }
      }

      sawTrap shouldBe true
    }
  }

  it should "trap a taken misaligned branch but not the same target when the branch is not taken" in {
    val base = BigInt("80000000", 16)
    val target = base + 2
    val beqX0X0Plus2 = BigInt("00000163", 16)

    simulate(new AetherCoreSimTop) { dut =>
      initialize(dut)
      var sawTrap = false
      var cycles = 0

      while (!sawTrap && cycles < 80) {
        dut.io.imemInst.poke((if (dut.io.imemAddr.peek().litValue == base) beqX0X0Plus2 else ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.pc.expect(base.U)
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAddressMisaligned.U)
          dut.io.commit.exceptionValue.expect(target.U)
          sawTrap = true
        }
      }

      sawTrap shouldBe true
    }

    val bneX0X0Plus2 = BigInt("00001163", 16)
    val addiX3Seven = BigInt("00700193", 16)
    val breakpointPc = base + 8
    val program = Map(
      base -> bneX0X0Plus2,
      base + 4 -> addiX3Seven,
      breakpointPc -> ebreak
    )

    simulate(new AetherCoreSimTop) { dut =>
      initialize(dut)
      var sawX3 = false
      var sawBreakpoint = false
      var cycles = 0

      while (!sawBreakpoint && cycles < 100) {
        dut.io.imemInst.poke(program.getOrElse(dut.io.imemAddr.peek().litValue, ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean) {
          if (dut.io.commit.exception.peek().litToBoolean) {
            dut.io.commit.pc.expect(breakpointPc.U)
            dut.io.commit.exceptionCause.expect(MachineExceptionCode.Breakpoint.U)
            sawBreakpoint = true
          }.elsewhen(dut.io.commit.rdWrite.peek().litToBoolean) {
            dut.io.commit.rd.expect(3.U)
            dut.io.commit.rdData.expect(7.U)
            sawX3 = true
          }
        }
      }

      sawX3 shouldBe true
      sawBreakpoint shouldBe true
    }
  }

  it should "raise instruction-address-misaligned in the executable RV32I profile" in {
    val base = BigInt("80000000", 16)
    val target = base + 2
    val jalX1Plus2 = BigInt("002000ef", 16)

    simulate(new AetherCoreSimTop(CoreProfiles.rv32iMinimal)) { dut =>
      initialize(dut)
      var sawTrap = false
      var cycles = 0

      while (!sawTrap && cycles < 80) {
        dut.io.imemInst.poke((if (dut.io.imemAddr.peek().litValue == base) jalX1Plus2 else ebreak).U)
        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean && dut.io.commit.exception.peek().litToBoolean) {
          dut.io.commit.pc.expect(base.U(32.W))
          dut.io.commit.exceptionCause.expect(MachineExceptionCode.InstructionAddressMisaligned.U)
          dut.io.commit.exceptionValue.expect(target.U(32.W))
          dut.io.commit.rd.expect(1.U)
          dut.io.commit.rdWrite.expect(false.B)
          sawTrap = true
        }
      }

      sawTrap shouldBe true
    }
  }
}
