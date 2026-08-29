package aethercore.soc

import chisel3._
import chisel3.util._

object Axi4Resp {
  val Okay = 0.U(2.W)
  val ExOkay = 1.U(2.W)
  val SlvErr = 2.U(2.W)
  val DecErr = 3.U(2.W)
}

object Axi4Burst {
  val Fixed = 0.U(2.W)
  val Incr = 1.U(2.W)
  val Wrap = 2.U(2.W)
}

class Axi4Address(
    val addrBits: Int,
    val idBits: Int
) extends Bundle {
  val id = UInt(idBits.W)
  val addr = UInt(addrBits.W)
  val len = UInt(8.W)
  val size = UInt(3.W)
  val burst = UInt(2.W)
  val lock = Bool()
  val cache = UInt(4.W)
  val prot = UInt(3.W)
  val qos = UInt(4.W)
}

class Axi4WriteData(val dataBits: Int) extends Bundle {
  val data = UInt(dataBits.W)
  val strb = UInt((dataBits / 8).W)
  val last = Bool()
}

class Axi4WriteResponse(val idBits: Int) extends Bundle {
  val id = UInt(idBits.W)
  val resp = UInt(2.W)
}

class Axi4ReadData(
    val dataBits: Int,
    val idBits: Int
) extends Bundle {
  val id = UInt(idBits.W)
  val data = UInt(dataBits.W)
  val resp = UInt(2.W)
  val last = Bool()
}

/**
  * Minimal full AXI4 master channel set used by AetherSoC.
  *
  * The first bridge emits only single-beat transactions (len = 0). Keeping the
  * standard five independent AXI channels here lets later FPGA wrappers connect
  * directly to vendor AXI interconnect/DDR IP without leaking AXI into the CPU
  * or AetherMem semantic boundary.
  */
class Axi4MasterIO(
    val addrBits: Int,
    val dataBits: Int,
    val idBits: Int
) extends Bundle {
  val aw = Decoupled(new Axi4Address(addrBits, idBits))
  val w = Decoupled(new Axi4WriteData(dataBits))
  val b = Flipped(Decoupled(new Axi4WriteResponse(idBits)))
  val ar = Decoupled(new Axi4Address(addrBits, idBits))
  val r = Flipped(Decoupled(new Axi4ReadData(dataBits, idBits)))
}
