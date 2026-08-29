#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, needle: str, replacement: str, label: str) -> str:
    count = text.count(needle)
    if count != 1:
        raise SystemExit(f"expected exactly one {label} insertion point, found {count}")
    return text.replace(needle, replacement, 1)


def make_runner(text: str) -> str:
    text = text.replace("VAetherCoreSimTop", "VAetherCoreRV32IMUPmpSimTop")
    text = replace_once(
        text,
        "  commit.exceptionValue = static_cast<std::uint64_t>(top.io_commit_exceptionValue);\n"
        "  return commit;",
        "  commit.exceptionValue = static_cast<std::uint64_t>(top.io_commit_exceptionValue);\n"
        "  commit.interrupt = top.io_commit_interrupt;\n"
        "  commit.interruptCause = static_cast<std::uint64_t>(top.io_commit_interruptCause);\n"
        "  commit.interruptPc = static_cast<std::uint64_t>(top.io_commit_interruptPc);\n"
        "  return commit;",
        "combined trap/interrupt metadata",
    )
    text = replace_once(
        text,
        '      if (difftest) std::cout << ", difftest=" << difftest->checkedCommits()'
        ' << ", zicsr-shadow=" << difftest->zicsrShadowSteps()'
        ' << ", trap-shadow=" << difftest->trapShadowSteps()'
        ' << ", mret-shadow=" << difftest->mretShadowSteps();',
        '      if (difftest) std::cout << ", difftest=" << difftest->checkedCommits()'
        ' << ", zicsr-shadow=" << difftest->zicsrShadowSteps()'
        ' << ", trap-shadow=" << difftest->trapShadowSteps()'
        ' << ", mret-shadow=" << difftest->mretShadowSteps()'
        ' << ", interrupt-shadow=" << difftest->interruptShadowSteps();',
        "combined DiffTest summary",
    )
    return text


def make_difftest(text: str) -> str:
    patches = (
        (
            "timer MMIO constants",
            '''constexpr std::uint32_t kMtimePageBase = 0x0200b000U;\nconstexpr std::uint32_t kMtimeAddress = 0x0200bff8U;\nconstexpr std::size_t kMmioSize = 4096;\nchar kMmioName[] = "aethercore-rv32-mmio";\nchar kMtimeName[] = "aethercore-rv32imu-mtime";\n''',
            '''constexpr std::uint32_t kMtimecmpPageBase = 0x02004000U;\nconstexpr std::uint32_t kMtimePageBase = 0x0200b000U;\nconstexpr std::uint32_t kMtimecmpAddress = 0x02004000U;\nconstexpr std::uint32_t kMtimeAddress = 0x0200bff8U;\nconstexpr std::size_t kMmioSize = 4096;\nchar kMmioName[] = "aethercore-rv32-mmio";\nchar kMtimecmpName[] = "aethercore-rv32imu-mtimecmp";\nchar kMtimeName[] = "aethercore-rv32imu-mtime";\n''',
        ),
        (
            "mie constant",
            '''constexpr std::uint32_t kMstatus = 0x300U;\nconstexpr std::uint32_t kMisa = 0x301U;\nconstexpr std::uint32_t kMtvec = 0x305U;\n''',
            '''constexpr std::uint32_t kMstatus = 0x300U;\nconstexpr std::uint32_t kMisa = 0x301U;\nconstexpr std::uint32_t kMie = 0x304U;\nconstexpr std::uint32_t kMtvec = 0x305U;\n''',
        ),
        (
            "mip constant",
            '''constexpr std::uint32_t kMcause = 0x342U;\nconstexpr std::uint32_t kMtval = 0x343U;\nconstexpr std::uint32_t kPmpcfg0 = 0x3a0U;\n''',
            '''constexpr std::uint32_t kMcause = 0x342U;\nconstexpr std::uint32_t kMtval = 0x343U;\nconstexpr std::uint32_t kMip = 0x344U;\nconstexpr std::uint32_t kPmpcfg0 = 0x3a0U;\n''',
        ),
        (
            "timer architecture constants",
            '''constexpr std::uint32_t kMstatusTrapMask =\n    kMstatusMie | kMstatusMpie | kMstatusMppMachine;\n''',
            '''constexpr std::uint32_t kMstatusTrapMask =\n    kMstatusMie | kMstatusMpie | kMstatusMppMachine;\nconstexpr std::uint32_t kMachineTimerMask = 1U << 7;\nconstexpr std::uint32_t kMachineTimerCause = 0x80000007U;\n''',
        ),
        (
            "machine mie",
            '''  std::uint32_t currentPrivilege = kPrivilegeMachine;\n  std::uint32_t mstatus = 0;\n  std::uint32_t mtvec = 0;\n''',
            '''  std::uint32_t currentPrivilege = kPrivilegeMachine;\n  std::uint32_t mstatus = 0;\n  std::uint32_t mie = 0;\n  std::uint32_t mtvec = 0;\n''',
        ),
        (
            "machine timer pending",
            '''  std::uint32_t pmpcfg0 = 0;\n  std::array<std::uint32_t, kPmpEntries> pmpaddr{};\n};\n''',
            '''  std::uint32_t pmpcfg0 = 0;\n  std::array<std::uint32_t, kPmpEntries> pmpaddr{};\n  bool timerPending = false;\n};\n''',
        ),
        (
            "mtimecmp map",
            '''      mtimeSpace_ = newSpace_(static_cast<int>(kMmioSize));\n''',
            '''      mtimecmpSpace_ = newSpace_(static_cast<int>(kMmioSize));\n      if (mtimecmpSpace_ == nullptr) {\n        throw std::runtime_error("RV32IMU DiffTest failed to allocate mtimecmp MMIO space");\n      }\n      std::memset(mtimecmpSpace_, 0, kMmioSize);\n      addMmioMap_(kMtimecmpName, kMtimecmpPageBase, mtimecmpSpace_,\n                  static_cast<int>(kMmioSize), nullptr);\n\n      mtimeSpace_ = newSpace_(static_cast<int>(kMmioSize));\n''',
        ),
        (
            "event exclusivity",
            '''  void check(const DifftestCommit& commit) {\n    const auto commitPc = checkedAddress(commit.pc, "commit PC");\n''',
            '''  void check(const DifftestCommit& commit) {\n    if (commit.exception && commit.interrupt) {\n      fail("one retirement event cannot be both synchronous and asynchronous");\n    }\n    const auto commitPc = checkedAddress(commit.pc, "commit PC");\n''',
        ),
        (
            "interrupt execution",
            '''    if (commit.memValid && commit.memWrite) compareStore(commit);\n\n    std::ostringstream line;\n''',
            '''    if (commit.memValid && commit.memWrite) {\n      compareStore(commit);\n      observeTimerStore(commit);\n    }\n\n    bool interruptStep = false;\n    if (commit.interrupt) {\n      validateInterrupt(after, commit);\n      after = executeInterrupt(after, commit);\n      regcpy_(&after, kToRef);\n      ++interruptShadowSteps_;\n      interruptStep = true;\n    }\n\n    std::ostringstream line;\n''',
        ),
        (
            "interrupt trace",
            '''    if (trapStep) {\n      line << " reference=trap-shadow cause="\n           << hex32(checkedAddress(commit.exceptionCause, "exception cause"))\n           << " value=" << hex32(checkedAddress(commit.exceptionValue, "exception value"));\n    }\n    line << " next=" << hex32(after.pc);\n''',
            '''    if (trapStep) {\n      line << " reference=trap-shadow cause="\n           << hex32(checkedAddress(commit.exceptionCause, "exception cause"))\n           << " value=" << hex32(checkedAddress(commit.exceptionValue, "exception value"));\n    }\n    if (interruptStep) {\n      line << " reference=timer-interrupt-shadow cause="\n           << hex32(checkedAddress(commit.interruptCause, "interrupt cause"))\n           << " epc=" << hex32(checkedAddress(commit.interruptPc, "interrupt PC"));\n    }\n    line << " next=" << hex32(after.pc);\n''',
        ),
        (
            "interrupt getter",
            '''  std::uint64_t trapShadowSteps() const { return trapShadowSteps_; }\n  std::uint64_t mretShadowSteps() const { return mretShadowSteps_; }\n\n private:\n''',
            '''  std::uint64_t trapShadowSteps() const { return trapShadowSteps_; }\n  std::uint64_t mretShadowSteps() const { return mretShadowSteps_; }\n  std::uint64_t interruptShadowSteps() const { return interruptShadowSteps_; }\n\n private:\n''',
        ),
        (
            "mie read",
            '''      case kMisa: return kRv32ImuMisa;\n      case kMtvec: return machine_.mtvec;\n''',
            '''      case kMisa: return kRv32ImuMisa;\n      case kMie: return machine_.mie;\n      case kMtvec: return machine_.mtvec;\n''',
        ),
        (
            "mip read",
            '''      case kMtval: return machine_.mtval;\n      case kPmpcfg0: return machine_.pmpcfg0;\n''',
            '''      case kMtval: return machine_.mtval;\n      case kMip: return machine_.timerPending ? kMachineTimerMask : 0U;\n      case kPmpcfg0: return machine_.pmpcfg0;\n''',
        ),
        (
            "mie writable",
            '''      case kMstatus:\n      case kMtvec:\n''',
            '''      case kMstatus:\n      case kMie:\n      case kMtvec:\n''',
        ),
        (
            "mip readonly",
            '''      case kMisa:\n        return false;\n''',
            '''      case kMisa:\n      case kMip:\n        return false;\n''',
        ),
        (
            "mie write",
            '''      case kMtvec:\n        machine_.mtvec = value & ~std::uint32_t{3};\n''',
            '''      case kMie:\n        machine_.mie = value & kMachineTimerMask;\n        return;\n      case kMtvec:\n        machine_.mtvec = value & ~std::uint32_t{3};\n''',
        ),
        (
            "mip write rejection",
            '''      case kMisa:\n        fail("Zicsr shadow attempted to write read-only misa");\n''',
            '''      case kMisa:\n      case kMip:\n        fail("Zicsr shadow attempted to write a read-only CSR");\n''',
        ),
        (
            "mapped store comparison",
            '''    const std::uint64_t mmioEnd = static_cast<std::uint64_t>(kMmioBase) + kMmioSize;\n    if (address >= kMmioBase && static_cast<std::uint64_t>(address) + referenceBytes.size() <= mmioEnd) {\n      std::memcpy(referenceBytes.data(), mmioSpace_ + (address - kMmioBase),\n                  referenceBytes.size());\n    } else {\n      memcpy_(address, referenceBytes.data(), referenceBytes.size(), kToDut);\n    }\n''',
            '''    if (auto* mapped = mappedPointer(address, referenceBytes.size())) {\n      std::memcpy(referenceBytes.data(), mapped, referenceBytes.size());\n    } else {\n      memcpy_(address, referenceBytes.data(), referenceBytes.size(), kToDut);\n    }\n''',
        ),
        (
            "mtimecmp member",
            '''  std::uint8_t* mmioSpace_ = nullptr;\n  std::uint8_t* mtimeSpace_ = nullptr;\n''',
            '''  std::uint8_t* mmioSpace_ = nullptr;\n  std::uint8_t* mtimecmpSpace_ = nullptr;\n  std::uint8_t* mtimeSpace_ = nullptr;\n''',
        ),
        (
            "interrupt counter member",
            '''  std::uint64_t trapShadowSteps_ = 0;\n  std::uint64_t mretShadowSteps_ = 0;\n};\n''',
            '''  std::uint64_t trapShadowSteps_ = 0;\n  std::uint64_t mretShadowSteps_ = 0;\n  std::uint64_t interruptShadowSteps_ = 0;\n};\n''',
        ),
        (
            "external interrupt getter",
            '''std::uint64_t NemuDifftest::mretShadowSteps() const { return impl_->mretShadowSteps(); }\n''',
            '''std::uint64_t NemuDifftest::mretShadowSteps() const { return impl_->mretShadowSteps(); }\nstd::uint64_t NemuDifftest::interruptShadowSteps() const {\n  return impl_->interruptShadowSteps();\n}\n''',
        ),
    )
    for label, needle, replacement in patches:
        text = replace_once(text, needle, replacement, label)

    helpers = '''  void validateInterrupt(const NemuState32& after, const DifftestCommit& commit) const {\n    const auto cause = checkedAddress(commit.interruptCause, "interrupt cause");\n    const auto epc = checkedAddress(commit.interruptPc, "interrupt PC");\n    if (cause != kMachineTimerCause) fail("unexpected machine interrupt cause " + hex32(cause));\n    if ((epc & 3U) != 0 || after.pc != epc) {\n      fail("machine timer interrupt EPC does not match the next architectural PC");\n    }\n    if ((machine_.mie & kMachineTimerMask) == 0) {\n      fail("machine timer interrupt was accepted while mie.MTIE was clear");\n    }\n    if (machine_.currentPrivilege == kPrivilegeMachine &&\n        (machine_.mstatus & kMstatusMie) == 0) {\n      fail("machine timer interrupt was accepted in M-mode while mstatus.MIE was clear");\n    }\n    if (!pmpAllows(epc, 4, false, true)) {\n      fail("machine timer interrupt replay PC is denied by the active PMP state");\n    }\n  }\n\n  NemuState32 executeInterrupt(const NemuState32& before, const DifftestCommit& commit) {\n    const auto epc = checkedAddress(commit.interruptPc, "interrupt PC");\n    const auto previousPrivilege = machine_.currentPrivilege;\n    machine_.timerPending = true;\n    machine_.mstatus =\n        (machine_.mstatus & ~kMstatusTrapMask) |\n        ((machine_.mstatus & kMstatusMie) ? kMstatusMpie : 0U) |\n        (previousPrivilege << 11);\n    machine_.currentPrivilege = kPrivilegeMachine;\n    machine_.mepc = epc & ~std::uint32_t{3};\n    machine_.mcause = kMachineTimerCause;\n    machine_.mtval = 0;\n    NemuState32 after = before;\n    after.pc = machine_.mtvec;\n    after.gpr[0] = 0;\n    return after;\n  }\n\n  std::uint8_t* mappedPointer(std::uint32_t address, std::size_t size) const {\n    const auto inPage = [&](std::uint32_t base) {\n      return address >= base &&\n             static_cast<std::uint64_t>(address - base) + size <= kMmioSize;\n    };\n    if (inPage(kMmioBase)) return mmioSpace_ + (address - kMmioBase);\n    if (inPage(kMtimecmpPageBase)) return mtimecmpSpace_ + (address - kMtimecmpPageBase);\n    if (inPage(kMtimePageBase)) return mtimeSpace_ + (address - kMtimePageBase);\n    return nullptr;\n  }\n\n  void observeTimerStore(const DifftestCommit& commit) {\n    const auto address = checkedAddress(commit.memAddr, "timer Store address");\n    if (address >= kMtimecmpAddress && address < kMtimecmpAddress + 8U) {\n      machine_.timerPending = false;\n    }\n  }\n\n'''
    return replace_once(
        text,
        "  void synchronizeTimerLoad(const DifftestCommit& commit) {\n",
        helpers + "  void synchronizeTimerLoad(const DifftestCommit& commit) {\n",
        "timer helper methods",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("runner", "difftest"))
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    text = args.source.read_text(encoding="utf-8")
    output = make_runner(text) if args.mode == "runner" else make_difftest(text)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output, encoding="utf-8")
    print(f"wrote isolated scheduler {args.mode}: {args.output}")


if __name__ == "__main__":
    main()
