#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"ERROR: expected exactly one {label} anchor, found {count}")
    return text.replace(old, new, 1)


def adapt(source: str) -> str:
    text = source

    text = replace_once(
        text,
        "constexpr std::uint32_t kMret = 0x30200073U;\n",
        "constexpr std::uint32_t kEcall = 0x00000073U;\n"
        "constexpr std::uint32_t kMret = 0x30200073U;\n",
        "SYSTEM instruction",
    )
    text = replace_once(
        text,
        "constexpr std::uint32_t kMip = 0x344U;\n",
        "constexpr std::uint32_t kMip = 0x344U;\n"
        "constexpr std::uint32_t kMhartid = 0xf14U;\n",
        "Machine CSR address",
    )
    text = replace_once(
        text,
        "constexpr std::uint32_t kMachineTimerCause = 0x80000007U;\n",
        "constexpr std::uint32_t kMachineEcallCause = 11U;\n"
        "constexpr std::uint32_t kMachineTimerCause = 0x80000007U;\n",
        "Machine trap cause",
    )

    text = replace_once(
        text,
        "  void check(const DifftestCommit& commit) {\n"
        "    if (commit.exception) {\n"
        "      fail(\"timer workload reported an unexpected synchronous exception\");\n"
        "    }\n\n"
        "    const auto commitPc = checkedAddress(commit.pc, \"commit PC\");\n",
        "  void check(const DifftestCommit& commit) {\n"
        "    const auto commitPc = checkedAddress(commit.pc, \"commit PC\");\n",
        "synchronous exception rejection",
    )

    text = replace_once(
        text,
        "    NemuState32 after{};\n"
        "    const bool mretStep = commit.inst == kMret;\n"
        "    const bool zicsrStep = isZicsrInstruction(commit.inst);\n"
        "    if (mretStep) {\n"
        "      after = executeMret(before, commit);\n"
        "      regcpy_(&after, kToRef);\n"
        "      ++mretShadowSteps_;\n"
        "    } else if (zicsrStep) {\n",
        "    NemuState32 after{};\n"
        "    const bool ecallTrapStep = commit.exception;\n"
        "    const bool mretStep = commit.inst == kMret;\n"
        "    const bool zicsrStep = isZicsrInstruction(commit.inst);\n"
        "    if (ecallTrapStep) {\n"
        "      after = executeEcallTrap(before, commit);\n"
        "      regcpy_(&after, kToRef);\n"
        "      ++trapShadowSteps_;\n"
        "    } else if (mretStep) {\n"
        "      after = executeMret(before, commit);\n"
        "      regcpy_(&after, kToRef);\n"
        "      ++mretShadowSteps_;\n"
        "    } else if (zicsrStep) {\n",
        "reference execution dispatch",
    )

    text = replace_once(
        text,
        "    if (zicsrStep) line << \" reference=zicsr-shadow\";\n"
        "    if (mretStep) line << \" reference=mret-shadow\";\n",
        "    if (ecallTrapStep) {\n"
        "      line << \" reference=machine-ecall-shadow cause=\"\n"
        "           << hex32(checkedAddress(commit.exceptionCause, \"exception cause\"));\n"
        "    }\n"
        "    if (zicsrStep) line << \" reference=zicsr-shadow\";\n"
        "    if (mretStep) line << \" reference=mret-shadow\";\n",
        "trace shadow labels",
    )

    text = replace_once(
        text,
        "  std::uint64_t trapShadowSteps() const { return 0; }\n",
        "  std::uint64_t trapShadowSteps() const { return trapShadowSteps_; }\n",
        "trap counter accessor",
    )

    text = replace_once(
        text,
        "      case kMip: return machine_.timerPending ? kMachineTimerMask : 0U;\n"
        "      default: fail(\"timer Zicsr shadow read of unimplemented CSR \" + hex32(address));\n",
        "      case kMip: return machine_.timerPending ? kMachineTimerMask : 0U;\n"
        "      case kMhartid: return 0U;\n"
        "      default: fail(\"FreeRTOS Zicsr shadow read of unimplemented CSR \" + hex32(address));\n",
        "CSR read switch",
    )

    text = replace_once(
        text,
        "      case kMisa:\n"
        "      case kMip:\n"
        "        return false;\n"
        "      default:\n"
        "        fail(\"timer Zicsr shadow legality query for unimplemented CSR \" + hex32(address));\n",
        "      case kMisa:\n"
        "      case kMip:\n"
        "      case kMhartid:\n"
        "        return false;\n"
        "      default:\n"
        "        fail(\"FreeRTOS Zicsr shadow legality query for unimplemented CSR \" + hex32(address));\n",
        "CSR writability switch",
    )

    text = replace_once(
        text,
        "      case kMisa:\n"
        "      case kMip:\n"
        "        fail(\"timer Zicsr shadow attempted to write a read-only CSR\");\n"
        "      default:\n"
        "        fail(\"timer Zicsr shadow write of unimplemented CSR \" + hex32(address));\n",
        "      case kMisa:\n"
        "      case kMip:\n"
        "      case kMhartid:\n"
        "        fail(\"FreeRTOS Zicsr shadow attempted to write a read-only CSR\");\n"
        "      default:\n"
        "        fail(\"FreeRTOS Zicsr shadow write of unimplemented CSR \" + hex32(address));\n",
        "CSR write switch",
    )

    ecall_method = r'''  NemuState32 executeEcallTrap(const NemuState32& before,
                                   const DifftestCommit& commit) {
    const auto cause = checkedAddress(commit.exceptionCause, "exception cause");
    const auto value = checkedAddress(commit.exceptionValue, "exception value");
    if (commit.inst != kEcall || cause != kMachineEcallCause || value != 0U ||
        commit.rdWrite || commit.memValid || commit.interrupt) {
      fail("FreeRTOS ECALL shadow received an invalid architectural event");
    }
    if ((before.pc & 3U) != 0U) {
      fail("FreeRTOS ECALL trap PC is not 4-byte aligned");
    }

    machine_.mstatus =
        (machine_.mstatus & ~kMstatusTrapMask) |
        ((machine_.mstatus & kMstatusMie) ? kMstatusMpie : 0U) |
        kMstatusMppMachine;
    machine_.mepc = before.pc & ~std::uint32_t{3};
    machine_.mcause = kMachineEcallCause;
    machine_.mtval = 0U;

    NemuState32 after = before;
    after.pc = machine_.mtvec;
    after.gpr[0] = 0;
    return after;
  }

'''
    text = replace_once(
        text,
        "  NemuState32 executeMret(const NemuState32& before, const DifftestCommit& commit) {\n",
        ecall_method
        + "  NemuState32 executeMret(const NemuState32& before, const DifftestCommit& commit) {\n",
        "MRET method",
    )

    text = replace_once(
        text,
        "  std::uint64_t zicsrShadowSteps_ = 0;\n"
        "  std::uint64_t mretShadowSteps_ = 0;\n",
        "  std::uint64_t zicsrShadowSteps_ = 0;\n"
        "  std::uint64_t trapShadowSteps_ = 0;\n"
        "  std::uint64_t mretShadowSteps_ = 0;\n",
        "shadow counters",
    )

    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()

    source = arguments.source.read_text(encoding="utf-8")
    output = adapt(source)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(output, encoding="utf-8")
    print(f"wrote FreeRTOS DiffTest adapter: {arguments.output}")


if __name__ == "__main__":
    main()
