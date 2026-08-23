package aethercore

/**
  * Test-scope mirror of the currently qualified tiny backend geometry.
  *
  * Production TinyRobGeometry intentionally remains private to core.v2 so ROB
  * size/generation width do not become a public generator surface merely for
  * tests. Source contracts and wrap regressions keep these fixed values aligned.
  */
private[aethercore] object TinyRobGeometry {
  val Entries: Int = 4
  val IndexBits: Int = 2
  val GenerationBits: Int = 8
}
