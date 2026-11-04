package aethercore.core.product

import aethercore.common.FloatingCommitTrace
import chisel3._

/** One authority for the FP part of a Product architectural commit record.
  *
  * The ROB is the sole owner of the retire event. Neither an issue response
  * nor a memory completion may update a differential trace before this event.
  * This adapter deliberately has no queues, registers, or second retire port.
  */
object ProductFloatingRetirementTrace {
  def connect(trace: FloatingCommitTrace, retirement: ProductRetirement,
              retiring: Bool, fpCsrWrite: Bool): Unit = {
    val cleanRetirement = retiring && !retirement.exception.valid
    val writesFloating = cleanRetirement && retirement.wroteFloatingRegister
    trace.rdWrite := writesFloating
    trace.rd := Mux(writesFloating, retirement.rd, 0.U(5.W))
    trace.rdData := Mux(writesFloating, retirement.value, 0.U)
    // FP->XPR compare/convert may raise fflags without writing a float PRF.
    trace.fflags := Mux(cleanRetirement, retirement.fflags, 0.U(5.W))
    // FS also becomes Dirty for precise writes to fflags/frm/fcsr even though
    // such CSR instructions write only the XPR namespace (or rd=x0).
    trace.stateDirty := cleanRetirement && (retirement.fpDirty || fpCsrWrite)
  }
}
