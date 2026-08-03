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
        "constexpr std::uint32_t kEcall = 0x00000073U;\n"
        "constexpr std::uint32_t kMret = 0x30200073U;\n",
        "constexpr std::uint32_t kEcall = 0x00000073U;\n"
        "constexpr std::uint32_t kWfi = 0x10500073U;\n"
        "constexpr std::uint32_t kMret = 0x30200073U;\n",
        "WFI instruction constant",
    )
    text = replace_once(
        text,
        "    const bool ecallTrapStep = commit.exception;\n"
        "    const bool mretStep = commit.inst == kMret;\n"
        "    const bool zicsrStep = isZicsrInstruction(commit.inst);\n"
        "    if (ecallTrapStep) {\n"
        "      after = executeEcallTrap(before, commit);\n"
        "      regcpy_(&after, kToRef);\n"
        "      ++trapShadowSteps_;\n"
        "    } else if (mretStep) {\n",
        "    const bool ecallTrapStep = commit.exception;\n"
        "    const bool wfiStep = commit.inst == kWfi;\n"
        "    const bool mretStep = commit.inst == kMret;\n"
        "    const bool zicsrStep = isZicsrInstruction(commit.inst);\n"
        "    if (ecallTrapStep) {\n"
        "      after = executeEcallTrap(before, commit);\n"
        "      regcpy_(&after, kToRef);\n"
        "      ++trapShadowSteps_;\n"
        "    } else if (wfiStep) {\n"
        "      after = executeWfi(before, commit);\n"
        "      regcpy_(&after, kToRef);\n"
        "    } else if (mretStep) {\n",
        "WFI reference dispatch",
    )
    text = replace_once(
        text,
        "    if (mtimeLoadStep) line << \" reference=mtime-load-shadow\";\n"
        "    if (zicsrStep) line << \" reference=zicsr-shadow\";\n",
        "    if (mtimeLoadStep) line << \" reference=mtime-load-shadow\";\n"
        "    if (wfiStep) line << \" reference=wfi-shadow\";\n"
        "    if (zicsrStep) line << \" reference=zicsr-shadow\";\n",
        "WFI trace label",
    )
    method = r'''  NemuState32 executeWfi(const NemuState32& before,
                         const DifftestCommit& commit) {
    const auto interruptPc = checkedAddress(commit.interruptPc, "WFI interrupt PC");
    if (commit.inst != kWfi || commit.rdWrite || commit.memValid || commit.exception ||
        !commit.interrupt || interruptPc != before.pc + 4U) {
      fail("FreeRTOS WFI shadow received an invalid architectural wake event");
    }

    NemuState32 after = before;
    after.pc = before.pc + 4U;
    after.gpr[0] = 0;
    return after;
  }

'''
    text = replace_once(
        text,
        "  NemuState32 executeMret(const NemuState32& before, const DifftestCommit& commit) {\n",
        method
        + "  NemuState32 executeMret(const NemuState32& before, const DifftestCommit& commit) {\n",
        "MRET method",
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
    print(f"wrote FreeRTOS WFI DiffTest adapter: {arguments.output}")


if __name__ == "__main__":
    main()
