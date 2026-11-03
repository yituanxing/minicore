# RV64GC FPGA Linux 产品：从现有 AetherCore v2 到最终微架构的差距审查

**状态：架构评审 / 决策输入，不是已实现规格**  
日期：2026-11-03  
代码核查基线：`main@7ff35c6acdc3068754292bba814e4ec52ba4ec50`  
产品目标输入：用户文档《RV64 FPGA Linux 最终产品目标与系统方案 v1.1》（2026-11-02）  
并行工程收尾：`docs/GSIM_PERF_STUDY_CLOSURE_2026-11-03.md` 位于独立的 PR #378 分支；不要以其代替产品架构验收。

> **G0 follow-up (2026-11-03):** The ownership/data-flow/handshake proposal is in
> [docs/architecture/RV64GC_G0_DESIGN_AND_OWNERSHIP.md](architecture/RV64GC_G0_DESIGN_AND_OWNERSHIP.md),
> with machine-readable semantics in [rv64gc_g0_contract.json](architecture/rv64gc_g0_contract.json)
> and executable contract checks in [test_product_g0_contract.py](../tests_py/test_product_g0_contract.py).
> G0 remains **proposed**, not a frozen RTL design or Linux qualification.
## 0. 两种身份，禁止混为一谈

1. **已经存在的工程事实：** `yituanxing/minicore` 有完整的 Chisel RTL、AetherCore v2、AetherSoC v0、AXI/ROM/UART/PLIC/ACLINT/Cache，虚拟 FPGA 上 OpenSBI→Linux 6.6.143→用户态中断标志已经通过。以 `docs/AETHERSOC_V0_FREEZE.md` 和 `docs/ROADMAP.md` 为准。
2. **最终产品目标：** RV64GC + LP64D + Debian riscv64 发行版 + 持久化存储 + 实际 FPGA；CPU 主候选是双解码、双退休的乱序架构，IQ16/32、ROB64 等是候选容量而非冻结值。目标文档明确说明新产品 CPU RTL 尚未完成。不可将 v0 的 Linux PASS 改写为新目标 CPU 的 PASS。
3. **实现关系尚待正式确认：** 可以把 v0 作为可信的板级/软件验证参考，并把 v2 的语义、特权、内存、事务接口作为优先复用候选；不得未审计就把 Tiny 结构按容量直接放大，也不应为了新目标破坏已冻结的 v0。主产品源码只能保留一套可退化的微架构，已有 v1/v2 历史核只作为参考和正确性证据。

## 1. 真实现状：不是一句“没有乱序/没有 LQ”

| 主体 | 在 main 上能确认的现状 | 与产品目标的本质差距 |
|---|---|---|
| 前端 | `TinyPagedCore` 每次驱动一条语义指令；已有 XLEN-aware RVC parcel/decompress 路径，但当前 Linux 应用配置不含 C | 需要 FetchBlock/align/FetchQueue、双译码、跨块/跨页异常原子性、预测/恢复带宽 |
| ROB / Commit | `TinyRobGeometry.Entries=4`, `IndexBits=2`, 单个 `io.retire: Decoupled[RobRetirement]`; 8-bit generation，完整身份验证 | 不能仅改成 ROB64：须重新处理多路分配、双退休、资源回收、按序异常与并发状态更新 |
| 重命名 / 值 | `TinyDependencyState` 用 RAM/scoreboard 的 ProducerTag 和 ROB value；没有面向目标的 PRF/FreeList/committed+speculative RAT 双份状态体系 | 与目标 32→64+ 物理寄存器映射、双译码组内 RAW/WAW、精确回滚是不同结构；此处是大架构迁移 |
| Issue / 完成 | ROB4 read-only scheduling view，单 launch、单 completion accept；现有 oldest-ready 选择性计算 Issue | IQ16/32 不应复制整个宽 uOp 的 ROB age-ordered 大 MUX；需窄元数据、唤醒/选择/广播与 FPGA Fmax 预算 |
| LSU / Memory | `TinyPagedCore` **默认**实例化 `TinyLoadQueueMemoryBackend`；`TinyDualReplaySafeLoadUnit` 最多 2 个普通 Load lifetime，可保守越过前序 Load；Store/SC/AMO 保持 exact-head。并非完全没有 LQ | 缺少目标所需有明确年龄、地址可知性、转发和提交权限的 SQ/LSQ；2 个 Load slot 不是 LQ16/SQ16 或普适乱序内存 |
| 物理总线 | 核心 `AetherMem` 是语义事务，AXI adapter 属于平台；`TinyPagedCore` 仍要求 `busDataBits == XLEN` | 需 bus beat/XLEN 解耦、宽度/突发/事务标签、MSHR、时钟域与非一致性 DMA 可验证契约 |
| ISA / ABI | RV64IMA M/S/U/Sv39/PMP Linux 已通过；RV64C 的 M-mode 代表测试已通过，但 Linux profile 不含 C；F/D、LP64D 实现未发布 | 用户态发行版的 RV64GC/LP64D 需要新的 FPR/FS/fflags/fcsr/精确 FP 退休及 Linux 状态切换验证 |
| FPGA/系统 | `AetherCoreV2FpgaSoC` 提供板无关 AXI4 和 UART 物理边界；v0 有虚拟板 PID1 PASS | DDR PHY/PLL/约束/存储/真实板时序尚未验收；现有 ECP5 映射数字只是结构代理而非目标器件 Fmax |

上述代码锚点：`src/main/scala/aethercore/core/v2/{TinyPagedCore,TinyRobCommit,TinyDependency,TinySelectiveIssue,TinyLoadQueueMemoryBackend,TinyDualReplaySafeLoadUnit}.scala`，`src/main/scala/aethercore/soc/AetherCoreV2FpgaSoC.scala`。对“有无 C、LQ、FP”等结论应总是注明 **配置、代码分支和验收负载**。

## 2. 比缺指令更严重的架构风险（按先后因果排序）

### P0 — 指令生命周期与所有权迁移未完成

目标不能是把 `TinyRobGeometry.Entries` 从 4 改 64。现有 `TinyRob` 导出的 `window` 和 `physicalWindow` 包含整条 BackendUop，`TinySelectiveIssue` 只为四槽 age scan 设计，扩展会引入宽 MUX、广播扇出和寄存器读端口压力。**必须先冻结物理寄存器/生产者/ROB/事务四种身份的归属与生命周期**，再构建有限的调度视图。

- ROB 唯一拥有程序顺序、异常、恢复切点与提交许可；
- speculative RAT/free list/PRF 独立于 ROB，保证重命名表可恢复、旧 PRF 最多释放一次；
- IQ 只拥有等待发射的紧凑项，不能复制两份可变的架构状态；
- 完成口携带完整 epoch/producer/ROB 身份；所有被 kill 的迟到响应必须被拒绝；
- 物理外存响应使用独立 txnId/MSHR 生命周期，不把 ROB generation 当永久唯一事务号。

**禁止**在旧五级流水线 stage-ownership 上叠一套 ROB，也不能把现有四槽 scoreboard 改名为 PRF 就算完成重命名。

### P0 — 双译码 / 双退休是协议问题，不是改两处数字

同拍两条指令带来：
- 译码组内 RAW/WAW，分配必须对 ROB/IQ/PRF/LSQ 资源进行**原子预留**，有任何资源不足就不得留下“半条指令”；
- 两条退休的顺序：先 old 再 young；若第二条异常，第一条可按架构条件退休而第二条不得有不可撤销副作用；若第一条异常，第二条不能退休；
- Branch mispredict、异常、WFI/CSR/FP flags、Fence、Store 提交与中断在同拍的全序优先级；
- 物理寄存器释放、CSR/fflags 修改和内存副作用必须与退休边界完全一致。

必须在代码编写前给出 **事件优先级表 + 不变量 + 定向对撞测试矩阵**；不能等 Linux 第一次出错才补。

### P0 — 恢复协议仍偏向 tiny/head 模式

现有恢复接口与部分结构源于 head-specialized 流程，最终双发射 + 非头部 Branch 需要 younger-only ROB squash、RAT 修复、kill/epoch、未完成 LOAD/DIV/FPU 应答处理。ROB64 下“顺序重建 RAT”可能带来过长恢复延迟，需要在 FPGA 布线成本和 checkpoint/replay 方案间明确设计。建议先用小窗口执行**非头分支故障注入**，再决定恢复实现，而不是先指定 checkpoint 个数。

### P0 — 访存一致性与异常副作用边界

当前双槽普通 Load 是可验证的过渡实现，不能把它认作成熟 LSQ。
- 按年龄记录旧 Store 地址“未知/已知”状态；未知旧 Store 必须阻挡危险年轻 Load；
- 已知不相交才允许更年轻读；重叠完整且数据就绪才安全转发；部分重叠/设备事务初期明确等待；
- Store 数据可早准备，但只有 Commit permit 才能外部可见；LR/SC、AMO、MMIO 必须遵循 ordering/PMA；
- PTW 必须与脏 D-cache 页表内容一致；SFENCE/FENCE.I、TLB/cache 失效域和 DMA 非一致策略需可验证；
- `TinyLoadQueueMemoryBackend` 对父类接口的 last-connect 覆盖属于已通过 tiny 验证的实现技巧，不是适合无限扩展到大 LSQ 的所有权架构。产品版优先显式组合/策略接口，不再层层子类覆盖。

### P1 — RV64GC 是建筑状态闭环

RVC 机器态 PASS 不能证明跨页指令 + Sv39/PMP + Linux 的组合。LP64D 不是切换 `-mabi` 就算支持：要有 FPR、FCSR、FS Dirty/Clean、NaN boxing、精确 fflags、上下文切换与用户态进程证据。退休语义、异常、调试/参考模型从一开始必须兼容 FP；可以延迟 FPU 计算单元的实现，不可延迟 FPU **架构状态归属**的设计。

### P1 — FPGA 物理代价应前置进入架构冻结

`docs/AETHERSOC_V0_FREEZE.md` 的 ECP5/Yosys 代理曾显示 96,718 LUT4 和 18,705 FF；这 **不是** Artix-7 资源估算，也不是板级 Fmax。它提醒我们：从 ROB4 到 ROB64/PRF/LSQ 绝非免费。需要先选候选器件和时钟目标，再给 PRF、IQ wakeup-select、ROB 退休端口、Cache/DDR CDC 各自独立面积/时序预算。不能先写大核心再祈求频率可收敛。

## 3. 要保留和要隔离的机制

**优先继承**（有证据，但必须按目标配置复验）：
- ISA/CSR/PMP/Sv39 语义、异常编码、RVC 分解和 XLEN 处理；
- `AetherMemRequest/Response` 与外部 AXI 隔离、TxnId、PMA 属性；
- TLB/PTW 翻译与权限规则、BootROM、PLIC、ACLINT、UART、DT、Linux/OpenSBI 固件及验证脚本；
- 仓库里的定向测试和逐退休/内存证据。

**不可直接放大**：
- ROB4 的全宽 `window`、单端口退休寄存器写回；
- 单 launch / 单 completion 的架构假设；
- scoreboard/ROB-backed value 替代大窗口 PRF；
- 父类后端叠加 last-connect 作为通用内存调度扩展方式；
- 仅用完整运行 digest 代替逐指令 retirement trace 或内存顺序验证；
- 认为 v0 的 Linux PASS 可自动继承到双解码双退休版本。

## 4. 冻结顺序：不是先补功能，也不是先把所有硬件都改完

| Gate | 一次交付物 | 强制验收 | 允许下一步的条件 |
| --- | --- | --- | --- |
| **G0: 产品架构身份和状态所有权** | 数据流图、所有权图、五类 ID 生命周期、ISA/ABI/CSR/Commit/LSQ/FP/AXI 接口、冲突优先级表、三档实现 Profile | 文档可映射到真实模块，契约测试覆盖资源原子分配、kill、恢复、精确异常 | 无双状态所有者、无未定义并发事件 |
| **G1: 同源最小乱序参数档** | 单一产品核心骨架：rename/ROB/IQ/PRF/Commit 均存在，先 1 decode/issue/retire 小窗口；不建独立顺序产品核 | 裸机逐退休对照 Spike，异常/中断/kill/重复 generation/一组并发用例通过 | 数据流正确而非靠功能绕过 |
| **G2: 控制流与并发端口** | 同源 2 decode/2 retire，分支预测与非头恢复，完整 completion arbitration | RAW/WAW/同拍 retire-trap/branch-kill/late response 的定向 + 随机差分 | 没有不可撤销的错误路径状态 |
| **G3: 访存和 RV64GC** | 明确 SQ/LSQ、store forwarding、安全 younger Load、PTW-cache 协作、F/D 状态与计算、C+Sv39 Linux profile | 原子/线程/futex、页故障和 FP 指令及上下文切换；固定软件哈希 | GCC `-march=rv64gc -mabi=lp64d` 实际程序正确 |
| **G4: SoC 到实体 FPGA** | board clock/reset/PLL、DDR controller、真实 AXI/timing/约束、SD 或持久介质、UART/启动链 | Bitstream、资源/Fmax/DDR 校准、OpenSBI、Linux PID1、串口登录及持久化文件系统 | 真实器件重复上电冷启动 PASS |
| **G5: 发行版和性能** | Debian riscv64、动态链接、线程、浮点、包管理；优化后的 named presets | 端到端任务时间 + IPC/cycles + Fmax/LUT/BRAM/DSP + 结果校验 | 满足产品功能与物理资源预算 |

**G0 与现有 v0 板卡准备可以并行**，但正式 CPU RTL 改动不应越过 G0；G1 的“参数缩小”不能关闭 ROB、精确异常、重命名回滚、Store commit 许可这些根本机制。

## 5. 下一个真正值得做的批次

### A. 一次冻结 G0，而不是反复小批整理
1. 从生产 RTL 的真实 `frontend -> decode -> dispatch -> rename/dependency -> issue -> execute -> completion -> ROB/Commit -> AetherMem/AXI` 画现状/目标**两张**数据流图；
2. 分出核心、MMU/Cache、SoC/Board、software target 的类型与所有权；
3. 对 ROB token、producer tag、PRF tag、memory txn id、branch epoch 和 FP flags 定义创建/保持/销毁；
4. 一次写出 2-wide dispatch、2-wide retire、squash、store and FP side effect 的事件优先级；
5. 固定性能比较和 FPGA PPA 的同目标器件/同软件身份，避免“参考 Nax/XiangShan 的面积即自己结果”。

### B. 两条真正有意义的工程主线，不互相卡住
- **CPU 产品架构线：** G0→G1，从已验证语义复用起步，建立单一可退化 OoO 产品实现。
- **现有 v0 FPGA 板卡线：** 选择物理器件、DDR IP、板级时钟/复位、约束、DDR 测试与第一字节 UART；这些已有 AXI/SoC 参考可以提前试，不等最终 ROB64。

## 6. 禁止再次跑偏的门槛

- GSIM 优化已收尾，不作为核心路线前置条件；新的优化需有真实瓶颈和单独预算。
- 不以“架构完整”要求一次性实现整个 ROB64/IQ32/PRF/FPU；先明确所有权与接口，再用命名配置逐档验收。
- 不以“Linux 能启动”代替精确异常、DMA/页表一致性和多进程 LP64D 语义。
- 不把“已有 v0 CPU”与产品文档里尚未实现的目标 CPU 混成同一验收结果。
- 没有实际板卡与 P&R，不声明 FPGA Fmax，也不从 ECP5 代理结果估算 Artix-7 占用。
- 重要事实以固定 commit、RTL 文件、原始运行证据为准；后续如果主分支改动，应更新本审查的基线。

## 7. 可核查来源

- `docs/AETHERSOC_V0_FREEZE.md`, `docs/ROADMAP.md`
- `docs/AETHERCORE_V2_ARCHITECTURE_PHILOSOPHY.md`
- `docs/AETHERCORE_V2_POST_F7_ARCHITECTURE_AUDIT.md`
- `docs/AETHERCORE_V2_P8_3_COMPLETION_DECISION.md`
- `docs/AETHERCORE_V2_P8_4_M1_MEMORY_LIFETIME_STATUS.md`
- `docs/AETHERCORE_V2_SUPPORTED_ARCHITECTURAL_PROFILES.md`
- `docs/AETHERCORE_V2_ABI_PROFILE_OWNERSHIP.md`
- `src/main/scala/aethercore/core/v2/TinyPagedCore.scala`
- `src/main/scala/aethercore/core/v2/TinyRobCommit.scala`
- `src/main/scala/aethercore/core/v2/TinyDependency.scala`
- `src/main/scala/aethercore/core/v2/TinySelectiveIssue.scala`
- `src/main/scala/aethercore/core/v2/TinyLoadQueueMemoryBackend.scala`
- `src/main/scala/aethercore/core/v2/TinyDualReplaySafeLoadUnit.scala`
- `src/main/scala/aethercore/soc/AetherCoreV2FpgaSoC.scala`
- 产品设计输入《RV64 FPGA Linux 最终产品目标与系统方案 v1.1》（2026-11-02，用户提供，非仓库基线）
