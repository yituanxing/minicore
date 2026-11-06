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

## SD SPI CMD0 定点根因与短门禁（2026-11-06）

**原障碍仍存在，完整 RTL Debian 尚未启动。** [正式接力 #42221108211](https://github.com/yituanxing/minicore/actions/runs/42221108211) 在 332→352M 报错，不能把 332M 当作远端可恢复快照。现存最近已发布的完整快照仍是 312M。

已完成不碰 Linux 长跑的三组针对性验证：

- [SPI/GPIO 短门禁 #42229855857](https://github.com/yituanxing/minicore/actions/runs/42229855857) **SUCCESS**：`ProductSd1SpiGpioSpec` 2/2、`ProductSd1FabricSpec` 2/2。经真实 Product fabric、SPI TXDATA 和 GPIO 寄存器，实测 18×`0xff` 对应 **144 个 CS_N 高且 MOSI 高的 SCLK 上升沿**；同样发时钟但 CS_N 低时不满足初始化要求。
- [`SdSpiCard` C++ 定向回归 ec81f6d8](https://github.com/yituanxing/minicore/commit/ec81f6d8053f09f5f74e4568d88d39b9fdfe2413) **1/1 PASS**：严格区分 73 个有效 CS-high/MOSI-high 时钟不足和 74 个足够；CS 拉低、MOSI 拉低都不能冒充 74 个有效时钟。协议守卫未关闭。
- 独立核查上游 [Linux v6.6.143 MMC SPI](https://github.com/gregkh/linux/blob/v6.6.143/drivers/mmc/host/mmc_spi.c)、[SPI core](https://github.com/gregkh/linux/blob/v6.6.143/drivers/spi/spi.c)、[`spi-oc-tiny`](https://github.com/gregkh/linux/blob/v6.6.143/drivers/spi/spi-oc-tiny.c) 和 [`spi-bitbang`](https://github.com/gregkh/linux/blob/v6.6.143/drivers/spi/spi-bitbang.c)：`mmc_spi_initsequence` 尝试翻转 `SPI_CS_HIGH` 并发送 18 字节 `0xff`；但设备树 GPIO-CS 路径在 `spi_set_cs()` 中按 transfer 的 `activate` 调 `gpiod_set_value_cansleep`，不通过翻转模式位改变实际 GPIO 状态；Product 的 `spi-oc-tiny` 由 bitbang 提供 `transfer_one`，最终采用 SPI core 默认消息路径，该路径有 `spi_transfer.cs_off`。这一组合**高度支持**此前的 CS_N 低初始化时钟根因，但仍缺真实 guest 的逐引脚 trace。

已将**仅用于隔离诊断、当前不会自动应用**的 [Linux 6.6.143 GPIO-CS `cs_off` 补丁](../tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch) 固化在仓库：只在检测到 GPIO-CS 时临时对 `host->status` 传输设置 `cs_off=1`，发送 18×`0xff` 后恢复 `cs_off=0`；非 GPIO 路径保留原样。下一步在**独立源码/构建目录**应用、编译新 Image，获得新 firmware/兼容性 SHA，先核实 Linux 真实驱动发出的 CS_N/MOSI/SCLK 与 CMD0 R1；通过短门禁后才能决定是否开展新身份的长跑。**新 Image 绝不能恢复旧 312M 主存状态。**

## 12M 周期三路仿真器 A/B（新留存）

[短 A/B #42229855875](https://github.com/yituanxing/minicore/actions/runs/42229855875) **SUCCESS**，12M 比较点的 Product CPU cycles/commits/traps/IRQs/PC、RAM/LRSC 及 SD1 UART/CPU/RAM/SPI 状态匹配。此处是短测试，**不是 Linux 或 Debian 长跑的等价验收**：

| 方案 | 测得 cycles/s | 相对旧版 |
| --- | ---: | ---: |
| Verilator 5.024 | 230,608 | 1.000× |
| Verilator 5.054 | 301,616 | **1.3079×** |
| Verilator 5.054 + adaptive | 297,642 | 1.2907× |

5.054 是**候选优化**；这次 adaptive 低于普通 5.054，不能宣传为额外提速。原始 A/B artifact **#13508595839**（42,641 字节）将在 **2026-11-20 14:03:35 UTC** 到期；已将速率、状态一致性、run 和 artifact ID 写入长期 [JSONL 台账](product-sd1-boot-stage-ledger-2026-11-06.jsonl)。下一步审查是否能在不改冻结身份的情况下迁移 simulator 二进制并重复短恢复，不拿旧快照冒险。

## Verilator 5.054 默认版本选择（2026-11-06）

- **官方 SD1 冻结 RTL 接力本来已经固定 5.054/reference + 1 thread**，312M 状态绑定的原始 savable 模拟器不会被替换；故 12M A/B 的 +30.79% **不是**旧 312M 长跑未来还能额外收获的一次提速。
- [SD1 手动入口 e421ed7](https://github.com/yituanxing/minicore/commit/e421ed77757d56db07c8bb049488adc84fa85eda) 把默认从 5.024 调到 **5.054/reference**，仍需要同构、同媒体、同工具指纹的短 A/B 证据才能实际编译；`AETHERCORE_VERILATOR_VERSION=5.024` 可明确回退。
- [G5 Debian 手动入口 464db62](https://github.com/yituanxing/minicore/commit/464db62f4463580e062cbf17119ebc5b7f9db293) 也以 **5.054/reference** 为首选，原有 30M Debian Top A/B 自检和显式 5.024 回退保留。不会因源码提交自动启动长运行。
- [默认版本契约测试 2d80555](https://github.com/yituanxing/minicore/commit/2d80555963b1955c203be143233a44a12de42cd9) 3/3 PASS，`bash -n` PASS。旧版安装器和 G3/G4 尚未重新资格验证的路径仍保留 5.024；不启用 adaptive 默认。

## 旧 PID1 为什么没有 CMD0 错误（2026-11-06 溯源）

这不是近期 Verilator 默认值变更所引入的 RTL 回归：[失败 #42221108211](https://github.com/yituanxing/minicore/actions/runs/42221108211) 明确在**冻结** `a15197f`、**Verilator 5.054/reference**、312M 完整 warm state 校验通过的前提下，先到 332M / 83,178,875 commits，再在 332→352M 遇到第一条不合规 CMD0；此前 `sd-cmds=0`、读写均为 0。

- G4/Tiny PID1 及 D1/D2/D3 都从 **内建 initramfs** 的 `rdinit=/init` 启动，不经 SPI/MMC 块设备；非 SD1 payload 明确关闭 `RV64_PAYLOAD_ENABLE_SPI_SD`。SD1 才从 **SD 卡 MBR/ext4** 的 `/dev/mmcblk0p1` 启动原生 `/sbin/init`，对应 DTS 中 `spi-oc-tiny` + GPIO-CS + `mmc-spi-slot`。
- 官方 QEMU 原生完整 Debian oracle 走 virtio `/dev/vda1`，也没有验证 Product GPIO-CS。
- [353a97e](https://github.com/yituanxing/minicore/commit/353a97ee716f4d6e384442c86e87ef515e14735e) 在 11 月 5 日增加了卡模型对 **74 个连续 CS-high/MOSI-high SCLK 上升沿**的严格要求，过早 CMD0 直接拒绝。该检查早于冻结 `a15197f`，属于新增 SD1 真实性门禁**暴露了此前未测路径的缺陷**，不是更新编译器产生的 CPU 指令错误；绝不能删除协议守卫以取得假阳性。

为继续验证，已给 [RV64 Debian Linux 构建脚本](../tools/ci/rv64_debian_linux_build.sh) 增加 **默认关闭**的 `AETHERCORE_SD1_GPIO_CS_DIAGNOSTIC_PATCH=1`：只允许 SD1、原版源先校验、固定 SHA `ff408c2…` 补丁先核对，再 `mktemp` 复制独立源码树后应用；已有 `build/rv64-debian-linux` 时直接拒绝，不会覆盖原 Image。构建后写入新 Image/.config SHA、上游源码 SHA、诊断补丁 SHA，并归档原始 patch，明确 `SD1_GPIO_CS_DIAGNOSTIC_ONLY_NOT_FROZEN_PRODUCT`。静态契约测试 3/3 PASS、`bash -n` PASS；**尚未实际编译新 Image，也未执行新诊断版 RTL**。这必须在一次性独立 checkout/runner 中运行，新 Linux/FW 身份禁止复用旧 312M checkpoint。

## 可继续累积的数据

机器可读的 17 条历史进度记录和 1 条失败记录见 [JSONL 台账](product-sd1-boot-stage-ledger-2026-11-06.jsonl)。未来 [SD1 relay workflow](../.github/workflows/product-sd1-official-artifact-relay.yml) 每个成功窗口额外生成 `analysis/segments.jsonl`、原始累计 UART、逐窗 UART 增量、runner CPU 信息、分窗口 save/relay 日志和 SHA256；失败时抢救最近经过正式完整校验的独立工件及分析日志，**保留失败状态，不伪装 PASS**。后续只会在新的人工 label 触发后将兼容性匹配、再次完整校验通过的 recovery 工件选为续跑源。分析专用工件预设保留 90 天，而这个小型版本化历史台账存于 Git 仓库，长期可追溯。

下一阶段优先对隔离内核补丁建立真实 guest 引脚追踪和 CMD0 R1 短证明，再考虑新 Image 的正式 SD1 长跑；旧冻结 312M 快照继续保留、不得混用。持续保存启动各阶段 UART、CPU 周期、退休数和宿主耗时，用于后续 Linux 启动性能分段分析。
