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

    board.plicSourceCount shouldBe AetherSoCBoardSpec.QualifiedPlicSourceCount
    board.plicSourceCount shouldBe 16
    board.uartPlicSourceId should be <= board.plicSourceCount
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
    board.uartClockFrequencyHz shouldBe 3686400L
    board.uartBaud shouldBe 115200
    board.uartDefaultDivisor shouldBe 2
    dts should include("bootargs = \"console=ttyS0 rdinit=/init\";")
    dts should not include "phandle = <1>;"
    dts should not include "phandle = <2>;"
  }

  it should "fit the qualified FPGA software map inside the implemented PA32 window" in {
    AetherSoCBoardSpec.FpgaImplementedPaddrBits shouldBe 32
    val map = board.addressMap
    val limit = BigInt(1) << AetherSoCBoardSpec.FpgaImplementedPaddrBits
    Seq(
      map.bootRomLimit,
      map.ramLimit,
      map.uartLimit,
      map.exitAddress + 8,
      map.mtimeAddress + 8,
      map.mtimecmpAddress + 8,
      map.plicLimit
    ).foreach(_ should be < limit)
  }

  it should "fail closed for a non-RV64 or non-Sv39 software profile" in {
    an[IllegalArgumentException] should be thrownBy {
      AetherSoCDts.render(board, isa = "rv32ima_zicsr", mmu = "sv39")
    }
    an[IllegalArgumentException] should be thrownBy {
      AetherSoCDts.render(board, isa = "rv64ima_zicsr", mmu = "sv48")
    }
  }

  it should "preserve legacy and RAM-only Product board selection without SPI nodes" in {
    for ((product, sourceCount) <- Seq(false -> 16, true -> 52)) {
      val selected = AetherSoCDtsBoardSelection.select(productBoard = product, spiSd = false)
      selected.plicSourceCount shouldBe sourceCount
      val text = AetherSoCDts.render(selected, enableSpiSd = false)
      text should include(s"riscv,ndev = <${sourceCount}>;")
      text should not include "mmc-spi-slot"
      text should not include "opencores,tiny-spi-rtlsvn2"
    }
  }

  it should "select Product PLIC 52 and the exact SPI clocks for SD media" in {
    val selected = AetherSoCDtsBoardSelection.select(
      productBoard = true,
      spiSd = true,
      spiClockHz = Some(48_000_000L),
      spiMaxHz = Some(12_000_000L)
    )
    selected.plicSourceCount shouldBe 52
    selected.spiBusClockFrequencyHz shouldBe 48_000_000L
    selected.spiMaxFrequencyHz shouldBe 12_000_000L
    val text = AetherSoCDts.render(selected, enableSpiSd = true)
    text should include("riscv,ndev = <52>;")
    text should include("compatible = \"opencores,tiny-spi-rtlsvn2\";")
    text should include("compatible = \"mmc-spi-slot\";")
    text should include("clock-frequency = <48000000>;")
    text should include("spi-max-frequency = <12000000>;")
  }

  it should "reject SPI media and SPI-clock options outside their owning board profile" in {
    val noProduct = intercept[IllegalArgumentException] {
      AetherSoCDtsBoardSelection.select(productBoard = false, spiSd = true)
    }
    noProduct.getMessage should include("--spi-sd requires --product")

    for (product <- Seq(false, true)) {
      val clockWithoutSpi = intercept[IllegalArgumentException] {
        AetherSoCDtsBoardSelection.select(
          productBoard = product, spiSd = false, spiClockHz = Some(50_000_000L))
      }
      clockWithoutSpi.getMessage should include("require --spi-sd")
      val maxWithoutSpi = intercept[IllegalArgumentException] {
        AetherSoCDtsBoardSelection.select(
          productBoard = product, spiSd = false, spiMaxHz = Some(12_500_000L))
      }
      maxWithoutSpi.getMessage should include("require --spi-sd")
    }
  }
}
