# NaxRiscv 对标与 FPGA Debian 产品验收（2026-11-03）

**决策：NaxRiscv 是主要 FPGA 乱序核架构参照；真实 FPGA 上运行完整上游 Debian riscv64 是最终产品验收。**
本文件为当前产品架构的方向修正，不宣称 AetherCore 已实现 RV64GC/LP64D 或已有真实板级 Debian 验收。

## 1. 精确的上游事实与源码版本

- NaxRiscv 官方仓库：[SpinalHDL/NaxRiscv](https://github.com/SpinalHDL/NaxRiscv)，参考提交 [9f452d5](https://github.com/SpinalHDL/NaxRiscv/commit/9f452d50560d02fb391bc8039f5453c54e0911af)（2026-01-23）。
- [Config.plugins — Gen.scala](https://github.com/SpinalHDL/NaxRiscv/blob/9f452d50560d02fb391bc8039f5453c54e0911af/src/main/scala/naxriscv/Gen.scala)：用参数和插件构成可伸缩核。包含 `decodeCount`、`robSize`、`dispatchSlots`、`lqSize/sqSize`、`withFloat/withDouble/withRvc`、`withMmu`、`withSupervisor/withUser`、`withDistributedRam`。注意默认 `withRvc=false`、`withFloat=false`、`withDouble=false`：**RV64 并不自动满足发行版 ABI**。
- [RfTranslationPlugin](https://github.com/SpinalHDL/NaxRiscv/blob/9f452d50560d02fb391bc8039f5453c54e0911af/src/main/scala/naxriscv/frontend/RfTranslationPlugin.scala)：推测 RAT 和已提交 RAT 双映射，加 dirty bit 选择；Commit reschedule 时撤销推测映射。其例子不是每个分支都存一份完整 RAT 快照。
- [RfAllocationPlugin](https://github.com/SpinalHDL/NaxRiscv/blob/9f452d50560d02fb391bc8039f5453c54e0911af/src/main/scala/naxriscv/frontend/RfAllocationPlugin.scala)：独立物理寄存器分配和退休释放，保护 x0；有多端口分配/释放策略。
- [RobPlugin](https://github.com/SpinalHDL/NaxRiscv/blob/9f452d50560d02fb391bc8039f5453c54e0911af/src/main/scala/naxriscv/misc/RobPlugin.scala)、[CommitPlugin](https://github.com/SpinalHDL/NaxRiscv/blob/9f452d50560d02fb391bc8039f5453c54e0911af/src/main/scala/naxriscv/misc/CommitPlugin.scala)：按宽度分组 ROB/Commit、调度器完成回收、重调度/flush 优先级；这些接口比“把 Tiny ROB4 改成 64”更值得学。
- [Lsu2Plugin](https://github.com/SpinalHDL/NaxRiscv/blob/9f452d50560d02fb391bc8039f5453c54e0911af/src/main/scala/naxriscv/lsu2/Lsu2Plugin.scala)、[MmuPlugin](https://github.com/SpinalHDL/NaxRiscv/blob/9f452d50560d02fb391bc8039f5453c54e0911af/src/main/scala/naxriscv/misc/MmuPlugin.scala)：Load/Store 分队列、存储转发、年龄依赖/重放、提交后 Store writeback、PTW/MMU refill，是真实 Linux 所需机制。
- [RegFilePlugin](https://github.com/SpinalHDL/NaxRiscv/blob/9f452d50560d02fb391bc8039f5453c54e0911af/src/main/scala/naxriscv/misc/RegFilePlugin.scala)：FPGA 端 RAM、读写端口和时序与 ASIC 端实现分离。
- [NaxRiscv 文档](https://spinalhdl.github.io/NaxRiscv-Rtd/main/NaxRiscv/introduction/index.html)与 [37C3: Open CPU/SoC design, all the way up to Debian](https://media.ccc.de/v/37c3-11777-open_cpu_soc_design_all_the_way_up_to_debian)：明确目标为 FPGA 乱序核及 Linux/Buildroot/Debian；官方有 Arty A7/LiteX 硬件运行记录。
- [LiteX/NaxRiscv 板级集成示例](https://github.com/SpinalHDL/NaxRiscv#running-on-hardware)：Nexys Video `--cpu-type=naxriscv --with-sdcard` 等，DTB 必须根据真实外设适配；不意味着我们必须迁移到 LiteX。
- [Debian 官方 riscv64 硬件与 ABI 基线](https://wiki.debian.org/RISC-V/)：**RV64GC + LP64D**。
 
这些证据说明 NaxRiscv 是可借鉴的 **FPGA-aware OoO + SoC + 发行版** 成功范例，不意味着其 155 MHz/13.3K LUT 的特定配置可无条件迁移到 AetherCore，或不经实测就确定我们的 FPGA 板型。

## 2. 必须分开的三根配置轴

| 维度 | 示例 | 不应错误绑定 |
| --- | --- | --- |
| **微架构几何** | Small: 1 decode/1 issue/1 retire、ROB8/PRF48；Medium；Large 2 decode/2 retire、ROB64 | 并不决定是否有 F/D；小乱序核也可以运行发行版 |
| **ISA/OS 能力** | 工程验证 `rv64ima`；产品 `rv64gc` + `lp64d` + M/S/U/Sv39 + Zicsr/Zifencei | 不能把 FPU 作为“必须等 ROB64 完成后才做”的性能功能 |
| **SoC/板级能力** | 虚拟 FPGA；真实 FPGA + DDR/SD/eMMC + UART/时钟/复位/DTB/中断 | Linux PID1（RAM initramfs）不等于真实板上完整 Debian rootfs |

产品配置以 **ISA 能力 × 微架构几何 × SoC/板级** 选出可验证组合，不制造独立的 SmallCore / LargeCore 两套代码。G1 Small 实现可先只支持 IMA 以验证机制，但**下一个应用里程碑应是 Small 几何上的 RV64GC/LP64D 完整能力**，然后再在真实板上跑 Debian，而不是必须先扩到 ROB64。

## 3. 逐层借鉴什么，不必照搬什么

**必须借鉴：**
1. 生成配置约束和统一接口：RAM 实现、寄存器读写端口、ROB/Dispatch/Commit 宽度、ISA 能力各有自身约束/能力验证。
2. Frontend → Rename/RAT/FreeList/ROB/Issue → Tagged Completion → Commit；store 队列完成前不允许副作用外露。
3. 具备独立 LQ/SQ、Store forwarding、内存顺序检查和必要时的 load replay 的 LSU；不能把旧 Tiny 双 Load 当成最终 LSU。
4. 非阻塞 D-cache 的 refill/writeback 槽、PTW 与 D-cache 的 PTE 可见性、Sv39/TLB/PMP、MMIO/interrupt/CSR 语义。
5. FP 状态所有权：FPR、FS Dirty/Clean、FCSR/fflags、精确退休，用户态动态库和多进程切换可验证。
6. FPGA-aware RAM banking、分布式 RAM/BRAM 选择、带宽/端口与时序预算；下板结果必须给实际器件与布局布线证据。

**不机械复制：**
- NaxRiscv 使用 SpinalHDL 插件框架；我们在 Chisel 可以采用**明确 typed IO/服务与单一状态 owner**，不要求复刻其 fiber/plugin 运行机制。
- Nax `Gen.scala` 的示例里 **ROB64 + 整数物理寄存器 64** 也是可工作的配置策略。我们的 PRF96 是为了避免写寄存器目的值时比 ROB 容量更早耗尽的 **性能/容量候选**，不是 ISA 正确性下限。必须允许 FreeList backpressure，并根据 PPA 调整 PRF64/96。
- Nax 的 `TranslatorWithRollback` 用 speculative/committed 双映射和 dirty mask，在提交重调度后清理推测状态；此前 G1 选定的逆向 Undo Log 只是**一种待实现的策略**，不应成为接口固定条件。先冻结 `RecoveryCut`、身份、older/younger 精确性，再把“全域 commit-reschedule rollback”和“younger-only undo”作为两种可对照策略。不要在未证明性能和资源时叠两套。
- 用 LiteX 是 Nax 的系统实现范例，现有 AetherSoC AXI/PLIC/ACLINT/UART 路线可保留；参考其真正可运行的 SD、DDR、时钟、DTB、启动固件验证资产。

## 4. 发行版优先的工程通路

| 关口 | 最小可证明结果 | 禁止拿来替代的结果 |
| --- | --- | --- |
| **D0 — 现有 v0 实体板带电** | 借现有 Linux v0 核下板，PLL/DDR 校准、UART/ROM/AXI、实际板级时序达标 | GSIM/Verilator 运行 |
| **D1 — Product Small OoO 基础正确** | 1-wide ROB8/PRF48 完成真正乱序完成与按序退休、恢复、存储/异常 + Spike 差分 | Rename Python 模型或 Chisel 编译成功 |
| **D2 — Small 的发行版 ISA** | RV64GC/LP64D，C+Sv39/PMP，F/D 指令与 FCSR/FS/上下文切换，多进程线程/原子/futex/页故障、动态链接测试 | 只跑 `rv64ima` OpenSBI/Linux PID1 |
| **D3 — 真实 FPGA 完整系统** | OpenSBI → Linux（按板级需求可含 U-Boot）→ 持久化 rootfs → 上游 Debian `riscv64` 用户空间：登录、shell、glibc 动态程序、apt/dpkg 安装/读取持久化数据、重启重复验证 | initramfs BusyBox、串口打印、停在 PID1 |
| **D4 — 性能/规模优化** | 同一套 RTL 生成 Medium/Large；对 FPGA PPA、IPC、DDR 带宽、软件负载做取舍 | 大 ROB 的仿真跑通就算目标完成 |

D0 与 D1/D2 可以并行。D3 依赖 D0 和 D2。D4 **不能成为 D3 的前置条件**。真实 FPGA 目标板未确定前，所有资源/频率只算候选估算。

### 实际发布证据（每个产品 Profile 都应留下）

- `isa_march`、`abi`、`misa`、FCSR/FS/TLB/PMP、Linux 内核与 Debian rootfs 版本/哈希。
- FPGA 器件/板卡、约束、综合与布局布线 LUT/FF/BRAM/DSP/Fmax、DDR 校准、时钟/复位日志。
- SD/eMMC/持久存储和 DTB 绑定；OpenSBI/bootloader/Linux 启动日志、串口登录、用户态命令/进程、动态链接、浮点、线程/原子、Futex、文件系统读写/断电重启完整性。
- 相同应用和镜像的最终行为验证、故障排查日志；不能只写 `Linux PASS` 四个字。

## 5. 对 PR #379 和下一批代码的直接修订

- **G0** 状态所有权仍正确，接口不推倒；确立 NaxRiscv 优先参照，并把恢复策略选择标为可验证实现选项而非单一先验结论。
- **S** 一次清理共享译码语义，使 ISA 能力和后端几何独立；不能动旧 v0 软件配置的正确性。
- **B** Small OoO 完成重命名/ROB/Commit/Recovery/LSQ；新增可独立打开的 C、F/D 和 FP architectural state 路径；先做功能正确而非先扩 ROB64。
- **I** 现有 AXI SoC 的新核可选集成与实体板验证；并行完成 DDR/SD/eMMC/DTB/启动链。
- **验收主线**：从这里开始不把 FPGA Linux PID1 视为最终产品，不以 Small/Medium 大小为发行版能力的替代目标，停止没有直接支持 D0–D3 的抽象架构设计。

**结论：先让同一套可配置乱序核具有完整 RV64GC/LP64D 能力，先让它在真实 FPGA 上跑 Debian，之后根据资源/性能选择大 ROB。**

## 6. Product SoC 容量参数贯通与资格边界（2026-11-04）

本轮只扩展**已经实现的单通道** SmallOoO 后端的存储深度，避免把结构容量增大误报为双发射、双退休或 RV64GC 已完成。

| Product 硬件组合 | Decode / Dispatch / Issue / Retire | ROB / IQ / PRF | LQ / SQ | SoC 外部事务 ID | 资格 |
| --- | --- | --- | --- | --- | --- |
| Small | 1 / 1 / 1 / 1 | 8 / 4 / 48 | 2 / 2 | 4 位 | 原 Linux/OpenSBI 工程基线，默认值不变 |
| Expanded single-lane | 1 / 1 / 1 / 1 | 16 / 8 / 64 | 4 / 4 | 5 位 | 同一套后端与 SoC RTL；需独立的 BootROM/LSQ/AXI 验收 |
| Medium / Large 候选 | 2-wide 等 | 参数候选 | 参数候选 | 按各自 LSQ 推导 | **尚无对应多通道 RTL 验收，不能宣称已实现** |

容量的位宽由 `ceil(log2(LQ + SQ))` 得到本地 Client ID，统一内存仲裁器在其高位添加 Data / PTW / I-fetch 三个源的 2 位标记，再贯通 AXI4 和 FPGA IO。过去的 2 位本地 ID / 4 位 AXI ID 常数不能容纳扩展后的 LQ/SQ。保留默认 Small 的接口宽度，避免改变已验证的旧启动链。

ISA 也须独立声明：当前可实例化的工程硬件为带 **C** 的 RV64IMAC（软浮点 `lp64`）；尚未实现 F/D 执行及 FCSR/FS，`rv64gc/lp64d` 仅是产品目标，不可直接实例化为已完成硬件。为防止错误验收，Product paged RTL 对包含 F/D 的配置显式拒绝。

资格证据分层：`ProductGeometrySoCSpec` 先证明两档**实际 RTL**在统一内存、AXI4、FPGA 顶层展开；接着借助固定版本 Verilator 检查两档 BootROM → 外部 RAM → Load 退休路径。后者列入 Product G2 directed CI；CI 未通过前不将 expanded 档标记为运行时 PASS。完整 Debian 仍须经过 D2（F/D 等）与 D3（实体 FPGA + 持久化 rootfs）的验收，不能被这批参数化工作替代。
