package aethercore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import aethercore.config.CoreProfiles
import aethercore.soc.{AetherSoCBoardSpec, AetherSoCDts}

class AetherSoCDtsSpec extends AnyFlatSpec with Matchers {
  behavior of "AetherSoCDts"

  private val board =
    AetherSoCBoardSpec.qualifiedLinux(CoreProfiles.rv64imasuSv39PmpSoftware.platform)

  it should "derive every board address and interrupt identity from the board spec" in {
    val dts = AetherSoCDts.render(
      board,
      isa = "rv64ima_zicsr_zifencei",
      mmu = "sv39",
      bootargs = Some("console=ttyS0 rdinit=/init")
    )
    val map = board.addressMap

    dts should include(s"bootrom@${map.bootRomBase.toString(16)}")
    dts should include(s"memory@${map.ramBase.toString(16)}")
    dts should include(s"interrupt-controller@${map.plicBase.toString(16)}")
    dts should include(s"serial@${map.uartBase.toString(16)}")
    dts should include(s"mtimer@${map.mtimeAddress.toString(16)}")

    dts should include(s"riscv,ndev = <${board.plicSourceCount}>;")
    dts should include(s"interrupts = <${board.uartPlicSourceId}>;")
    dts should include("cpu_intc: interrupt-controller {")
    dts should include(s"plic: interrupt-controller@${map.plicBase.toString(16)} {")
    dts should include(
      s"interrupts-extended = <&cpu_intc 0xffffffff &cpu_intc ${board.supervisorExternalInterruptId}>;"
    )
    dts should include("interrupt-parent = <&plic>;")
    dts should include(
      s"interrupts-extended = <&cpu_intc ${board.machineTimerInterruptId}>;"
    )
    dts should include(s"timebase-frequency = <${board.timebaseFrequencyHz}>;")
    dts should include(s"clock-frequency = <${board.uartClockFrequencyHz}>;")
    dts should include("bootargs = \"console=ttyS0 rdinit=/init\";")
    dts should not include "phandle = <1>;"
    dts should not include "phandle = <2>;"
  }

  it should "fail closed for a non-RV64 or non-Sv39 software profile" in {
    an[IllegalArgumentException] should be thrownBy {
      AetherSoCDts.render(board, isa = "rv32ima_zicsr", mmu = "sv39")
    }
    an[IllegalArgumentException] should be thrownBy {
      AetherSoCDts.render(board, isa = "rv64ima_zicsr", mmu = "sv48")
    }
  }
}
