package aethercore

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.sim.AetherCoreSimTop

class CoreSmokeSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "AetherCore"

  it should "forward dependent results, write UART, and retire ebreak" in {
    val base = BigInt("80000000", 16)
    val program = Map[BigInt, BigInt](
      base + 0  -> BigInt("100002b7", 16),
      base + 4  -> BigInt("04100313", 16),
      base + 8  -> BigInt("00628023", 16),
      base + 12 -> BigInt("00700093", 16),
      base + 16 -> BigInt("00500113", 16),
      base + 20 -> BigInt("002081b3", 16),
      base + 24 -> BigInt("00100073", 16)
    )

    simulate(new AetherCoreSimTop) { dut =>
      dut.io.imemFault.poke(false.B)
      dut.io.memReady.poke(true.B)
      dut.io.memRdata.poke(0.U)
      dut.io.memFault.poke(false.B)

      var uart = ""
      var sawX3 = false
      var cycles = 0

      while (!dut.io.halted.peek().litToBoolean && cycles < 100) {
        val pc = dut.io.imemAddr.peek().litValue
        dut.io.imemInst.poke(program.getOrElse(pc, BigInt("00100073", 16)).U)

        if (dut.io.uartValid.peek().litToBoolean) {
          uart += dut.io.uartByte.peek().litValue.toChar
        }

        dut.clock.step()
        cycles += 1

        if (dut.io.commit.valid.peek().litToBoolean &&
            dut.io.commit.rdWrite.peek().litToBoolean &&
            dut.io.commit.rd.peek().litValue == 3) {
          dut.io.commit.rdData.expect(12.U)
          sawX3 = true
        }
      }

      uart shouldBe "A"
      sawX3 shouldBe true
      dut.io.halted.expect(true.B)
    }
  }
}
