package aethercore.core

import aethercore.config.PageTableGeometry

/** Frozen Sv32 compatibility surface over the shared data-path adapter. */
class Sv32DataPathAdapter(paddrBits: Int = 34)
    extends DataPathAdapter(PageTableGeometry.Sv32, paddrBits)
