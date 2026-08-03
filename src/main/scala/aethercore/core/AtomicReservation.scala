package aethercore.core

import chisel3._

/** Single-hart LR/SC reservation state.
  *
  * The current in-order core has no cache, DMA master, or second hart. A
  * reservation is therefore represented by the exact physical address and
  * access width established by a completed LR. Any local store attempt,
  * explicit architectural flush, or SC attempt invalidates the reservation.
  * Clearing on every local store is deliberately conservative: RISC-V permits
  * SC to fail spuriously, while retaining a reservation across an intervening
  * local store would be incorrect.
  */
class AtomicReservation(val addressBits: Int) extends Module {
  require(addressBits > 0, s"reservation address width must be positive, got $addressBits")

  val io = IO(new Bundle {
    val lrComplete = Input(Bool())
    val lrAddress = Input(UInt(addressBits.W))
    val lrBytes = Input(UInt(4.W))

    val scAttempt = Input(Bool())
    val scAddress = Input(UInt(addressBits.W))
    val scBytes = Input(UInt(4.W))
    val scSuccess = Output(Bool())

    val localStoreAttempt = Input(Bool())
    val clear = Input(Bool())

    val valid = Output(Bool())
    val address = Output(UInt(addressBits.W))
    val bytes = Output(UInt(4.W))
  })

  val valid = RegInit(false.B)
  val address = RegInit(0.U(addressBits.W))
  val bytes = RegInit(0.U(4.W))

  io.scSuccess := io.scAttempt && valid &&
    io.scAddress === address && io.scBytes === bytes

  io.valid := valid
  io.address := address
  io.bytes := bytes

  when(io.clear || io.localStoreAttempt || io.scAttempt) {
    valid := false.B
  }.elsewhen(io.lrComplete) {
    valid := true.B
    address := io.lrAddress
    bytes := io.lrBytes
  }
}
