#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


def replace_once(text: str, needle: str, replacement: str, label: str) -> str:
    count = text.count(needle)
    if count != 1:
        raise SystemExit(f"expected exactly one {label} insertion point, found {count}")
    return text.replace(needle, replacement, 1)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: make_rv32imu_pmp_runner.py SOURCE OUTPUT")

    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    text = source.read_text(encoding="utf-8")
    text = text.replace("VAetherCoreSimTop", "VAetherCoreRV32IMUPmpSimTop")

    text = replace_once(
        text,
        "  commit.exception = top.io_commit_exception;\n  return commit;",
        "  commit.exception = top.io_commit_exception;\n"
        "  commit.exceptionCause = static_cast<std::uint64_t>(top.io_commit_exceptionCause);\n"
        "  commit.exceptionValue = static_cast<std::uint64_t>(top.io_commit_exceptionValue);\n"
        "  return commit;",
        "DiffTest trap metadata",
    )

    text = replace_once(
        text,
        "          if (top.io_commit_exception) {\n"
        "            ++exceptions;\n"
        "            exceptionPc = static_cast<std::uint64_t>(top.io_commit_pc);\n"
        "            exceptionInst = static_cast<std::uint32_t>(top.io_commit_inst);\n"
        "          }",
        "          if (top.io_commit_exception) {\n"
        "            ++exceptions;\n"
        "            exceptionPc = static_cast<std::uint64_t>(top.io_commit_pc);\n"
        "            exceptionInst = static_cast<std::uint32_t>(top.io_commit_inst);\n"
        "            std::cerr << \"PMP_EXCEPTION pc=0x\" << std::hex\n"
        "                      << exceptionPc << \" inst=0x\" << exceptionInst\n"
        "                      << \" cause=0x\"\n"
        "                      << static_cast<std::uint64_t>(top.io_commit_exceptionCause)\n"
        "                      << \" value=0x\"\n"
        "                      << static_cast<std::uint64_t>(top.io_commit_exceptionValue)\n"
        "                      << std::dec << '\\n';\n"
        "          }",
        "PMP exception diagnostics",
    )

    text = replace_once(
        text,
        '      if (difftest) std::cout << ", difftest=" << difftest->checkedCommits();',
        '      if (difftest) std::cout << ", difftest=" << difftest->checkedCommits()'
        ' << ", zicsr-shadow=" << difftest->zicsrShadowSteps()'
        ' << ", trap-shadow=" << difftest->trapShadowSteps()'
        ' << ", mret-shadow=" << difftest->mretShadowSteps();',
        "PMP DiffTest summary",
    )

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")
    print(f"wrote RV32IMU PMP runner: {output}")


if __name__ == "__main__":
    main()
