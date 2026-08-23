package aethercore

/**
  * Test-scope mirror of the active ROB8 experiment geometry.
  *
  * Production TinyRobGeometry intentionally remains private to core.v2 so ROB
  * size/generation width do not become a public generator surface merely for
  * tests. The isolated P8 ROB8 branch keeps this mirror aligned with production
  * while the geometry is under evaluation.
  */
private[aethercore] object TinyRobGeometry {
  val Entries: Int = 8
  val IndexBits: Int = 3
  val GenerationBits: Int = 8
}
