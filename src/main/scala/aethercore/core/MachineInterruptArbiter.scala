package aethercore.core

import chisel3._

/** Machine-level interrupt arbitration shared by CSR and WFI integration.
  *
  * The module deliberately separates raw pending state from architectural trap
  * qualification. A raw timer or external request is sufficient to wake WFI,
  * while trap entry still requires the corresponding mie bit and the global
  * machine-interrupt enable rule. Machine external interrupt has priority over
  * machine timer interrupt, matching the privileged interrupt priority order.
  */
class MachineInterruptArbiter(val xlen: Int) extends Module {
  require(xlen == 32 || xlen == 64, s"unsupported XLEN=$xlen")

  private val machineTimerBit = 7
  private val machineExternalBit = 11
  private val interruptFlag = BigInt(1) << (xlen - 1)

  val io = IO(new Bundle {
    val rawTimerPending = Input(Bool())
    val rawExternalPending = Input(Bool())
    val mie = Input(UInt(xlen.W))
    val mstatusMie = Input(Bool())
    val currentPrivilege = Input(UInt(2.W))

    val wakeRequest = Output(Bool())
    val takeInterrupt = Output(Bool())
    val cause = Output(UInt(xlen.W))
    val timerQualified = Output(Bool())
    val externalQualified = Output(Bool())
    val mip = Output(UInt(xlen.W))
  })

  val globallyEnabled = io.currentPrivilege < 3.U || io.mstatusMie
  val timerQualified = io.rawTimerPending && io.mie(machineTimerBit) && globallyEnabled
  val externalQualified = io.rawExternalPending && io.mie(machineExternalBit) && globallyEnabled

  io.wakeRequest := io.rawTimerPending || io.rawExternalPending
  io.timerQualified := timerQualified
  io.externalQualified := externalQualified
  io.takeInterrupt := externalQualified || timerQualified
  io.cause := Mux(
    externalQualified,
    (interruptFlag | BigInt(machineExternalBit)).U(xlen.W),
    (interruptFlag | BigInt(machineTimerBit)).U(xlen.W)
  )
  io.mip :=
    (io.rawTimerPending.asUInt << machineTimerBit) |
      (io.rawExternalPending.asUInt << machineExternalBit)
}
