package aethercore.core

import chisel3._

/**
  * Explicit boundary between architectural addresses and the platform PA bus.
  *
  * AetherCore currently supports architectural XLENs up to 64 bits. The helper
  * first binds every source into a known 64-bit architectural envelope instead
  * of asking Chisel to infer the source width at this call site. This matters
  * for routing wires such as the fetch virtual address whose width may still be
  * unresolved during Scala elaboration even though the architectural XLEN is
  * already bounded by the core profile.
  *
  * When PA width is at least the architectural envelope, the address is
  * zero-extended and can never be out of range. When PA width is narrower, the
  * low PA bits are returned together with an out-of-range flag for any discarded
  * architectural high bit. Callers must treat outOfRange as an access fault and
  * suppress the physical request; the low bits are never permission to alias a
  * wider address.
  *
  * 架构地址与平台物理地址总线之间的显式边界。当前 AetherCore 的架构地址最多 64 位；
  * 这里先把输入绑定到已知 64 位的架构地址包络，避免依赖 Chisel 在调用点推导 Wire 宽度。
  * PA 较窄时返回低位地址与越界标志；调用者必须在越界时产生 access fault 并抑制总线访问，
  * 禁止静默回绕。
  */
object PhysicalAddressNarrowing {
  private val ArchitecturalAddressBits = 64

  def apply(address: UInt, paddrBits: Int): (UInt, Bool) = {
    require(paddrBits > 0, s"physical address width must be positive, got $paddrBits")

    val envelopeBits = math.max(ArchitecturalAddressBits, paddrBits)
    val architecturalAddress = Wire(UInt(envelopeBits.W))
    architecturalAddress := address

    if (paddrBits >= ArchitecturalAddressBits) {
      (architecturalAddress, false.B)
    } else {
      val physicalAddress = architecturalAddress(paddrBits - 1, 0)
      val outOfRange = architecturalAddress(ArchitecturalAddressBits - 1, paddrBits).orR
      (physicalAddress, outOfRange)
    }
  }
}
