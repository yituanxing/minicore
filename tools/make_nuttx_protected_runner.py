#!/usr/bin/env python3
"""Generate the dedicated NuttX protected-userspace Verilator runner.

The shared simulator is intentionally generic.  This adapter selects the
RV32IMU/PMP/interrupt top and adds fail-closed evidence that execution really
entered U-mode: retired instructions from the protected user text region and
at least one architecturally classified ECALL-from-U trap (mcause=8).
"""

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
        raise SystemExit("usage: make_nuttx_protected_runner.py SOURCE OUTPUT")

    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    text = source.read_text(encoding="utf-8")

    text = text.replace(
        "VAetherCoreSimTop", "VAetherCoreNuttXProtectedSimTop"
    )

    text = replace_once(
        text,
        "constexpr std::uint32_t kEbreak = 0x00100073U;\n",
        "constexpr std::uint32_t kEbreak = 0x00100073U;\n"
        "constexpr std::uint32_t kMret = 0x30200073U;\n"
        "constexpr std::uint64_t kUserTextBase = 0x80040000ULL;\n"
        "constexpr std::uint64_t kUserTextLimit = 0x80080000ULL;\n"
        "constexpr std::uint64_t kEnvironmentCallFromU = 8ULL;\n",
        "protected-userspace constants",
    )

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
        "    std::uint64_t exceptions = 0;\n",
        "    std::uint64_t exceptions = 0;\n"
        "    std::uint64_t userCommits = 0;\n"
        "    std::uint64_t userEnvironmentCalls = 0;\n"
        "    std::uint64_t mretCommits = 0;\n",
        "U-mode counters",
    )

    text = replace_once(
        text,
        "        if (top.io_commit_valid) {\n"
        "          ++committed;\n"
        "          if (difftest) difftest->check(makeDifftestCommit(top));\n\n",
        "        if (top.io_commit_valid) {\n"
        "          ++committed;\n"
        "          const auto protectedCommitPc =\n"
        "              static_cast<std::uint64_t>(top.io_commit_pc);\n"
        "          if (protectedCommitPc >= kUserTextBase &&\n"
        "              protectedCommitPc < kUserTextLimit) {\n"
        "            ++userCommits;\n"
        "          }\n"
        "          if (static_cast<std::uint32_t>(top.io_commit_inst) == kMret) {\n"
        "            ++mretCommits;\n"
        "          }\n"
        "          if (difftest) difftest->check(makeDifftestCommit(top));\n\n",
        "user commit observation",
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
        "            const auto exceptionCause =\n"
        "                static_cast<std::uint64_t>(top.io_commit_exceptionCause);\n"
        "            if (exceptionCause == kEnvironmentCallFromU) {\n"
        "              ++userEnvironmentCalls;\n"
        "            }\n"
        "            std::cerr << \"PROTECTED_EXCEPTION pc=0x\" << std::hex\n"
        "                      << exceptionPc << \" inst=0x\" << exceptionInst\n"
        "                      << \" cause=0x\" << exceptionCause\n"
        "                      << \" value=0x\"\n"
        "                      << static_cast<std::uint64_t>(top.io_commit_exceptionValue)\n"
        "                      << std::dec << '\\n';\n"
        "          }",
        "protected exception diagnostics",
    )

    text = replace_once(
        text,
        "    if (!top.io_halted && cycles >= options.maxCycles) {\n"
        "      std::cerr << \"FAIL: timeout after \" << cycles << \" cycles\\n\";\n"
        "      return 2;\n"
        "    }\n",
        "    std::cerr << \"UMODE_EVIDENCE user-commits=\" << userCommits\n"
        "              << \" u-ecalls=\" << userEnvironmentCalls\n"
        "              << \" mrets=\" << mretCommits << '\\n';\n"
        "    if (userCommits == 0) {\n"
        "      std::cerr << \"FAIL: no instruction retired from protected user text\\n\";\n"
        "      return 11;\n"
        "    }\n"
        "    if (userEnvironmentCalls == 0) {\n"
        "      std::cerr << \"FAIL: no ECALL-from-U trap was observed\\n\";\n"
        "      return 12;\n"
        "    }\n"
        "    if (mretCommits == 0) {\n"
        "      std::cerr << \"FAIL: no MRET user transition was observed\\n\";\n"
        "      return 13;\n"
        "    }\n\n"
        "    if (!top.io_halted && cycles >= options.maxCycles) {\n"
        "      std::cerr << \"FAIL: timeout after \" << cycles << \" cycles\\n\";\n"
        "      return 2;\n"
        "    }\n",
        "fail-closed U-mode evidence",
    )

    text = replace_once(
        text,
        '      if (difftest) std::cout << ", difftest=" << difftest->checkedCommits();',
        '      if (difftest) std::cout << ", difftest=" << difftest->checkedCommits()'
        ' << ", zicsr-shadow=" << difftest->zicsrShadowSteps()'
        ' << ", trap-shadow=" << difftest->trapShadowSteps()'
        ' << ", mret-shadow=" << difftest->mretShadowSteps();',
        "protected DiffTest summary",
    )

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")
    print(f"wrote NuttX protected runner: {output}")


if __name__ == "__main__":
    main()
