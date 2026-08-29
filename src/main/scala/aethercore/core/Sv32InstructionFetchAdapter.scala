package aethercore.core

import aethercore.config.PageTableGeometry

/**
  * Frozen Sv32 compatibility surface over the shared instruction adapter.
  * Existing production callers keep their historical class name while directly
  * exercising the geometry-driven implementation.
  */
class Sv32InstructionFetchAdapter(paddrBits: Int = 34)
    extends InstructionFetchAdapter(PageTableGeometry.Sv32, paddrBits)
