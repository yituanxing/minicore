package aethercore.sim

import chisel3._
import chisel3.util.Cat
import aethercore.common._
import aethercore.config.{CoreConfig, CoreProfiles}
import aethercore.core.AetherCore

class AetherCoreSimTop(
    val config: CoreConfig = CoreProfiles.rv64imCurrent,
    val stopOnTrap: Boolean = true,
    val withMachineInterruptPlatform: Boolean = false,
    val stopOnWfi: Boolean = true,
    val withNs16550Uart: Boolean = false
) extends Module {
  private val xlen = config.isa.xlen
  private val paddrBits = config.platform.paddrBits
  private val busDataBits = config.platform.busDataBits
  private val busBytes = config.platform.busBytes
  private val plicBase = BigInt("0c000000", 16)
  private val plicLimit = plicBase + BigInt("00400000", 16)
  private val uartRxBase = config.platform.uartAddress + BigInt(0x100)
  private val uartRxLimit = uartRxBase + BigInt(0x10)
  private val ns16550Limit = config.platform.uartAddress + BigInt(8)

  if (withMachineInterruptPlatform) {
    require(busDataBits == 32,
      s"the first Machine interrupt platform requires a 32-bit data bus, got $busDataBits")
  }

  val io = IO(new Bundle {
    val imemAddr = Output(UInt(paddrBits.W))
    val imemInst = Input(UInt(32.W))
    val imemFault = Input(Bool())

    val memValid = Output(Bool())
    val memWrite = Output(Bool())
    val memAddr = Output(UInt(paddrBits.W))
    val memWdata = Output(UInt(busDataBits.W))
    val memWmask = Output(UInt(busBytes.W))
    val memSize = Output(MemSize())
    val memReady = Input(Bool())
    val memRdata = Input(UInt(busDataBits.W))
    val memFault = Input(Bool())

    // Sv32 page-table walks are exposed as a second physical read port in the
    // simulation shell. This intentionally stays simple: runners may service
    // PTW reads from the same backing memory as instruction/data accesses.
    val ptwValid = if (config.isa.hasSv32) Some(Output(Bool())) else None
    val ptwAddr = if (config.isa.hasSv32) Some(Output(UInt(paddrBits.W))) else None
    val ptwReady = if (config.isa.hasSv32) Some(Input(Bool())) else None
    val ptwRdata = if (config.isa.hasSv32) Some(Input(UInt(32.W))) else None
    val ptwFault = if (config.isa.hasSv32) Some(Input(Bool())) else None

    val uartValid = Output(Bool())
    val uartByte = Output(UInt(8.W))
    val rxValid = if (withMachineInterruptPlatform) Some(Input(Bool())) else None
    val rxByte = if (withMachineInterruptPlatform) Some(Input(UInt(8.W))) else None
    val rxReady = if (withMachineInterruptPlatform) Some(Output(Bool())) else None
    val externalInterrupt =
      if (withMachineInterruptPlatform) Some(Output(Bool())) else None
    val exitValid = Output(Bool())
    val exitCode = Output(UInt(xlen.W))

    val mtime = Output(UInt(64.W))
    val mtimecmp = Output(UInt(64.W))
    val timerInterrupt = Output(Bool())

    val commit = Output(new CommitTrace(xlen, paddrBits, busDataBits))
    val halted = Output(Bool())
  })

  def mergeBytes(oldValue: UInt, newValue: UInt, mask: UInt, bytes: Int): UInt = {
    Cat((0 until bytes).reverse.map { byte =>
      val high = byte * 8 + 7
      val low = byte * 8
      Mux(mask(byte), newValue(high, low), oldValue(high, low))
    })
  }

  val core = Module(new AetherCore(config, withMachineExternalInterrupt = withMachineInterruptPlatform))
  val interruptPlatform =
    if (withMachineInterruptPlatform) {
      Some(Module(new MachineInterruptPlatform(addressBits = paddrBits, uartBase = uartRxBase)))
    } else None

  val uartAddress = config.platform.uartAddress.U(paddrBits.W)
  val exitAddress = config.platform.exitAddress.U(paddrBits.W)
  val mtimeAddress = config.platform.mtimeAddress.U(paddrBits.W)
  val mtimecmpAddress = config.platform.mtimecmpAddress.U(paddrBits.W)

  val mtime = RegInit(0.U(64.W))
  val mtimecmp = RegInit("hffffffffffffffff".U(64.W))

  val uartLcr = if (withNs16550Uart) Some(RegInit(0.U(8.W))) else None
  val uartIer = if (withNs16550Uart) Some(RegInit(0.U(8.W))) else None
  val uartDll = if (withNs16550Uart) Some(RegInit(0.U(8.W))) else None
  val uartDlm = if (withNs16550Uart) Some(RegInit(0.U(8.W))) else None
  val uartMcr = if (withNs16550Uart) Some(RegInit(0.U(8.W))) else None
  val uartScr = if (withNs16550Uart) Some(RegInit(0.U(8.W))) else None

  core.io.imem.inst := io.imemInst
  core.io.imem.fault := io.imemFault
  io.imemAddr := core.io.imem.addr

  if (config.isa.hasSv32) {
    io.ptwValid.get := core.io.ptw.get.valid
    io.ptwAddr.get := core.io.ptw.get.addr
    core.io.ptw.get.ready := io.ptwReady.get
    core.io.ptw.get.rdata := io.ptwRdata.get
    core.io.ptw.get.fault := io.ptwFault.get
  }

  val isWrite = core.io.dmem.valid && core.io.dmem.write
  val isNs16550Address = if (withNs16550Uart) {
    core.io.dmem.valid && core.io.dmem.addr >= uartAddress &&
      core.io.dmem.addr < ns16550Limit.U(paddrBits.W)
  } else false.B
  val uartOffset = core.io.dmem.addr - uartAddress
  val uartDlab = if (withNs16550Uart) uartLcr.get(7) else false.B
  val isUartTx = if (withNs16550Uart) {
    isWrite && core.io.dmem.addr === uartAddress && !uartDlab
  } else {
    isWrite && core.io.dmem.addr === uartAddress
  }
  val isUartMmio = if (withNs16550Uart) isNs16550Address else isUartTx
  val isExit = isWrite && core.io.dmem.addr === exitAddress

  if (withNs16550Uart) {
    when(isNs16550Address && core.io.dmem.write) {
      switch(uartOffset(2, 0)) {
        is(0.U) {
          when(uartLcr.get(7)) { uartDll.get := core.io.dmem.wdata(7, 0) }
        }
        is(1.U) {
          when(uartLcr.get(7)) {
            uartDlm.get := core.io.dmem.wdata(7, 0)
          }.otherwise {
            uartIer.get := core.io.dmem.wdata(7, 0)
          }
        }
        is(3.U) { uartLcr.get := core.io.dmem.wdata(7, 0) }
        is(4.U) { uartMcr.get := core.io.dmem.wdata(7, 0) }
        is(7.U) { uartScr.get := core.io.dmem.wdata(7, 0) }
      }
    }
  }

  val uartReadData = WireDefault(0.U(busDataBits.W))
  if (withNs16550Uart) {
    switch(uartOffset(2, 0)) {
      is(0.U) {
        uartReadData := Mux(uartLcr.get(7), uartDll.get, 0.U(8.W)).pad(busDataBits)
      }
      is(1.U) {
        uartReadData := Mux(uartLcr.get(7), uartDlm.get, uartIer.get).pad(busDataBits)
      }
      // IIR bit 0=1 means no interrupt is pending. FIFO state is deliberately
      // omitted until the real workload requires it.
      is(2.U) { uartReadData := 1.U(busDataBits.W) }
      is(3.U) { uartReadData := uartLcr.get.pad(busDataBits) }
      is(4.U) { uartReadData := uartMcr.get.pad(busDataBits) }
      // LSR: transmitter holding register empty + transmitter empty. No RX byte.
      is(5.U) { uartReadData := "h60".U(busDataBits.W) }
      is(7.U) { uartReadData := uartScr.get.pad(busDataBits) }
    }
  }

  val isPlicAddress = if (withMachineInterruptPlatform) {
    core.io.dmem.valid && core.io.dmem.addr >= plicBase.U(paddrBits.W) &&
      core.io.dmem.addr < plicLimit.U(paddrBits.W)
  } else false.B
  val isUartRxAddress = if (withMachineInterruptPlatform) {
    core.io.dmem.valid && core.io.dmem.addr >= uartRxBase.U(paddrBits.W) &&
      core.io.dmem.addr < uartRxLimit.U(paddrBits.W)
  } else false.B
  val isInterruptMmio = isPlicAddress || isUartRxAddress

  if (withMachineInterruptPlatform) {
    val platform = interruptPlatform.get
    platform.io.rxValid := io.rxValid.get
    platform.io.rxByte := io.rxByte.get
    io.rxReady.get := platform.io.rxReady

    platform.io.request := isInterruptMmio
    platform.io.write := core.io.dmem.write
    platform.io.address := core.io.dmem.addr
    platform.io.wdata := core.io.dmem.wdata(31, 0)
    platform.io.wmask := core.io.dmem.wmask(3, 0)

    core.io.externalInterrupt.get := platform.io.externalInterrupt
    io.externalInterrupt.get := platform.io.externalInterrupt
  }

  val isMtimeLow = core.io.dmem.addr === mtimeAddress
  val isMtimeHigh = core.io.dmem.addr === (config.platform.mtimeAddress + 4).U(paddrBits.W)
  val isMtimecmpLow = core.io.dmem.addr === mtimecmpAddress
  val isMtimecmpHigh = core.io.dmem.addr === (config.platform.mtimecmpAddress + 4).U(paddrBits.W)
  val isTimerAddress = if (busDataBits == 32) {
    isMtimeLow || isMtimeHigh || isMtimecmpLow || isMtimecmpHigh
  } else {
    isMtimeLow || isMtimecmpLow
  }
  val isTimer = core.io.dmem.valid && isTimerAddress
  val isMmio = isUartMmio || isExit || isTimer || isInterruptMmio

  val timerReadData = WireDefault(0.U(busDataBits.W))
  if (busDataBits == 32) {
    when(isMtimeLow) { timerReadData := mtime(31, 0) }
    when(isMtimeHigh) { timerReadData := mtime(63, 32) }
    when(isMtimecmpLow) { timerReadData := mtimecmp(31, 0) }
    when(isMtimecmpHigh) { timerReadData := mtimecmp(63, 32) }
  } else {
    when(isMtimeLow) { timerReadData := mtime }
    when(isMtimecmpLow) { timerReadData := mtimecmp }
  }

  val nextMtime = WireDefault(mtime + 1.U)
  val nextMtimecmp = WireDefault(mtimecmp)
  when(isTimer && core.io.dmem.write) {
    if (busDataBits == 32) {
      when(isMtimeLow) {
        nextMtime := Cat(mtime(63, 32), mergeBytes(mtime(31, 0), core.io.dmem.wdata, core.io.dmem.wmask, 4))
      }
      when(isMtimeHigh) {
        nextMtime := Cat(mergeBytes(mtime(63, 32), core.io.dmem.wdata, core.io.dmem.wmask, 4), mtime(31, 0))
      }
      when(isMtimecmpLow) {
        nextMtimecmp := Cat(mtimecmp(63, 32), mergeBytes(mtimecmp(31, 0), core.io.dmem.wdata, core.io.dmem.wmask, 4))
      }
      when(isMtimecmpHigh) {
        nextMtimecmp := Cat(mergeBytes(mtimecmp(63, 32), core.io.dmem.wdata, core.io.dmem.wmask, 4), mtimecmp(31, 0))
      }
    } else {
      when(isMtimeLow) {
        nextMtime := mergeBytes(mtime, core.io.dmem.wdata, core.io.dmem.wmask, 8)
      }
      when(isMtimecmpLow) {
        nextMtimecmp := mergeBytes(mtimecmp, core.io.dmem.wdata, core.io.dmem.wmask, 8)
      }
    }
  }
  mtime := nextMtime
  mtimecmp := nextMtimecmp

  val timerInterrupt = mtime >= mtimecmp
  core.io.timerInterrupt := timerInterrupt

  io.memValid := core.io.dmem.valid && !isMmio
  io.memWrite := core.io.dmem.write
  io.memAddr := core.io.dmem.addr
  io.memWdata := core.io.dmem.wdata
  io.memWmask := core.io.dmem.wmask
  io.memSize := core.io.dmem.size

  val interruptReady =
    if (withMachineInterruptPlatform) interruptPlatform.get.io.ready else false.B
  val interruptReadData =
    if (withMachineInterruptPlatform) interruptPlatform.get.io.rdata else 0.U(32.W)
  val interruptFault =
    if (withMachineInterruptPlatform) interruptPlatform.get.io.fault else false.B

  core.io.dmem.ready := Mux(
    isInterruptMmio,
    interruptReady,
    Mux(isMmio, true.B, io.memReady)
  )
  core.io.dmem.rdata := Mux(
    isInterruptMmio,
    interruptReadData.pad(busDataBits),
    Mux(isTimer, timerReadData, Mux(isUartMmio, uartReadData, io.memRdata))
  )
  core.io.dmem.fault := Mux(
    isInterruptMmio,
    interruptFault,
    Mux(isMmio, false.B, io.memFault)
  )

  io.uartValid := isUartTx
  io.uartByte := core.io.dmem.wdata(7, 0)
  io.exitValid := isExit
  io.exitCode := core.io.dmem.wdata

  io.mtime := mtime
  io.mtimecmp := mtimecmp
  io.timerInterrupt := timerInterrupt

  val observedTrap = RegInit(false.B)
  when(core.io.commit.valid && (core.io.commit.exception || core.io.commit.interrupt)) {
    observedTrap := true.B
  }

  val wfiHalted = if (stopOnWfi) core.io.halted else false.B
  io.commit := core.io.commit
  io.halted := wfiHalted || (if (stopOnTrap) observedTrap else false.B)
}
