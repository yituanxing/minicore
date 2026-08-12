package aethercore.core

/**
  * Physical-address geometry shared by PMP CSR storage and address matching.
  *
  * XLEN determines the architectural CSR width. Platform physical-address
  * width is independent and may be wider than XLEN. Standard PMP address
  * registers encode physical bits through PA33 (RV32) or PA55 (RV64), while
  * NAPOT low-order-one size encoding can describe a range whose upper bound is
  * one bit wider. Any such range is finally bounded by the platform PA width.
  */
final case class PmpGeometry(xlen: Int, paddrBits: Int) {
  require(xlen == 32 || xlen == 64, s"PMP requires XLEN 32 or 64, got $xlen")
  require(paddrBits >= 3 && paddrBits <= 64, s"platform PA width must be 3..64, got $paddrBits")

  val architecturalPmpPhysicalBits: Int = if (xlen == 32) 34 else 56
  val implementedPmpPhysicalBits: Int = math.min(paddrBits, architecturalPmpPhysicalBits)
  val encodedAddressBits: Int = implementedPmpPhysicalBits - 2
  val encodedAddressMask: BigInt = (BigInt(1) << encodedAddressBits) - 1

  // With every implemented pmpaddr bit set, the first zero used by NAPOT's
  // trailing-one size encoding is the next unimplemented/WARL-zero bit. Thus
  // the nominal range size is 2^(encodedAddressBits+3), clipped only by the
  // surrounding platform's physical-address domain.
  val allOnesNapotUpper: BigInt =
    BigInt(1) << math.min(paddrBits, encodedAddressBits + 3)
}
