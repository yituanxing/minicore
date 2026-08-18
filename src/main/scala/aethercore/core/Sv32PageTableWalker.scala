package aethercore.core

import aethercore.config.PageTableGeometry

/**
  * Frozen Sv32 compatibility surface over the shared page-table walker.
  *
  * Keeping the historical class name means the existing Sv32 focused tests,
  * NuttX paging workload and production translation unit become executable
  * regression oracles for the geometry-driven implementation.
  *
  * Sv32 兼容薄封装：现有冻结测试继续使用原接口，但实际遍历逻辑由共享 walker 承担。
  */
class Sv32PageTableWalker extends PageTableWalker(PageTableGeometry.Sv32)
