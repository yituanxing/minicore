#!/usr/bin/env python3
"""Add an independent four-entry PMP shadow to the generated RV32IMU adapter."""

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
        'constexpr std::uint32_t kMtval = 0x343U;\n'
        'constexpr std::uint32_t kRv32ImuMisa = 0x40101100U;\n',
        'constexpr std::uint32_t kMtval = 0x343U;\n'
        'constexpr std::uint32_t kPmpcfg0 = 0x3a0U;\n'
        'constexpr std::uint32_t kPmpaddr0 = 0x3b0U;\n'
        'constexpr std::uint32_t kPmpEntries = 4U;\n'
        'constexpr std::uint32_t kPmpRead = 1U << 0;\n'
        'constexpr std::uint32_t kPmpWrite = 1U << 1;\n'
        'constexpr std::uint32_t kPmpExecute = 1U << 2;\n'
        'constexpr std::uint32_t kPmpAddressShift = 3U;\n'
        'constexpr std::uint32_t kPmpAddressMask = 3U << kPmpAddressShift;\n'
        'constexpr std::uint32_t kPmpTor = 1U;\n'
        'constexpr std::uint32_t kPmpNa4 = 2U;\n'
        'constexpr std::uint32_t kPmpNapot = 3U;\n'
        'constexpr std::uint32_t kPmpLock = 1U << 7;\n'
        'constexpr std::uint32_t kRv32ImuMisa = 0x40101100U;\n',
        "PMP constants",
    )

    text = replace_once(
        text,
        '  std::uint32_t mtval = 0;\n'
        '};\n',
        '  std::uint32_t mtval = 0;\n'
        '  std::uint32_t pmpcfg0 = 0;\n'
        '  std::array<std::uint32_t, kPmpEntries> pmpaddr{};\n'
        '};\n',
        "PMP machine state",
    )

    text = replace_once(
        text,
        '      if (imageInst != commit.rawInst) {\n'
        '        fail("DUT raw instruction " + hex32(commit.rawInst) + " differs from image instruction " +\n'
        '             hex32(imageInst) + " at pc=" + hex32(commitPc));\n'
        '      }\n\n'
        '      mretStep = commit.inst == kMret;\n',
        '      if (imageInst != commit.rawInst) {\n'
        '        fail("DUT raw instruction " + hex32(commit.rawInst) + " differs from image instruction " +\n'
        '             hex32(imageInst) + " at pc=" + hex32(commitPc));\n'
        '      }\n'
        '      validatePmpNormal(before, commit);\n\n'
        '      mretStep = commit.inst == kMret;\n',
        "normal PMP validation",
    )

    helpers = r'''  static std::uint8_t canonicalPmpConfigByte(std::uint8_t value) {
    const bool read = (value & kPmpRead) != 0;
    const bool write = read && (value & kPmpWrite) != 0;
    return static_cast<std::uint8_t>(
        (value & (kPmpExecute | kPmpAddressMask | kPmpLock)) |
        (read ? kPmpRead : 0U) | (write ? kPmpWrite : 0U));
  }

  std::uint8_t pmpConfig(unsigned entry) const {
    return static_cast<std::uint8_t>(machine_.pmpcfg0 >> (entry * 8));
  }

  void writePmpcfg0(std::uint32_t value) {
    for (unsigned entry = 0; entry < kPmpEntries; ++entry) {
      const auto oldConfig = pmpConfig(entry);
      if ((oldConfig & kPmpLock) != 0) continue;
      const auto newConfig = canonicalPmpConfigByte(
          static_cast<std::uint8_t>(value >> (entry * 8)));
      const std::uint32_t mask = 0xffU << (entry * 8);
      machine_.pmpcfg0 =
          (machine_.pmpcfg0 & ~mask) | (std::uint32_t{newConfig} << (entry * 8));
    }
  }

  bool pmpAddressLocked(unsigned entry) const {
    if ((pmpConfig(entry) & kPmpLock) != 0) return true;
    if (entry + 1 >= kPmpEntries) return false;
    const auto next = pmpConfig(entry + 1);
    const auto nextMode = (next & kPmpAddressMask) >> kPmpAddressShift;
    return (next & kPmpLock) != 0 && nextMode == kPmpTor;
  }

  void writePmpaddr(unsigned entry, std::uint32_t value) {
    if (!pmpAddressLocked(entry)) machine_.pmpaddr[entry] = value & 0x3fffffffU;
  }

  static unsigned memoryAccessSize(std::uint32_t instruction) {
    switch ((instruction >> 12) & 0x7U) {
      case 0:
      case 4: return 1;
      case 1:
      case 5: return 2;
      case 2: return 4;
      default: throw std::runtime_error("RV32IMU PMP shadow received an invalid memory width");
    }
  }

  bool pmpAllows(std::uint32_t address, unsigned size, bool write, bool execute) const {
    if (size == 0) return false;
    const std::uint64_t start = address;
    const std::uint64_t end = start + size - 1U;
    if (end > std::numeric_limits<std::uint32_t>::max()) return false;

    for (unsigned entry = 0; entry < kPmpEntries; ++entry) {
      const auto config = pmpConfig(entry);
      const auto mode = (config & kPmpAddressMask) >> kPmpAddressShift;
      if (mode == 0) continue;

      std::uint64_t lower = 0;
      std::uint64_t upper = 0;
      const std::uint32_t encoded = machine_.pmpaddr[entry];
      if (mode == kPmpTor) {
        lower = entry == 0 ? 0 : std::uint64_t{machine_.pmpaddr[entry - 1]} << 2;
        upper = std::uint64_t{encoded} << 2;
      } else if (mode == kPmpNa4) {
        lower = std::uint64_t{encoded} << 2;
        upper = lower + 4;
      } else if (mode == kPmpNapot) {
        unsigned ones = 0;
        while (ones < 30 && ((encoded >> ones) & 1U) != 0) ++ones;
        if (ones == 30) {
          lower = 0;
          upper = std::uint64_t{1} << 32;
        } else {
          const std::uint32_t lowMask = ones == 0 ? 0 : ((1U << ones) - 1U);
          lower = std::uint64_t{encoded & ~lowMask} << 2;
          upper = lower + (std::uint64_t{1} << (ones + 3));
        }
      }

      if (upper <= lower || start >= upper || end < lower) continue;
      const bool fullyContained = start >= lower && end < upper;
      if (!fullyContained) return false;
      if (machine_.currentPrivilege == kPrivilegeMachine && (config & kPmpLock) == 0) {
        return true;
      }
      const auto permission = execute ? kPmpExecute : (write ? kPmpWrite : kPmpRead);
      return (config & permission) != 0;
    }
    return machine_.currentPrivilege == kPrivilegeMachine;
  }

  void validatePmpNormal(const NemuState32& before, const DifftestCommit& commit) const {
    const auto pc = checkedAddress(commit.pc, "normal-event PC");
    if (!pmpAllows(pc, commit.instBytes, false, true)) {
      fail("normal event should have raised an instruction access fault at " + hex32(pc));
    }
    if (!commit.memValid) return;
    const auto address = checkedAddress(commit.memAddr, "normal memory address");
    const auto size = memoryAccessSize(commit.inst);
    if (!pmpAllows(address, size, commit.memWrite, false)) {
      fail("normal memory event should have raised a PMP access fault at " + hex32(address));
    }
    (void)before;
  }

'''
    text = replace_once(
        text,
        '  std::uint32_t readCsr(std::uint32_t address) const {\n',
        helpers + '  std::uint32_t readCsr(std::uint32_t address) const {\n',
        "PMP helper methods",
    )

    text = replace_once(
        text,
        '      case kMtval: return machine_.mtval;\n'
        '      default: fail("Zicsr shadow read of unimplemented CSR " + hex32(address));\n',
        '      case kMtval: return machine_.mtval;\n'
        '      case kPmpcfg0: return machine_.pmpcfg0;\n'
        '      case kPmpaddr0 + 0: return machine_.pmpaddr[0];\n'
        '      case kPmpaddr0 + 1: return machine_.pmpaddr[1];\n'
        '      case kPmpaddr0 + 2: return machine_.pmpaddr[2];\n'
        '      case kPmpaddr0 + 3: return machine_.pmpaddr[3];\n'
        '      default: fail("Zicsr shadow read of unimplemented CSR " + hex32(address));\n',
        "PMP CSR reads",
    )

    text = replace_once(
        text,
        '      case kMtval:\n'
        '        return true;\n'
        '      case kMisa:\n',
        '      case kMtval:\n'
        '      case kPmpcfg0:\n'
        '      case kPmpaddr0 + 0:\n'
        '      case kPmpaddr0 + 1:\n'
        '      case kPmpaddr0 + 2:\n'
        '      case kPmpaddr0 + 3:\n'
        '        return true;\n'
        '      case kMisa:\n',
        "PMP CSR writability",
    )

    text = replace_once(
        text,
        '      case kMtval:\n'
        '        machine_.mtval = value;\n'
        '        return;\n'
        '      case kMisa:\n',
        '      case kMtval:\n'
        '        machine_.mtval = value;\n'
        '        return;\n'
        '      case kPmpcfg0:\n'
        '        writePmpcfg0(value);\n'
        '        return;\n'
        '      case kPmpaddr0 + 0:\n'
        '      case kPmpaddr0 + 1:\n'
        '      case kPmpaddr0 + 2:\n'
        '      case kPmpaddr0 + 3:\n'
        '        writePmpaddr(address - kPmpaddr0, value);\n'
        '        return;\n'
        '      case kMisa:\n',
        "PMP CSR writes",
    )

    text = replace_once(
        text,
        '    if (commit.rdWrite || commit.memValid) {\n'
        '      fail("trap event exposed a register or memory side effect");\n'
        '    }\n\n'
        '    switch (cause) {\n',
        '    if (commit.rdWrite || commit.memValid) {\n'
        '      fail("trap event exposed a register or memory side effect");\n'
        '    }\n'
        '    if (cause != kInstructionAccessFault && !pmpAllows(pc, commit.instBytes, false, true)) {\n'
        '      fail("non-fetch trap occurred at a PMP-denied instruction address");\n'
        '    }\n\n'
        '    switch (cause) {\n',
        "trap fetch validation",
    )

    text = replace_once(
        text,
        '      case kInstructionAccessFault:\n'
        '        if (value != pc) {\n'
        '          fail("instruction access fault mtval=" + hex32(value) +\n'
        '               " expected faulting pc=" + hex32(pc));\n'
        '        }\n'
        '        return;\n',
        '      case kInstructionAccessFault:\n'
        '        if (value != pc || pmpAllows(pc, commit.instBytes, false, true)) {\n'
        '          fail("instruction access fault is not justified by the PMP shadow");\n'
        '        }\n'
        '        return;\n',
        "instruction PMP trap",
    )

    text = replace_once(
        text,
        '      case kLoadAccessFault:\n'
        '        if ((commit.inst & 0x7fU) != kLoadOpcode || instructionAt(pc, commit.instBytes) != commit.rawInst ||\n'
        '            value != explicitMemoryAddress(before, commit.inst)) {\n'
        '          fail("load access-fault trap metadata is inconsistent");\n'
        '        }\n'
        '        return;\n',
        '      case kLoadAccessFault: {\n'
        '        const auto address = explicitMemoryAddress(before, commit.inst);\n'
        '        if ((commit.inst & 0x7fU) != kLoadOpcode || instructionAt(pc, commit.instBytes) != commit.rawInst ||\n'
        '            value != address || pmpAllows(address, memoryAccessSize(commit.inst), false, false)) {\n'
        '          fail("load access fault is not justified by the PMP shadow");\n'
        '        }\n'
        '        return;\n'
        '      }\n',
        "load PMP trap",
    )

    text = replace_once(
        text,
        '      case kStoreAccessFault:\n'
        '        if ((commit.inst & 0x7fU) != kStoreOpcode || instructionAt(pc, commit.instBytes) != commit.rawInst ||\n'
        '            value != explicitMemoryAddress(before, commit.inst)) {\n'
        '          fail("store access-fault trap metadata is inconsistent");\n'
        '        }\n'
        '        return;\n',
        '      case kStoreAccessFault: {\n'
        '        const auto address = explicitMemoryAddress(before, commit.inst);\n'
        '        if ((commit.inst & 0x7fU) != kStoreOpcode || instructionAt(pc, commit.instBytes) != commit.rawInst ||\n'
        '            value != address || pmpAllows(address, memoryAccessSize(commit.inst), true, false)) {\n'
        '          fail("store access fault is not justified by the PMP shadow");\n'
        '        }\n'
        '        return;\n'
        '      }\n',
        "store PMP trap",
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text, encoding="utf-8")
    print(f"wrote RV32IMU PMP DiffTest adapter: {args.output}")


if __name__ == "__main__":
    main()
