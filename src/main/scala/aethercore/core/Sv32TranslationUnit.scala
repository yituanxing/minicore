package aethercore.core

import aethercore.config.PageTableGeometry

/**
  * Frozen Sv32 compatibility name over the shared translation-unit machinery.
  * Existing Sv32 production callers and tests intentionally keep this wrapper
  * so they remain executable regression oracles while Sv39/Sv48 reuse the same
  * composition state machine.
  *
  * 保留 Sv32 兼容入口；内部直接复用共享 TranslationUnit，使历史 Sv32 测试继续
  * 作为新实现的可执行回归基线。
  */
class Sv32TranslationUnit(tlbEntries: Int = 8)
    extends TranslationUnit(PageTableGeometry.Sv32, tlbEntries)
