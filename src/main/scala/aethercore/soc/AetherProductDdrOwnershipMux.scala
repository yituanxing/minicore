package aethercore.soc

import chisel3._
import chisel3.util._

/** Exclusive, five-channel AXI4 DDR ownership for a cold-start image loader.
  *
  * The loader first writes the complete OpenSBI fw_payload into calibrated
  * external DDR. Only after all AW, W, B, AR and R handshakes have drained
  * may the existing cold-boot controller release the Product CPU. Selection
  * never changes with traffic outstanding, so IDs need not be rewritten.
  *
  * Both masters use single-beat AXI. They can issue AW and W independently;
  * tracking a W acceptance alone is *not* sufficient to release the loader:
  * its B response must also have completed. A read is drained only at R.last.
  * No DDR PHY or loader implementation is fabricated by this block.
  */
class AetherProductDdrOwnershipMux(
    val addrBits: Int,
    val dataBits: Int,
    val txnIdBits: Int,
    val outstandingBits: Int = 16,
    val allowedRamBase: BigInt = BigInt("80000000", 16),
    val allowedRamBytes: BigInt = BigInt("10000000", 16)
) extends Module {
  require(addrBits >= 3 && dataBits == 64 && txnIdBits > 0)
  require(outstandingBits >= 2)
  require(allowedRamBase >= 0 && allowedRamBytes > 0)
  require(allowedRamBase + allowedRamBytes < (BigInt(1) << addrBits),
    "loader's DDR RAM window must fit the physical address interface")

  val io = IO(new Bundle {
    val loaderOwnsDdr = Input(Bool())
    val cpuOwnsDdr = Input(Bool())
    val loader = Flipped(new Axi4MasterIO(addrBits, dataBits, txnIdBits))
    val cpu = Flipped(new Axi4MasterIO(addrBits, dataBits, txnIdBits))
    val ddr = new Axi4MasterIO(addrBits, dataBits, txnIdBits)
    val loaderDrained = Output(Bool())
    val loaderAddressFault = Output(Bool())
  })

  val anyOwner = io.loaderOwnsDdr || io.cpuOwnsDdr
  assert(!(io.loaderOwnsDdr && io.cpuOwnsDdr),
    "DDR may have either loader or Product CPU as owner, never both")

  // Do not let a mistaken loader address wrap around the vendor DDR IP or
  // overwrite an MMIO region. This is a hard gate, not merely a simulation
  // assertion. A rejected transaction raises a sticky fault to the cold-boot
  // controller; neither side can claim the memory again without board reset.
  private val loaderAddressFault = RegInit(false.B)
  private val allowedLimit = (allowedRamBase + allowedRamBytes).U((addrBits + 1).W)
  private val allowedStart = allowedRamBase.U((addrBits + 1).W)
  private val maxAxiSize = log2Ceil(dataBits / 8)
  private def validLoaderAddress(address: Axi4Address): Bool = {
    val extendedAddress = Cat(0.U(1.W), address.addr)
    val transferBytes = 1.U((addrBits + 1).W) << address.size
    val validSize = address.size <= maxAxiSize.U
    val aligned = (address.addr(2, 0) &
      ((1.U(3.W) << address.size)(2, 0) - 1.U)) === 0.U
    address.len === 0.U && validSize && aligned &&
      extendedAddress >= allowedStart &&
      extendedAddress + transferBytes <= allowedLimit
  }
  private val loaderAwAllowed = validLoaderAddress(io.loader.aw.bits)
  private val loaderArAllowed = validLoaderAddress(io.loader.ar.bits)
  private val loaderWAllowed = io.loader.w.bits.last
  when(io.loaderOwnsDdr &&
    ((io.loader.aw.valid && !loaderAwAllowed) ||
      (io.loader.ar.valid && !loaderArAllowed) ||
      (io.loader.w.valid && !loaderWAllowed))) {
    loaderAddressFault := true.B
  }
  io.loaderAddressFault := loaderAddressFault

  io.ddr.aw.valid := (io.loaderOwnsDdr && io.loader.aw.valid &&
    loaderAwAllowed && !loaderAddressFault) ||
    (io.cpuOwnsDdr && io.cpu.aw.valid)
  io.ddr.aw.bits := Mux(io.loaderOwnsDdr, io.loader.aw.bits, io.cpu.aw.bits)
  io.loader.aw.ready := io.loaderOwnsDdr && loaderAwAllowed &&
    !loaderAddressFault && io.ddr.aw.ready
  io.cpu.aw.ready := io.cpuOwnsDdr && io.ddr.aw.ready

  io.ddr.w.valid := (io.loaderOwnsDdr && io.loader.w.valid &&
    loaderWAllowed && !loaderAddressFault) ||
    (io.cpuOwnsDdr && io.cpu.w.valid)
  io.ddr.w.bits := Mux(io.loaderOwnsDdr, io.loader.w.bits, io.cpu.w.bits)
  io.loader.w.ready := io.loaderOwnsDdr && loaderWAllowed &&
    !loaderAddressFault && io.ddr.w.ready
  io.cpu.w.ready := io.cpuOwnsDdr && io.ddr.w.ready

  io.loader.b.valid := io.loaderOwnsDdr && io.ddr.b.valid
  io.loader.b.bits := io.ddr.b.bits
  io.cpu.b.valid := io.cpuOwnsDdr && io.ddr.b.valid
  io.cpu.b.bits := io.ddr.b.bits
  io.ddr.b.ready := (io.loaderOwnsDdr && io.loader.b.ready) ||
    (io.cpuOwnsDdr && io.cpu.b.ready)

  io.ddr.ar.valid := (io.loaderOwnsDdr && io.loader.ar.valid &&
    loaderArAllowed && !loaderAddressFault) ||
    (io.cpuOwnsDdr && io.cpu.ar.valid)
  io.ddr.ar.bits := Mux(io.loaderOwnsDdr, io.loader.ar.bits, io.cpu.ar.bits)
  io.loader.ar.ready := io.loaderOwnsDdr && loaderArAllowed &&
    !loaderAddressFault && io.ddr.ar.ready
  io.cpu.ar.ready := io.cpuOwnsDdr && io.ddr.ar.ready

  io.loader.r.valid := io.loaderOwnsDdr && io.ddr.r.valid
  io.loader.r.bits := io.ddr.r.bits
  io.cpu.r.valid := io.cpuOwnsDdr && io.ddr.r.valid
  io.cpu.r.bits := io.ddr.r.bits
  io.ddr.r.ready := (io.loaderOwnsDdr && io.loader.r.ready) ||
    (io.cpuOwnsDdr && io.cpu.r.ready)

  // Count accepted loader channels separately. AXI permits AW and W to be
  // accepted in either order; only B retires both. Likewise R.last retires
  // one accepted AR. No ledger is needed for CPU->loader re-entry because the
  // cold-boot controller makes post-release faults sticky until board reset.
  private val writeAddresses = RegInit(0.U(outstandingBits.W))
  private val writeBeats = RegInit(0.U(outstandingBits.W))
  private val readAddresses = RegInit(0.U(outstandingBits.W))

  private val awFire = io.loader.aw.fire
  private val wFire = io.loader.w.fire
  private val bFire = io.loader.b.fire
  private val arFire = io.loader.ar.fire
  private val rFire = io.loader.r.fire

  when(awFire) {
    assert(io.loader.aw.bits.len === 0.U, "loader AXI writes must be single-beat")
  }
  when(arFire) {
    assert(io.loader.ar.bits.len === 0.U, "loader AXI reads must be single-beat")
  }
  when(wFire) {
    assert(io.loader.w.bits.last, "loader AXI W must terminate in a single beat")
  }
  when(rFire) {
    assert(io.loader.r.bits.last, "loader AXI R must terminate in a single beat")
  }
  when(bFire) {
    assert(writeAddresses =/= 0.U || awFire,
      "loader DDR write response had no accepted AW")
    assert(writeBeats =/= 0.U || wFire,
      "loader DDR write response had no accepted W")
  }
  when(rFire) {
    assert(readAddresses =/= 0.U || arFire,
      "loader DDR read response had no accepted AR")
  }

  when(awFire =/= bFire) {
    writeAddresses := Mux(awFire, writeAddresses + 1.U, writeAddresses - 1.U)
  }
  when(wFire =/= bFire) {
    writeBeats := Mux(wFire, writeBeats + 1.U, writeBeats - 1.U)
  }
  when(arFire =/= rFire) {
    readAddresses := Mux(arFire, readAddresses + 1.U, readAddresses - 1.U)
  }

  when(awFire && !bFire) { assert(!writeAddresses.andR, "loader AW ledger overflow") }
  when(wFire && !bFire) { assert(!writeBeats.andR, "loader W ledger overflow") }
  when(arFire && !rFire) { assert(!readAddresses.andR, "loader AR ledger overflow") }

  // The external loader's idle hint is used by the controller *in addition*
  // to this hardware-derived drain. It is never accepted by itself as proof.
  io.loaderDrained := writeAddresses === 0.U && writeBeats === 0.U &&
    readAddresses === 0.U && !io.loader.aw.valid && !io.loader.w.valid &&
    !io.loader.ar.valid

  when(!anyOwner) {
    assert(!io.ddr.aw.valid && !io.ddr.w.valid && !io.ddr.ar.valid,
      "no DDR owner may issue any AXI request")
    assert(!io.ddr.b.ready && !io.ddr.r.ready,
      "no DDR owner may accept any AXI response")
  }
}
