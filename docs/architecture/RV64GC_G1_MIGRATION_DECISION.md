# G0 收口 / G1 落地决策：怎么改，哪些保留，什么时候停

**技术方向已选，尚待 G0 评审冻结；不是产品核 RTL 实现或 Linux 通过证明。**  
基线：`main@7ff35c6`，产品目标 RV64GC/LP64D、2 decode / 2 retire、FPGA + Linux / Debian。  
机器清单：[rv64gc_g1_migration_map.json](rv64gc_g1_migration_map.json)。  
执行语义示例：[product_g1_rename_reference.py](../../tests_py/product_g1_rename_reference.py)（纯 Python 参考模型，非硬件）。  
独立 PR：#379；已通过 Linux 的 AetherSoC v0 属于保留验证参考。

> **FPGA Debian 产品目标修订（参照 NaxRiscv）：** [NaxRiscv 对标与真实 FPGA Debian 验收](RV64GC_NAXRISCV_FPGA_DEBIAN_ALIGNMENT.md)。G1 Small 是乱序正确性实验档，不是最终系统产品。C/F/D、LP64D、Sv39、FCSR/FS 和持久化系统必须在产品验收前完成，**不要求先把 ROB 扩到 64**。Rename 的 reverse-undo 是目前参考实现的候选策略，不排除 Nax 的 commit-reschedule dirty-mask dual RAT 方法；对外 RecoveryCut 不变。

## 0. 设计结论：在现有代码的真实边界上动刀

今天的主路径是：

```text
TinyPagedCore
  ├─ InstructionFetchAdapter / RVC parcel / TinySemanticDecode
  └─ TinyLoadQueueMemoryBackend extends TinyMemoryBackend
        ├─ TinyDependencyBackend
        │    ├─ TinyRob(ROB4, retire1, head-recovery)
        │    ├─ TinyDependencyState(rename Mem(32, ProducerTag), ROB-value)
        │    ├─ V2Commit
        │    └─ RegisterFile
        ├─ TinyPhysicalSelectiveComputeIssue -> TinySelectiveExecutionCluster
        ├─ TinyPhysicalLoadQueueIssue -> TinyDualReplaySafeLoadUnit(2 slots)
        ├─ TinyBlockingLsu(exact-head Store/SC/AMO)
        ├─ TinyCompletionArbiter(3 classes, accept1)
        └─ MachineCsrFile / translation / PMP / AetherMem
  -> AetherCoreV2Complex(cache) -> AetherSoCPlatformFabric -> AXI / UART / PLIC / ACLINT
```

**这个实现是真实可工作的有限选择性乱序核；不是“没写 CPU”**。
但四项 ROB 的宽存储投影、`rename Mem(32, ProducerTag)`、ROB 内存放值、
Branch 和异常只允许 head recovery，依赖同一 ROB index/generation 的
`RobToken / ProducerTag / ValueRef`，都是有限窗口的具体实现。
另外 `TinyLoadQueueMemoryBackend` 通过继承和后续连线覆盖父级访存策略，
不是未来 SQ/LSQ16 的合适实现边界。

**改变目标应是实现组织，不是修改已有成功运行的 ISA 语义。**
不能 `TinyRobGeometry.Entries=64` 然后一路打补丁。

## 1. D06 代码继承与目录边界：G1 选择

建立 **一个** `aethercore.core.product` 产品核实现，G1/G2/G3 只调整受约束的配置。
保留 `aethercore.core.v2` 的原始状态所有者和 AetherSoC v0 确认的 CI/固件链。
既不复制整个 Tiny 后端，也不在 v2 类上继续通过 `extends` 或 `last-connect`
叠加产品功能。

```text
src/main/scala/aethercore/
  common/                  # 复用公共基本运算/异常/CommitTrace
  config/                  # IsaConfig/SoftwareTarget + ProductCoreConfig（独立能力声明）
  core/
    Decoder.scala          # 既有 ISA 解码实现，提取到一个语义所有者
    semantic/
      SemanticDecode.scala # 新：backend-neutral decode/exception/CSR semantics
    v2/                    # 保留：TinyPagedCore 等既有验证实现
    product/
      ProductCoreConfig.scala / ProductTypes.scala
      ProductFrontend.scala / ProductRename.scala / ProductPrf.scala
      ProductRob.scala / ProductIssueQueue.scala / ProductExecution.scala
      ProductRecovery.scala / ProductCommit.scala
      ProductLsq.scala / ProductMmuAdapter.scala / ProductMemoryTransport.scala
      ProductCore.scala
  memory/AetherMemLink.scala  # 原有独立核心-物理内存合同
  soc/
    AetherCoreV2Complex.scala / AetherCoreV2FpgaSoC.scala   # do not modify v0
    ProductCoreComplex.scala / ProductFpgaSoC.scala         # G1 通过后 opt-in
```

这些新文件名是**目标组织，不是当前已有源码**。真正复用时：
- **只提取一次** `Decoder`、`TinySemanticDecode` 中的 backend-neutral 语义；
  同一共享 `SemanticDecode` 同时供旧 v2 和新产品 adapter 消费，
  避免改动前端 C/ISA 规则出现双轨不一致。
  `TinySemanticDecode` 的 `RobDispatch` 包装保留为兼容入口。
- `TinyRobCommit`、`TinyDependency`、`TinyPhysicalIssue`、
  `TinyMemoryBackend`、`TinyLoadQueueMemoryBackend` 为**产品后端重写**；
  保留已有实现作差分依据，**不可**复制其宽窗口算法或继承父类来扩展。
- 已有 `MachineCsrFile`、`TranslationUnit`、`PmpChecker`、
  `AetherMemRequest/Response` 和 SoC AXI/外设语义属于**可复用接口**，
  但必须新增明确适配器，防止恢复时 CSR/访存失去单一所有者。
- F/D 不要通过放宽现有 `CoreConfig.require` 伪装支持；
  产品 `ProductCoreConfig` 初期只允许已真正实现的 I/M/A + M/S/U/Sv39。
  LP64D 在 FPU、FCSR、Linux 上下文切换与软件回归完成前不得开放。

## 2. D02 Rename/PRF/恢复：G1 具体选型

**G1 使用显式物理寄存器重命名（PRF），两份 RAT 和逆向 undo log；暂不使用每分支整份 RAT 快照。**

```text
architectural x0..x31
  x0 -> phys0（永远为零）
  x1..x31 -> 初始 phys1..phys31
FreeList = phys32..phys47（G1 共 48 个整数物理寄存器）
                   |
         Speculative RAT[32]
            |            |
    src physical tags    allocate new pdst + old pdst
            |            |
      IQ / execution     ROB record + undo metadata
            |            |
      PRF read/write     ROB in-order retirement
            |            |
       bypass/wakeup   Committed RAT[32]
                             |
                  release stale pdst ON COMMIT
```

**G1 宽度/端口：** 1 decode、1 rename/dispatch、1 issue、1 retire；
ROB8 / IQ4 / LoadQ2 / StoreQ2；整数 PRF48，初始 2R/1W，
FP PRF/FPU 暂不启用。这是为**先证明所有权与精确恢复**选择的参数，
并非最终双宽产品物理布局。

- 分配：预检 ROB/IQ/LSQ/FreeList 所有资源后，一次接受整个有效组。
  多路（G2）先按 lane0、lane1 顺序重命名，lane1 可直接看到 lane0 新映射，
  保证组内 RAW/WAW、x0 语义。
- 写回：`RobToken` 必须指向仍然活着的同一生命周期，
  `ProducerTag` 与 `ValueRef` 也必须匹配；否则不得写 PRF 或唤醒 IQ。
  外部 AetherMem `txnId` 不能使用 ROB token 的有限代号替代。
- 退休：普通指令经精确 Commit 更新 `Committed RAT`。
  **旧 pdst 在覆盖它的指令提交后才释放一次**，不是旧值生成时释放，
  也不是年轻 uOp 被分配时就释放。
  旧映射的物理编号可能是初始的 phys1..phys31；这些编号提交后可以重新进入 FreeList，**只有 phys0 永远保留**。
- Branch 恢复：验证任意年龄分支的 token，ROB 保留分支及所有 older，
  只剔除 younger。Rename 停止分配/退休，按照 younger 的**逆年龄顺序**
  恢复 spec RAT 的旧映射，并返还被 squash 的新 pdst。
  先释放外部请求在 transport 上的逻辑所有权，迟到应答必须安全 drain。
- Trap / xRET / interrupt：G1 在精确架构边界处理，停掉新的 speculative dispatch；
  在提交可提交的 older 前缀后，撤销所有剩余未提交的重命名，再进入新上下文。
- 同拍竞争：recovery cut/资源撤销优先于新组 grant；
  Commit 不可观察 partial RAT 更新；需要 G1 Chisel assertions 证明。

**为什么 G1 不用 branch 快照？** BOOM 支持分支快照/RAT + AllocationList
快速恢复，也支持更保守的 rollback 路线。两者要付出不同的物理状态与恢复延迟。
我们的前提是 FPGA 实体资源、Fmax 和新核 G1 尚未测量，
先选择简化多写端口的**可正确恢复的多周期撤销**。
G2 若确认分支恢复拖累 IPC，可在相同 `RecoveryCut` 合同下替换为 checkpoint；
不能再重做 ROB/PRF/异常接口。

这个取舍不是理论证明。G1 必须通过功能模型、Chisel 定向故障注入、
逐退休 Spike 差分和真实 FPGA PPA，才能称为“完成”。

## 3. PRF 容量发现：纠正旧 G0 的 ROB64 / PRF64 配置

整数有 32 个架构寄存器映射。若每条 ROB 内在途指令都写一个 rd，
单为了不让 FreeList 比 ROB 容量更早耗尽，建议候选物理容量满足：

`integer physical registers >= 32 + max concurrent uncommitted integer destinations`

于是：
- G1 ROB8、PRF48：16 个可分配 spare，满足 G1。
- G2 ROB16、PRF64：32 个 spare，足够 G2 双路分配的初期演示。
- **G3 ROB64、PRF96**：64 个 spare，取代旧草案的 **PRF64**。

这个不等式是**无额外重命名容量阻塞的设计目标**，
而非 RV64 ISA 必须满足的下限。较小的 PRF 可以靠背压正确运行，
但不能宣传为“满 64 项 ROB 都可接收普通写 rd 指令”。
最终物理位宽、banking、读取口、FP PRF 仍由目标 FPGA 的 PPA 决定。

## 4. 两个大代码批次 + 一次受控接入，不拆成碎修

| 批次 | 一次真正完成的大类 | 这批必须一起修改的范围 | 完成凭证 |
| --- | --- | --- | --- |
| **S 语义与接口** | 把 ISA/CSR/异常的 backend-neutral 语义从 v2 里抽出来 | Decoder + TinySemanticDecode + FoundationTypes 的语义类型；v2 兼容层；ProductTypes/CoreConfig | v2 decode/OS 不回退；新旧对同一指令和 fault 的语义一致 |
| **B 完整 G1 后端** | 实现 Rename→PRF→ROB→IQ→执行→Completion→Commit→Recovery→小型 LSQ 的**闭环** | 对应全部 Product* 模块和唯一 owner/ID 类型；每种 stale/kill/exception 路径一次性完成 | bare 逐退休 Spike、ROB/FreeList/scoreboard property tests、kill/回滚/无 speculative Store |
| **I 受控集成** | G1 产品核作为**可选** CoreComplex 接入 AetherMem 和 v0 SoC 外设 | 独立 ProductCoreComplex / ProductFpgaSoC，保持 AetherCoreV2Complex 的默认构建不动 | UART/Timer/PLIC/AXI/BootROM；可选 OpenSBI，再 Linux；所有结果独立标明新/旧核 |

> 注意：批次 S 完成以后，新产品 core 的 Decode 不应该再 import
> `TinyRobGeometry` 或 `TinyDependencyBackend`；批次 B 不应只提交一个
> 孤立的 Rename RAM 而没有 FreeList/Commit/Recovery，避免新旧两套定义共存。

## 5. 必须处理的真实控制面风险

1. **ROB + age**：读 `TinyRobCommit.scala`，只读 `physicalWindow` 和
   `window` 的整条 uOp 是四槽设计的观测优化，不能当 64 项 IQ；
   产品 IQ 必须存紧凑 tag/ready 元数据。
2. **分支早恢复**：今天 `TinyRob` 的 `recoveryMatches`
   明确要求 `completionIndex === head`；产品必须允许非头 branch
   产生 *younger-only cut*，同时尊重 older trap 优先权。
3. **提交权限**：`V2Commit` 只有一个 `Decoupled` retire；
   新 Commit 双路必须支持 `[normal, fault]` 仅旧指令先退休，
   且 CSR/FFLAGS/SQ 的外部副作用不能越过 first-fault。
4. **内存祖先**：现有 `TinyLoadQueueMemoryBackend extends TinyMemoryBackend`
   对 `lsu.io.request.valid` 等连线的 override 不能再扩成 LSQ16；
   产品 LSU 的 SQ/LoadQ、PMA、PTW 和 TxnId 有独立组合式 owner。
5. **FP state**：FPR/FS Dirty/fflags/FCSR 从产品设计 G0 就有单一 Commit owner，
   但不允许 G1 没有 FPU 时对外报 `rv64gc/lp64d`。
6. **边界保持**：AetherMem 是“内核物理内存请求”，不是 AXI 信号；
   SoC Fabric 管 PMA/MMIO；Cache/PTW 的 PTE 读取一致性另外验收。

## 6. exit criteria：什么时候 G0 算收，什么时候真的开始 G1

G0 单独运行 **Fast Gate**、机器可读契约和 Rename 参考模型测试，不使用运行时性能收益替代协议验收。

**G0 技术方案收口的条件（此次评审交付）**
- [x] 真实 v2 源码每一条需改路径定位到具体模块；
- [x] D06 G1 产品包与共享语义提取的边界已选择；
- [x] D02 G1 的 RAT/PRF/FreeList/undo/Commit 生命周期已选择；
- [x] G1/G2/G3 命名配置和 PRF64→PRF96 候选修正；
- [x] 外部事务、精确异常、分支恢复、双宽冲突和 FP 所有权受机器契约约束；
- [ ] **维护者审查/采纳 D02 和 D06**；只有这个完成才标记 G0 正式冻结。
- [ ] 产品 RTL 的 Chisel 模拟/Spike 差分：属于后续 G1 验收，不得伪装为 G0 PASS。

**G1 完工条件**：真正生产 Chisel RTL 中存在唯一的 ProductCore
（1/1/1/1 档），能从 ELF/裸机跑到稳定退休，非法指令、同步 trap、
中断、kill/迟到 FU response、Store commit、访存与地址翻译均有运行证明。
不必一开始 Linux PID1，但不能用“编译通过”替代 Spike 差分正确性。

**停止反复设计的门槛**：若 G0 CI、迁移路径、D02/D06 owner 和恢复实例
都自洽，**下一轮直接进入批次 S+B 的产品代码**，不再继续抽象审计。
仅在证明 G1 不能实现当前合同（语义冲突/FPGA PPA 不可行）时回头修改 G0。
外部板级 DDR/PLL/真实 UART 可以沿 v0 独立推进，不被这条线卡住。

## S 批已进入真实代码实现（待 CI 资格验证）

共享架构语义不再属于 Tiny：新增 `core/semantic/SemanticTypes.scala` 和 `SemanticDecode.scala`，`TinySemanticDecode` 只处理 v2 RobDispatch/ExecutionClass，`core/v2/package.scala` 保持旧 API 的类型别名/枚举引用，`FoundationTypes` 只拥有 ROB/Producer/Value/执行结果等后台身份。**仅一份解码算法与语义类型定义**。包含旧 v2 源码合同、直接 SharedSemanticDecodeSpec、V2F7SemanticDecodeSpec 和 v2 ELF/OpenSBI elaboration 的回归；通过前不得将此项标记为 S 批 PASS。ISA/ABI 可配置能力仍不会凭此升级到 F/D/LP64D。

按 NaxRiscv 经验：Small 与 Debian ISA/ABI 是独立维度；S 完成后，下一批进入连通的 G1 Rename/PRF/ROB/IQ/Commit/Recovery/LSQ，而不是再新增另一套 Decoder。

### S 批配置实现（待测试验收）

`core/product/ProductCoreConfig.scala` 新增 **三根正交配置轴** 的真实 Scala 验证层：`ProductCoreGeometry` 负责 Decode/Dispatch/Issue/Retire/ROB/IQ/LQ/SQ/PRF 规模及端口检查；`SoftwareTarget` 独立描述工程 RV64IMA/LP64 与产品 RV64GC/LP64D；`PlatformConfig` 保留真实板级参数。含 `smallEngineering`、`smallDebianTarget`、`mediumDebianTarget`、`largeDebianTarget`，**均显式标为 rtlImplemented=false**。PRF 小于 32+ROB 只触发可预计的分配背压标记，不被错误判为 ISA 不合法，遵循 NaxRiscv 可配置实践。配置检查由 `ProductCoreConfigSpec` Scala 测试以及独立 Python 静态所有权测试承担；这不是 SmallOoO RTL 本体。

### G1 可综合后端第一落点（独立整数／分支切片；不能冒充完整 CPU）

`core/product/ProductSmallOoOBackend.scala` 新增 **真实 Chisel 硬件状态和 ready/valid 接口**：ROB8、IQ4、显式 SpecRAT/CommittedRAT、PRF48、FreeList、ROB/物理寄存器各自代际编号、out-of-order 完成与 oldest-only 退休、多周期 youngest-first 分支 rollback、精确同步异常全域 flush、被取消／复用的 Completion 拒绝。该模块接受共享 `DecodedInstruction`；Issue/Completion 由后续 FU 通过独立握手实现。前端、整数 FU、MMU/LSQ、特权、中断和 SoC 尚未接入，所以 `rtlImplemented=false` **暂不改变**。

`ProductSmallOoOBackendSpec` 为真实 Chisel 时序测试：年轻独立指令先完成但不能先退休、RAW PRF 就绪与恢复、非头部 Branch 错误预测与晚到 Completion、异常精确边界，以及对未实现的 Memory/System 指令 fail closed。它不是单纯的 JSON／Python 自洽检查。与 F7 共享语义和现有 v2 回归同门禁运行。**验收前不得宣称 G1 SmallOoO 已完整实现或 Linux 已经运行。**

后续一次闭环补齐：复用共享 ALU/乘除和分支运算（不得继承 Tiny 后端）、LSQ/MemoryTransport 独立所有权、Commit CSR/中断、Sv39/Cache/PTW，再做逐退休 Spike 差分，最后 opt-in ProductCoreComplex 接入 SoC。现在的整数后端是这个大批次真实硬件的首段，绝非 Debian 产品完成。

### G1 RV64I 执行闭环扩展（仍非完整 ProductCore）

ProductSmallOoOBackend 的 Issue／Completion 接口已经连到 `ProductIntegerBranchUnit`，形成 `ProductSmallIntegerCore` 的 **实际整数 I 子集 + Branch 运算 → tagged Completion → ROB/PRF → Commit**。ALU 使用原有 `aethercore.core.ALU`，新增 `withMulDiv=false` 的 Chisel **elaboration-time 参数**，在此整数 FU 中不生成乘除网络；已有 v1/v2 ALU 的默认 `withMulDiv=true` 保持不变。M 类指令（MUL/DIV/REM）、访存、CSR、Sv39 与 F/D 仍缺失或未连接，明确挂起/背压；SmallRV64GC 仍不可运行 Debian。

`ProductSmallIntegerCoreSpec` 将覆盖真正的 ADDI/RAW 寄存器流、BEQ 错预测、M 未实现门禁及内存 fail-closed；同时保留 `ALUSpec` 和 `RV64MALUSpec` 保护旧 ALU 算法。**只有 Chisel F7 门禁执行完成后，才能声称整数执行链路已验证。** 后续 G1 仍须增加独立迭代 M Unit、LSQ/SQ、特权/MMU/Caches、系统启动和 Spike 逐退休差分。
