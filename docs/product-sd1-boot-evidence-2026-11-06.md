# Product SD1 RTL Debian 启动留存台账（2026-11-06）

> 本文仅记录已经核查的真实证据。**QEMU 官方完整 Debian 启动通过 ≠ Product RTL 已启动完整 Debian。** 冻结 RTL SHA `a15197f96e5869e69269202a0a9caffdbe1f5255`，兼容性指纹 `90f67471f71c95bc921427afa46cc5f4cf57d164a58a41cf5d46ca96a06be189`；**这些结果不能替代最新 PR HEAD RTL 的验收**。

## 验证边界

- [官方原生 Debian QEMU 预检 #42207139595](https://github.com/yituanxing/minicore/actions/runs/42207139595) PASS：完整签名官方 Debian SD1 镜像、ext4 根文件系统、`/sbin/init`、登录提示（**未认证登录**）。这是 QEMU，不是 RTL。
- [SD1 RTL #42210893404](https://github.com/yituanxing/minicore/actions/runs/42210893404) → [#42214303482](https://github.com/yituanxing/minicore/actions/runs/42214303482) → [#42219309886](https://github.com/yituanxing/minicore/actions/runs/42219309886) 三次成功继续，最后可恢复完整状态 **312,000,000 cycles / 78,170,492 commits**，仅 OpenSBI 已观察到；**无 ext4 挂载、原生 PID1、登录的 RTL 凭证**。
- [#42221108211](https://github.com/yituanxing/minicore/actions/runs/42221108211) 在运行内完成 332,000,000 cycles / 83,178,875 commits，但是下一窗口 332→352M 于 2026-11-06 12:56:41 UTC 因 `Product SD1 CMD0 before 74 CS-high/DI-high initialization clocks` 中止。**332M 没有正式发布的可恢复工件**，不能作为新的续跑源。
- [312M 原始 UART 启动日志](product-sd1-boot-312m-original-uart.log) 已从 **经过校验的完整 host checkpoint** 直接提取并永久入 Git：10,726 字节、SHA256 `138d77ee29042b73de34feb06c77dd57a749b0f17f1b0669df0c5f7d2c1d19ca`。末尾内核打印时间约 `[4.793442]` 秒，在 `SuperH (H)SCI(F) driver initialized` 附近；**仍未见 SD1 ext4 根文件系统挂载**。
- C1（通用内核、rootfs 尚未挂载）QEMU 日志曾出现 `AETHERCORE_C1_READY rootfs_unmounted=1`，但 GO 输入交接未闭环，不能与官方 SD1 完整 Debian 路线或 RTL 原生 PID1 混淆。

## 已保存的远端全状态

| 运行 | 完整状态 | 提交指令 | GitHub artifact ID | 过期时间（UTC） |
| --- | ---: | ---: | ---: | --- |
| [#42210893404](https://github.com/yituanxing/minicore/actions/runs/42210893404) | 112M cycles | 27,666,988 | 13499356173 | 11-20 11:41:27 |
| [#42214303482](https://github.com/yituanxing/minicore/actions/runs/42214303482) | 212M cycles | 53,024,730 | 13501235911 | 11-20 12:08:10 |
| [#42219309886](https://github.com/yituanxing/minicore/actions/runs/42219309886) | 312M cycles | 78,170,492 | 13503930550 | 11-20 12:50:46 |
| [#42221108211](https://github.com/yituanxing/minicore/actions/runs/42221108211) | 332M cycles（仅该次日志） | 83,178,875 | **无** | 不可恢复 |

这些工件含冻结原版 simulator、完整 DDR/RTL/SD host checkpoint、manifest 和 SHA256；保存期为 **14 天**。下一轮只从校验通过、兼容性一致的完整快照恢复。中间窗口虽然单轮内经过验证，但未逐窗口发布为独立远端快照。

## 为什么需要分阶段记录

在同一 20M cycles 的近似 Actions 时段（由相邻 `STAGE_READY` 时间戳相减，**包含封存/校验开销，非纯仿真耗时**）：

| 同一运行内区间 | 单个 20M 窗口约耗时 | 粗略周期吞吐 |
| --- | ---: | ---: |
| 32→112M | 82–89 秒 | 22–24 万 cycles/s |
| 132→212M | 93–94 秒 | 21–22 万 cycles/s |
| 232→312M | 约 133 秒 | 约 15 万 cycles/s |

212M 之后宿主吞吐下降约 30%，但这恰好跨越两个不同的 GitHub Actions job；同一段 guest 的 CPI 仍大约 3.97–3.99。**现阶段不能把慢速直接归因于 Linux 某个驱动或 CPU 设计**，也可能是 runner CPU/共享负载。后续必须同时留存 runner CPU 信息、纯仿真耗时、快照打包耗时，最好同机重放相同窗口，才能判断真正的瓶颈。

## 原始 UART 中第一个值得剖析的时间空窗

312M 状态里的原版 Linux 6.6.143 串口日志约 115 条内核时间戳，已经走到 **4.793442 秒**，并非“尚未进入 Linux”。按日志相邻时间戳排序，最大空窗为：

- **[1.642012]** `io scheduler bfq registered`
- **[4.644577]** `Serial: 8250/16550 driver, 4 ports, IRQ sharing disabled`
- 二者相差 **3.002565 秒**，期间没有 UART 打印。第二大空窗是 0.911643→1.404195，约 0.492552 秒。

这只能证明该区间**没有可见的 printk**，不能单凭日志认定 CPU 卡死，也无法归因于 BFQ、8250、SPI、某个 initcall 或硬件时序。未来需要同一冻结内核下更细的 initcall/trace 证据，或专门新建不同兼容性指纹的诊断启动；不能偷换现有 312M 快照所绑定的内核和固件。

## 三个已核验的 RTL 周期 ↔ Linux 时间锚点

112M 和 212M 两份原始快照各自提取的 UART，与 [已入 Git 的 312M UART](product-sd1-boot-312m-original-uart.log) 的对应长度前缀逐字节一致、SHA256 一致。无需再重复保存相同串口全文。

| 真实恢复节点 | 退休指令数 | 累计 UART | 最后一条 Linux printk |
| --- | ---: | ---: | --- |
| 112M cycles | 27,666,988 | 8,364 字节，SHA256 `5e40134b…` | 0.911643s，切换到 `riscv_clocksource` |
| 212M cycles | 53,024,730 | 10,243 字节，SHA256 `0ed077d2…` | 1.642012s，BFQ 调度器注册 |
| 312M cycles | 78,170,492 | 10,726 字节，SHA256 `138d77ee…` | 4.793442s，SuperH SCI 串口驱动初始化 |

112→212M 仍退休 25,357,742 条指令，CPI≈3.94357；212→312M 又退休 25,145,762 条，CPI≈3.97681。**3.002565s 的无 printk 区间不代表 CPU 不提交，更不是 3 秒的仿真宿主墙钟耗时。** 需要单独的 initcall trace 才能把时间归因给某个具体驱动。

## 原生 Debian QEMU 门禁也已留存

原 [#42207139595](https://github.com/yituanxing/minicore/actions/runs/42207139595) 工件 #13496414850 在 **2026-11-13 11:08:40 UTC** 先于 312M RTL 快照过期，故已经额外永久归档完整原版 [QEMU UART](evidence/sd1-native-qemu-42207139595/boot-uart.log)、[boot-result](evidence/sd1-native-qemu-42207139595/boot-result.txt) 和 [SHA/媒体身份结果](evidence/sd1-native-qemu-42207139595/result.txt)。三者原始内容分别为 23,689 / 264 / 633 字节，SHA256 见 JSONL 台账。这不会自动延长 GitHub artifact 本身的有效期；下一次 RTL 接力的现有门禁仍要求可下载的成功 QEMU artifact，过期后必须先做便宜的等价 QEMU 预检或严格审核后的证据更新，**不可因此从零重跑 RTL**。

**两条路径的区别：**这个原生 Debian QEMU oracle 的根设备是 `/dev/vda1`（virtio block），而 Product RTL 使用真实 SPI SD 卡。QEMU 验证了官方 ext4/`/sbin/init`/systemd/登录提示链路，**没有验证 Product 的 Linux MMC SPI GPIO-CS 握手**。

## SD SPI 下一条低成本诊断

332M 之后的 CMD0 错误与 Linux 6.6 `mmc_spi_initsequence()` 通过翻转 `SPI_CS_HIGH` 发出 18×`0xff` 初始化时钟、而 GPIO 描述符 CS 路径依旧物理选中（CS_N低）这一组合高度吻合。候选短修复是仅在这段预备传输临时设置 `spi_transfer.cs_off=1`，并以 **144 个 CS_N 高/MOSI 高的 SCLK 边沿**、**73 时钟拒绝与 74 时钟接受 CMD0**、恢复 CS 低有效后的 CMD0 R1 回应做短验证。当前这仍是**待实证的根因假说**，未修改冻结内核或启动长跑。任何新的 Linux Image 身份都不能继承旧 312M 主存快照；两条验证线必须分别标记。

## 可继续累积的数据

机器可读的 17 条历史进度记录和 1 条失败记录见 [JSONL 台账](product-sd1-boot-stage-ledger-2026-11-06.jsonl)。未来 [SD1 relay workflow](../.github/workflows/product-sd1-official-artifact-relay.yml) 每个成功窗口额外生成 `analysis/segments.jsonl`、原始累计 UART、逐窗 UART 增量、runner CPU 信息、分窗口 save/relay 日志和 SHA256；失败时抢救最近经过正式完整校验的独立工件及分析日志，**保留失败状态，不伪装 PASS**。后续只会在新的人工 label 触发后将兼容性匹配、再次完整校验通过的 recovery 工件选为续跑源。分析专用工件预设保留 90 天，而这个小型版本化历史台账存于 Git 仓库，长期可追溯。

接下来优先处理 **Linux MMC SPI CMD0 之前的 CS-high/DI-high 74 个时钟**，先用短时 SPI/GPIO 引脚级验证确认时序，再决定是否需要 Linux `mmc_spi_initsequence`/CS 控制修复。没有短验证依据前，停止反复触发 312M→后续的长 RTL 接力；绝不禁用协议守卫来制造虚假启动成功。
