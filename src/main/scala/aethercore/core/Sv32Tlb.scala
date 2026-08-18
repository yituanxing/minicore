package aethercore.core

import aethercore.config.PageTableGeometry

/**
  * Frozen Sv32 compatibility surface over the shared translation TLB.
  *
  * Existing Sv32 tests and the production translation unit keep their original
  * source interface while exercising the same geometry-driven cache that will
  * later host Sv39 and Sv48.
  *
  * Sv32 兼容薄封装：接口不变，TLB 的 tag/refill/superpage 逻辑改由共享实现承担。
  */
class Sv32Tlb(entries: Int = 8) extends TranslationTlb(PageTableGeometry.Sv32, entries)
