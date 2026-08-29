package aethercore.core

import aethercore.config.PageTableGeometry

/** Frozen Sv32 compatibility surface over the shared PTW arbiter. */
class Sv32PtwArbiter(paddrBits: Int = 34)
    extends PtwArbiter(PageTableGeometry.Sv32, paddrBits)
