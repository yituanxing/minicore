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
| **D0 — 未来实物板上电** | 获得并确认实际器件/原理图后，复用 Linux v0 核验证 PLL/DDR 校准、UART/ROM/AXI、板级时序 | GSIM/Verilator 运行；当前尚无实物，不能声称 D0 已开始 |
| **D1 — Product Small OoO 基础正确** | 1-wide ROB8/PRF48 完成真正乱序完成与按序退休、恢复、存储/异常 + Spike 差分 | Rename Python 模型或 Chisel 编译成功 |
| **D2 — Small 的发行版 ISA** | RV64GC/LP64D，C+Sv39/PMP，F/D 指令与 FCSR/FS/上下文切换，多进程线程/原子/futex/页故障、动态链接测试 | 只跑 `rv64ima` OpenSBI/Linux PID1 |
| **D3 — 真实 FPGA 完整系统** | OpenSBI → Linux（按板级需求可含 U-Boot）→ 持久化 rootfs → 上游 Debian `riscv64` 用户空间：登录、shell、glibc 动态程序、apt/dpkg 安装/读取持久化数据、重启重复验证 | initramfs BusyBox、串口打印、停在 PID1 |
| **D4 — 性能/规模优化** | 同一套 RTL 生成 Medium/Large；对 FPGA PPA、IPC、DDR 带宽、软件负载做取舍 | 大 ROB 的仿真跑通就算目标完成 |

D0 与 D1/D2 在有实物后可以并行；**当前没有实物板卡，D0 完全暂缓**，只推进软件可实现的 D1/D2、D3 的离线软件和可综合 RTL。未来实物 D3 依赖 D0 和 D2。D4 **不能成为 D3 的前置条件**。实际器件与板卡约束未验收前，所有资源/频率只算候选估算。

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

资格证据分层：`ProductGeometrySoCSpec` 先证明两档**实际 RTL**在统一内存、AXI4、FPGA 顶层展开；再借助固定版本 Verilator 检查两档 BootROM → 外部 RAM → Load 退休路径。提交 `54b0667` 的 [Product G2 CI](https://github.com/yituanxing/minicore/actions/runs/41672431552) 已通过这两层门禁；后续物理 LSQ 重构属于**新 RTL**，必须重新验收。完整 Debian 仍须经过 D2（F/D 等）与 D3（实体 FPGA + 持久化 rootfs）的验收，不能被这批参数化工作替代。

## 7. 物理 LSQ 与 PMP 所有权收敛（2026-11-04）

真实数据流复核发现：旧 `ProductPhysicalLsq` 的 `MemSlot` 由 `ROB entries` 分配，且每个候选对全 ROB 遍历、构造 Store Forwarding 数据路径。它让两个独立的容量轴错误耦合，也让扩大 ROB 产生不必要的平方级组合网络。

本批架构修改后的硬件契约：

- **ROB 只管理指令生命周期**；LSQ 宽物理槽数量等于 `LQ+SQ`，各槽拥有完整 `RobId(index,generation)`，不再用 LSQ 下标冒充 ROB 下标。
- Reserve / Resolve / Kill / Memory Response 都用准确身份查找槽。已发出外部请求即使被 squash，也保留独立 `MemTxnId` 租约直到 terminal response；不可用新的 ROB generation 接走旧响应。
- Store 依赖比较和地址覆盖检查随 `(LQ+SQ)^2` 扩展，而非 `ROB^2`；实际 64 位转发数据选择位于仲裁之后，只形成一条共享宽数据路径。按 ROB head 相对年龄的**平衡选择树**保留跨环绕的最老候选优先级。
- `ProductDataResolver` 保留每个译码时刻的 PMP 快照，但后级只锁存 `slot index+paddr`，共享原翻译槽的上下文所有权，删除宽快照的第二份逐位复制；round-robin 对非 2 的幂队列使用真实取模索引。

| 配置 | 旧 LSQ 宽物理槽 | 新 LSQ 宽物理槽 | 旧依赖比较上界 | 新依赖比较上界 |
| --- | ---: | ---: | ---: | ---: |
| Small ROB8 / LQ2+SQ2 | 8 | 4 | 64 | 16 |
| Expanded ROB16 / LQ4+SQ4 | 16 | 8 | 256 | 64 |
| ROB64 / LQ16+SQ16 候选 | 64 | 32 | 4096 | 1024 |

这些仅是**结构实例数量/比较对数**，并不是 LUT/FF/Fmax 实测值。增加 `ProductPhysicalStorageSpec` 与杀死 post 分类、六槽轮转动态验证；新增 Product Small/Expanded 同顶层 PA32 `synth_ecp5` 面积及逻辑深度 A/B 门禁。**必须看 RTL/Verilator/综合实际结果后才能评价 FPGA 收益**，不能凭表直接声称已经满足 FPGA 资源或 Debian 产品要求。

## 8. Small 上的浮点架构所有权与产品资格边界（2026-11-04）

本批以 **Small 单发射乱序核直接承载未来 RV64GC/LP64D** 为架构约束，而不是先堆成多发射或大 ROB。实现遵循 NaxRiscv 可参考的原则：ISA、重命名与物理寄存器容量彼此独立；整数和浮点各有寄存器状态所有权，共享指令生命周期、提交和异常恢复。**本节描述正在集成的源码切片，不代表本次 Scala/Chisel 门禁已通过。**

| 层次 | 本批硬件接口/机制 | 当前资格及禁止推论 |
| --- | --- | --- |
| 译码、C 扩展 | `DecodedFloatingOperation` 单独保存 F/D 操作种类、格式、三个 FPR 源、整数源和目的寄存器域；`FpExtensionDecode` 负责指令合法性；C.FLD/FSD 及 SP 变体仅随 D 配置打开 | 覆盖译码语义，不意味着对应 IEEE 算术能执行 |
| Rename / IQ / ROB | `ProductSmallOoOBackend(enableFloating)` 条件生成独立浮点 RAT、FPR 物理寄存器、FreeList、ready/generation；整数与浮点操作共享单发射仲裁、ROB、Tag Completion、按序 Commit 和年轻指令撤销；`f0` 是普通可写浮点寄存器，与固定零的 `x0` 不同 | 不新增第二条全功能流水线，不假设 ROB64 或双发射；FP 开关关闭时原整数后端实例仍是基线 |
| 统一 LSQ | `ProductPhysicalLsq` 用 `destinationFile` 区分 FLW/FLD 的浮点写回；FLW 从外存和 Store Forwarding 两条路径统一执行 NaN-boxing，FLD 保留 64 位，FSW/FSD 经同一存储队列，异常路径禁止错误写回 | 不创建第二份浮点 Load/Store 系统；内存序、精确异常及回滚仍需新配置动态回归 |
| FCSR / FS | `MachineCsrFile` 实现 `fflags/frm/fcsr`、`mstatus.FS` 与派生 `SD`，并由**真正 ROB 退休**端口累积浮点标志及 Dirty；FS=Off 时访问浮点 CSR 非法 | 不能用执行完成时更新 FCSR；需验证 Trap、S/U 上下文切换与 Linux 保存/恢复语义 |
| FP 执行 | `ProductFpUnit` 提供真实的位级 FSGNJ、FMIN/MAX、比较、FCLASS、FMV 等逻辑，并处理单精度 NaN-boxing、sNaN/NV 和 ±0；与整数单发射端口互斥 | **没有经过验证的 IEEE 加减乘除、平方根、FMA 与带舍入转换**。缺失操作必须以原始指令 `mtval` 精确 IllegalInstruction 结束，不能伪装成计算结果 |
| PagedCore 产品入口 | `ProductSmallMemoryCore` 可按 ISA F 参数生成上述条件路径；`ProductSmallPagedCore` 仍保留对 F/D 的**显式 `require` 拒绝** | **目前不可把这批切片作为 `rv64gc/lp64d` 发行版产品进行实例化或宣称通过** |

产品能力要越过该阻断，必须先有 FPGA 可综合且可审计的 IEEE-754 F/D 算术 IP（例如经过许可、版本固定并实测的 HardFloat 集成），补齐动态舍入模式、转换、异常标志与 NaN 的指令级正确性；然后把 FP 指令加入 Spike 对照、旁路/回滚、FS 切换、线程/进程上下文切换和内核 `ptrace`/信号处理的验证集合，再解除 `PagedCore` F/D 拒绝。**IP 接入不是复用整数 ALU 的简单优化，也不应触发重做 ROB/LSQ 架构。**

这一切仍只对应 **D2 的候选实现**。D3 必须独立提供目标 FPGA 的布局布线 Fmax/BRAM/LUT/FF、DDR 与持久存储、真实 DTB/固件、上游 Debian riscv64（LP64D）动态链接、shell/apt/dpkg、文件持久化与复位重启证据。即使 Small 上的 RV64GC 指令级差分全部通过，也不能代替板级发行版实测。

## 9. F/D 算术与整个 SoC 的统一配置入口（2026-11-04，待 CI 资格）

上节描述的是 **HardFloat 算术集成之前** 的状态，不能再把“没有 F/D 算术”或“PagedCore 必然拒绝 F/D”当作新代码的静态事实。本批引入 HardFloat 0.1.1 的 F32/F64 加减乘、真正融合乘加、迭代除法/开方、整数与格式转换及 IEEE `fflags`，与已有浮点位操作、FPR 重命名、精确 ROB 退休和 CSR 唯一所有者连接。同一个 Small/Expanded 单发射核按 ISA 生成 F/D 或关闭 F/D；不创建第二套 FP 后端。

本次架构切口不再允许 FPGA、AXI4、统一内存三层各自重建固定的 `rv64imac/lp64` 配置：顶层显式选择一个 `ProductCoreConfig`，下层沿用这个对象及其 `geometry`、ISA/ABI、PA 和启动 ROM 约束。保留既有 IMAC 工程默认，另提供显式 `smallOpenSbiDistroTarget` 以**尝试**生成 RV64GC/LP64D SoC。PagedCore 的 F/D 配置拒绝转为 G1 真实取指→FS/CSR→浮点执行/LSQ→精确退休测试，新增独立 RV64GC paged RTL elaboration；G2 在 FPGA 最外层检查 ISA/ABI 与 AXI 事务 ID 同源。

这里有三层不同的资格，不应混为一谈：

1. **构造/编译/RTL**：同一配置贯通顶层、HardFloat 模块被真正实例化并能生成 Verilog；这只证明可构造。
2. **功能**：F7 数值与恢复、G1 分页特权态/FS Dirty/陷阱、G2 总线连接及 Spike/SoftFloat 独立差分全部有通过记录；在门禁完成前保留“待验证”标记。
3. **产品**：目标 FPGA 全 SoC 的布局布线及容量、运行真实 OpenSBI→Linux→Debian LP64D 与持久化软件；**这层仍未完成**。

FPU 单精度/双精度模块、三读口 FP PRF 对 LUT、FF、BRAM、Fmax 的实际压力尚无本轮板级数据。此阶段不会为“优化面积”提前改变 PRF 读取时序或增加第二套 ISA 核；先看同配置 F-off/F-on 的综合结果，再决定可度量的结构改动。

## 10. 当前无实物的软件阶段任务边界（2026-11-05）

用户确认目前**没有 FPGA 实物**。产品目标仍是可配置的小乱序 RV64GC/LP64D SoC，最终让真实 FPGA 运行完整上游 Debian riscv64；但软件阶段不以 DDR PHY、MIG/PLL、PCB 引脚约束、下载线或实物板时序为当轮阻塞项，更不能把板级合同、空壳接口当成硬件验收凭证。

这一阶段同时推进五条软件主线，并按实际依赖排序：

1. **CPU/SoC 架构与可配置性**：反复审查 Rename/ROB/LSQ/FPU/Sv39/中断/缓存/AXI 所有权和几何×ISA×设备组合，找到同类问题后集中整改；不因尚无综合数据而凭猜测扩成多发射或砍掉 IEEE 正确性。
2. **完整发行版的软件启动链**：真实 OpenSBI、内核 Image、单独 DTB、原生 Debian riscv64 rootfs、ext4/SD、systemd、动态链接、登录和持久化。供应链文件必须有确切 digest/SHA，且软件建好与真正从 RTL 执行成功是两类证据。
3. **仿真成本与有效加速**：模型缓存 fingerprint、增量构建、分层状态快照、热启动和必要时 QEMU 状态导入；旧 V2/GSIM 的测速、候选 Verilator 版本、自适应 settle 都不可自动算作当前 Product 已证明加速。一次昂贵长测之前必须确保新机制确实接入本次执行路径。
4. **工程结构**：一个工作分支、按逻辑类别大步提交、逐提交统计；避免高成本自动 CI，不能因为清分支或构建缓存而丢失历史成果。
5. **未来板级设计预留**：保持 DDR/时钟/固件源和 SPI SD 的清晰接口及正确的地址协议；不在无原理图、无器件的情况下写伪造的器件专用 DDR PHY 或约束。

**本阶段的工作模式**：先多轮读完整相关调用链，集中修确定性缺陷；暂停全量编译、测试、QEMU、RTL/发行版长模拟和反复 CI。软件静态收口后，才由单独明确批准的轮次一次性执行短成本门禁，再决定是否值得进行一个长时真实发行版负载。没有当轮运行记录时只能报“源代码已实现/待验”，不得沿用过往 Linux PASS 表示新配置已通过。

### 仿真加速当前可用性（软件源码审计口径）

- **已接入待核验**：`product_sim_cache.sh` 按 RTL/生成器、宿主编译器、编译选项与运行时头文件指纹复用同一 Product 仿真二进制；Product G3 与 G4 的相同 OpenSBI Top 采用相同 `-O3 -march=native` 构建身份和 GitHub Actions 缓存键（命中受 CI 缓存作用域限制）；`product_sim_ab.sh` 已包含真实 SPI-SD、独立 DTB 与固件一致的 SD1 Top 三档短窗口差分，`product_sim_sd1_manual.sh` 为加速配置设置按源码/工具/介质/差分结果身份匹配的手动晋级门禁。以上均仅做源码实现，未运行、未发放 Product 资格。
- **历史加速不直接继承**：历史 PA32/V2 路线有 GSIM 对 Verilator 的 Linux PID1 约 1.42692×、Adaptive Settle 约 13%–20% 的同工况测量，以及 forkserver 对重复工作负载的收益；Product SD1 并无对应当轮差分证明。GSIM 实现仍在独立 PR #378，当前 Product 分支没有可直接调用的 GSIM 生成/运行入口，不应假装已接入。
- **已有历史实现但不等于 Product 快照**：`l32_opensbi_runtime.h` 可以复制主机 256MiB RAM 和 LR/SC reservation；`opensbi_forkserver_main.cpp` 在旧 L32 登录后通过进程 `fork` 复用内存和 RTL 的运行态。这不能作为 Product SD1 固件→Debian 登录的持久可恢复快照，也不能跳过新镜像的一次真实启动。
- **尚未实现**：Product 的完整可恢复检查点必须同时保存同一时刻的 RTL/VerilatedContext、主机 RAM 与 LR/SC、TransportRuntimeState、串口/中断/时间、SPI SD 卡读写会话与 COW 等状态，并把所有对应模型、固件、DTB、SD 镜像指纹绑定到快照；此状态合同未完成，禁止把仅有 RAM 的快照标注为可恢复。
- **QEMU 状态导入不是现成加速**：QEMU 的架构寄存器/CSR/内存快照无法自动重建 Product 的 RAT、ROB、LSQ、TLB、AXI 在途事务。只有设计并验证一个清空全部在途微架构工作、可注入架构状态和设备状态的交接边界，且经过差分校验，才可称为接力热启动；目前不把它用于下一次长跑。

因此，下一次确有必要运行 Product RTL 时，默认仍选择已经指定的 **5.024/reference** 与经过 exact-hash 验证的可复用二进制；先做独立的短程 A/B，只有得到同周期状态一致证据后才考虑升级 simulator 或 settle 算法。无此证据不启用未知快路径。
