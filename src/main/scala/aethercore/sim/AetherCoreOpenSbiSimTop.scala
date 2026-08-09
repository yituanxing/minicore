package aethercore.sim

import chisel3._
import aethercore.config.CoreProfiles

/** First L32 execution shell for pinned RV32 OpenSBI v1.6.
  *
  * Keep the platform intentionally small. The embedded FDT describes only the
  * RAM, NS16550 console and ACLINT MTIMER already present in the frozen N5
  * platform. Add PLIC/MSIP or Linux-only devices only when real execution
  * demonstrates the requirement.
  *
  * The Linux timer debug port is observation-only. It exposes the already
  * implemented Sstc state so a long Linux run can distinguish comparator,
  * pending, interrupt-qualification and privilege problems without changing
  * architectural behavior.
  */
class AetherCoreOpenSbiSimTop
    extends AetherCoreSimTop(
      config = CoreProfiles.rv32imasuSv32Software,
      stopOnTrap = false,
      withMachineInterruptPlatform = false,
      stopOnWfi = false,
      withNs16550Uart = true
    ) {
  val linuxTimerDebug = IO(new Bundle {
    val privilege = Output(UInt(2.W))
    val stimecmp = Output(UInt(64.W))
    val supervisorTimerPending = Output(Bool())
    val supervisorTimerInterrupt = Output(Bool())
  })

  linuxTimerDebug.privilege := core.csrFile.io.currentPrivilege
  linuxTimerDebug.stimecmp := core.csrFile.sstc.get.io.compare
  linuxTimerDebug.supervisorTimerPending := core.csrFile.io.supervisorTimerPending.get
  linuxTimerDebug.supervisorTimerInterrupt := core.csrFile.io.supervisorTimerInterrupt.get
}
