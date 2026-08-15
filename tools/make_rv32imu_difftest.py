#!/usr/bin/env python3
"""Generate the RV32IMU DiffTest adapter from the shared RV32 adapter.

NEMU remains authoritative for ordinary instructions and memory. The frozen
regcpy ABI does not expose privilege CSRs, so this adapter independently shadows
M/U privilege transitions, Zicsr, synchronous traps and MRET. mtime is a
free-running platform input; immediately before NEMU executes an mtime load,
only the bytes observed by the DUT are copied into NEMU's passive MMIO page.
"""

from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, needle: str, replacement: str, label: str) -> str:
    count = text.count(needle)
    if count != 1:
        raise SystemExit(f"expected exactly one {label} insertion point, found {count}")
    return text.replace(needle, replacement, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    text = args.source.read_text(encoding="utf-8")

    text = replace_once(
        text,
        'constexpr std::uint32_t kMmioBase = 0x10000000U;\n'
        'constexpr std::size_t kMmioSize = 4096;\n'
        'char kMmioName[] = "aethercore-rv32-mmio";\n',
        'constexpr std::uint32_t kMmioBase = 0x10000000U;\n'
        'constexpr std::uint32_t kMtimePageBase = 0x0200b000U;\n'
        'constexpr std::uint32_t kMtimeAddress = 0x0200bff8U;\n'
        'constexpr std::size_t kMmioSize = 4096;\n'
        'char kMmioName[] = "aethercore-rv32-mmio";\n'
        'char kMtimeName[] = "aethercore-rv32imu-mtime";\n',
        "MMIO constants",
    )

    text = replace_once(
        text,
        'constexpr std::uint32_t kStoreAccessFault = 7U;\n'
        'constexpr std::uint32_t kEnvironmentCallFromM = 11U;\n',
        'constexpr std::uint32_t kStoreAccessFault = 7U;\n'
        'constexpr std::uint32_t kEnvironmentCallFromU = 8U;\n'
        'constexpr std::uint32_t kEnvironmentCallFromM = 11U;\n',
        "U ECALL cause",
    )

    text = replace_once(
        text,
        'constexpr std::uint32_t kRv32ImMisa = 0x40001100U;\n'
        'constexpr std::uint32_t kMstatusMie = 1U << 3;\n',
        'constexpr std::uint32_t kRv32ImuMisa = 0x40101100U;\n'
        'constexpr std::uint32_t kPrivilegeUser = 0U;\n'
        'constexpr std::uint32_t kPrivilegeMachine = 3U;\n'
        'constexpr std::uint32_t kMstatusMie = 1U << 3;\n',
        "RV32IMU privilege constants",
    )

    text = replace_once(
        text,
        'struct MachineState32 {\n'
        '  std::uint32_t mstatus = 0;\n',
        'struct MachineState32 {\n'
        '  std::uint32_t currentPrivilege = kPrivilegeMachine;\n'
        '  std::uint32_t mstatus = 0;\n',
        "current privilege state",
    )

    text = replace_once(
        text,
        '      addMmioMap_(kMmioName, kMmioBase, mmioSpace_, static_cast<int>(kMmioSize), nullptr);\n\n'
        '      if (!image_.empty()) memcpy_(resetPc_, image_.data(), image_.size(), kToRef);\n',
        '      addMmioMap_(kMmioName, kMmioBase, mmioSpace_, static_cast<int>(kMmioSize), nullptr);\n\n'
        '      mtimeSpace_ = newSpace_(static_cast<int>(kMmioSize));\n'
        '      if (mtimeSpace_ == nullptr) {\n'
        '        throw std::runtime_error("RV32IMU DiffTest failed to allocate mtime MMIO space");\n'
        '      }\n'
        '      std::memset(mtimeSpace_, 0, kMmioSize);\n'
        '      addMmioMap_(kMtimeName, kMtimePageBase, mtimeSpace_,\n'
        '                  static_cast<int>(kMmioSize), nullptr);\n\n'
        '      if (!image_.empty()) memcpy_(resetPc_, image_.data(), image_.size(), kToRef);\n',
        "mtime map",
    )

    text = replace_once(
        text,
        '      } else {\n'
        '        exec_(1);\n'
        '        regcpy_(&after, kToDut);\n'
        '      }\n',
        '      } else {\n'
        '        synchronizeTimerLoad(commit);\n'
        '        exec_(1);\n'
        '        regcpy_(&after, kToDut);\n'
        '      }\n',
        "ordinary NEMU execution",
    )

    text = replace_once(
        text,
        '      case kMisa: return kRv32ImMisa;\n',
        '      case kMisa: return kRv32ImuMisa;\n',
        "misa value",
    )

    text = replace_once(
        text,
        '      case kMstatus:\n'
        '        machine_.mstatus = (value & (kMstatusMie | kMstatusMpie)) | kMstatusMppMachine;\n'
        '        return;\n',
        '      case kMstatus: {\n'
        '        const auto requestedMpp = value & kMstatusMppMachine;\n'
        '        const auto legalMpp =\n'
        '            requestedMpp == 0U || requestedMpp == kMstatusMppMachine\n'
        '                ? requestedMpp\n'
        '                : kMstatusMppMachine;\n'
        '        machine_.mstatus = (value & (kMstatusMie | kMstatusMpie)) | legalMpp;\n'
        '        return;\n'
        '      }\n',
        "mstatus WARL write",
    )

    text = replace_once(
        text,
        '    const std::uint32_t address = instruction >> 20;\n\n'
        '    if (operation == 0) {\n',
        '    const std::uint32_t address = instruction >> 20;\n'
        '    const std::uint32_t requiredPrivilege = (address >> 8) & 0x3U;\n\n'
        '    if (machine_.currentPrivilege < requiredPrivilege) {\n'
        '      fail("Zicsr shadow received a normal event below CSR privilege for " +\n'
        '           hex32(address));\n'
        '    }\n\n'
        '    if (operation == 0) {\n',
        "CSR privilege check",
    )

    text = replace_once(
        text,
        '  NemuState32 executeMret(const NemuState32& before, const DifftestCommit& commit) {\n'
        '    if (commit.inst != kMret || commit.rdWrite || commit.memValid) {\n'
        '      fail("MRET shadow received an invalid architectural event");\n'
        '    }\n\n'
        '    machine_.mstatus =\n'
        '        (machine_.mstatus & ~kMstatusTrapMask) |\n'
        '        ((machine_.mstatus & kMstatusMpie) ? kMstatusMie : 0U) |\n'
        '        kMstatusMpie |\n'
        '        kMstatusMppMachine;\n\n'
        '    NemuState32 after = before;\n'
        '    after.pc = machine_.mepc;\n'
        '    after.gpr[0] = 0;\n'
        '    return after;\n'
        '  }\n',
        '  NemuState32 executeMret(const NemuState32& before, const DifftestCommit& commit) {\n'
        '    if (commit.inst != kMret || commit.rdWrite || commit.memValid ||\n'
        '        machine_.currentPrivilege != kPrivilegeMachine) {\n'
        '      fail("MRET shadow received an invalid architectural event");\n'
        '    }\n\n'
        '    const auto targetPrivilege = (machine_.mstatus >> 11) & 0x3U;\n'
        '    if (targetPrivilege != kPrivilegeUser && targetPrivilege != kPrivilegeMachine) {\n'
        '      fail("MRET shadow selected an unsupported privilege");\n'
        '    }\n'
        '    machine_.mstatus =\n'
        '        (machine_.mstatus & ~kMstatusTrapMask) |\n'
        '        ((machine_.mstatus & kMstatusMpie) ? kMstatusMie : 0U) |\n'
        '        kMstatusMpie;\n'
        '    machine_.currentPrivilege = targetPrivilege;\n\n'
        '    NemuState32 after = before;\n'
        '    after.pc = machine_.mepc;\n'
        '    after.gpr[0] = 0;\n'
        '    return after;\n'
        '  }\n',
        "MRET privilege transition",
    )

    text = replace_once(
        text,
        '      case kEnvironmentCallFromM:\n'
        '        if (commit.inst != kEcall || instructionAt(pc, commit.instBytes) != kEcall || value != 0) {\n'
        '          fail("M-mode ECALL trap metadata is inconsistent");\n'
        '        }\n'
        '        return;\n',
        '      case kEnvironmentCallFromU:\n'
        '        if (machine_.currentPrivilege != kPrivilegeUser || commit.inst != kEcall ||\n'
        '            instructionAt(pc, commit.instBytes) != kEcall || value != 0) {\n'
        '          fail("U-mode ECALL trap metadata is inconsistent");\n'
        '        }\n'
        '        return;\n\n'
        '      case kEnvironmentCallFromM:\n'
        '        if (machine_.currentPrivilege != kPrivilegeMachine || commit.inst != kEcall ||\n'
        '            instructionAt(pc, commit.instBytes) != kEcall || value != 0) {\n'
        '          fail("M-mode ECALL trap metadata is inconsistent");\n'
        '        }\n'
        '        return;\n',
        "ECALL privilege validation",
    )

    text = replace_once(
        text,
        '    machine_.mstatus =\n'
        '        (machine_.mstatus & ~kMstatusTrapMask) |\n'
        '        ((machine_.mstatus & kMstatusMie) ? kMstatusMpie : 0U) |\n'
        '        kMstatusMppMachine;\n'
        '    machine_.mepc = pc & ~std::uint32_t{3};\n',
        '    machine_.mstatus =\n'
        '        (machine_.mstatus & ~kMstatusTrapMask) |\n'
        '        ((machine_.mstatus & kMstatusMie) ? kMstatusMpie : 0U) |\n'
        '        (machine_.currentPrivilege << 11);\n'
        '    machine_.currentPrivilege = kPrivilegeMachine;\n'
        '    machine_.mepc = pc & ~std::uint32_t{3};\n',
        "trap privilege stacking",
    )

    text = replace_once(
        text,
        '  std::uint32_t instructionAt(std::uint32_t pc, std::uint8_t bytes) const {\n',
        '  void synchronizeTimerLoad(const DifftestCommit& commit) {\n'
        '    if (!commit.memValid || commit.memWrite) return;\n'
        '    const auto address = checkedAddress(commit.memAddr, "mtime Load address");\n'
        '    const auto funct3 = (commit.inst >> 12) & 0x7U;\n'
        '    std::size_t size = 0;\n'
        '    switch (funct3) {\n'
        '      case 0:\n'
        '      case 4: size = 1; break;\n'
        '      case 1:\n'
        '      case 5: size = 2; break;\n'
        '      case 2: size = 4; break;\n'
        '      default: return;\n'
        '    }\n'
        '    if (address < kMtimeAddress ||\n'
        '        static_cast<std::uint64_t>(address - kMtimeAddress) + size > 8U) return;\n'
        '    const auto offset = address - kMtimePageBase;\n'
        '    const auto value = static_cast<std::uint32_t>(commit.rdData);\n'
        '    for (std::size_t byte = 0; byte < size; ++byte) {\n'
        '      mtimeSpace_[offset + byte] = static_cast<std::uint8_t>(value >> (byte * 8));\n'
        '    }\n'
        '  }\n\n'
        '  std::uint32_t instructionAt(std::uint32_t pc, std::uint8_t bytes) const {\n',
        "mtime load synchronization",
    )

    text = replace_once(
        text,
        '  std::uint8_t* mmioSpace_ = nullptr;\n',
        '  std::uint8_t* mmioSpace_ = nullptr;\n'
        '  std::uint8_t* mtimeSpace_ = nullptr;\n',
        "mtime storage",
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text, encoding="utf-8")
    print(f"wrote RV32IMU DiffTest adapter: {args.output}")


if __name__ == "__main__":
    main()
