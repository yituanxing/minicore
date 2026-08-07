#!/usr/bin/env python3
"""Generate the dedicated NuttX protected-userspace Verilator runner.

The shared simulator is intentionally generic. This adapter selects the
RV32IMU/PMP/interrupt top and adds fail-closed evidence that execution really
entered U-mode: retired instructions from the protected user text region and
architecturally classified ECALL-from-U traps (mcause=8). It snapshots those
counters at the first NSH prompt, so the hello command phase itself must add
user commits, U-mode ECALLs, and MRET transitions before the second prompt.

The generated runner is a dedicated OS runner. Generic precise-fault and toy
x3/self-check modes belong to sim_main.cpp's small-program harness and are
explicitly disabled here instead of abusing --self-check-exit merely to bypass
the x3=12 assertion.
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

    # This runner is exclusively for a long-running protected OS image. The
    # generic precise-fault mode is a different harness contract and must not
    # become active because of unrelated protected-runner options.
    text = replace_once(
        text,
        "  bool faultCheck() const { return expectedExceptionPc.has_value(); }\n",
        "  bool faultCheck() const { return false; }  // Dedicated protected OS runner.\n",
        "generic precise-fault mode disable",
    )

    text = replace_once(
        text,
        "constexpr std::uint32_t kEbreak = 0x00100073U;\n",
        "constexpr std::uint32_t kEbreak = 0x00100073U;\n"
        "constexpr std::uint32_t kMret = 0x30200073U;\n"
        "constexpr std::uint64_t kUserTextBase = 0x80040000ULL;\n"
        "constexpr std::uint64_t kUserTextLimit = 0x80080000ULL;\n"
        "constexpr std::uint64_t kEnvironmentCallFromU = 8ULL;\n"
        "constexpr char kProtectedPrompt[] = \"nsh>\";\n"
        "constexpr std::size_t kProtectedPromptLength = sizeof(kProtectedPrompt) - 1;\n",
        "protected-userspace constants",
    )

    text = replace_once(
        text,
        "  std::uint64_t rxGapCycles = 1;\n",
        "  std::uint64_t rxGapCycles = 1;\n"
        "  std::optional<std::string> rxAfterUart;\n",
        "prompt-armed RX option",
    )

    text = replace_once(
        text,
        '        "[--rx-byte N ... --rx-start-cycle N --rx-gap-cycles N] [--difftest NEMU_SO] "\n',
        '        "[--rx-byte N ... --rx-start-cycle N --rx-gap-cycles N] "\n'
        '        "[--rx-after-uart TEXT] [--difftest NEMU_SO] "\n',
        "prompt-armed RX usage",
    )

    text = replace_once(
        text,
        "    } else if (arg == \"--rx-gap-cycles\" && i + 1 < argc) {\n"
        "      options.rxGapCycles = parseInteger(argv[++i]);\n"
        "      if (options.rxGapCycles == 0) {\n"
        "        throw std::runtime_error(\"--rx-gap-cycles must be non-zero\");\n"
        "      }\n"
        "    } else if (arg == \"--difftest\" && i + 1 < argc) {\n",
        "    } else if (arg == \"--rx-gap-cycles\" && i + 1 < argc) {\n"
        "      options.rxGapCycles = parseInteger(argv[++i]);\n"
        "      if (options.rxGapCycles == 0) {\n"
        "        throw std::runtime_error(\"--rx-gap-cycles must be non-zero\");\n"
        "      }\n"
        "    } else if (arg == \"--rx-after-uart\" && i + 1 < argc) {\n"
        "      options.rxAfterUart = argv[++i];\n"
        "      if (options.rxAfterUart->empty()) {\n"
        "        throw std::runtime_error(\"--rx-after-uart must not be empty\");\n"
        "      }\n"
        "    } else if (arg == \"--difftest\" && i + 1 < argc) {\n",
        "prompt-armed RX parser",
    )

    text = replace_once(
        text,
        "  if (options.expectedMemoryAddress.has_value() != options.expectedMemoryValue.has_value()) {\n"
        "    throw std::runtime_error(\"--expect-memory64 requires both address and value\");\n"
        "  }\n"
        "  return options;\n",
        "  if (options.expectedMemoryAddress.has_value() != options.expectedMemoryValue.has_value()) {\n"
        "    throw std::runtime_error(\"--expect-memory64 requires both address and value\");\n"
        "  }\n"
        "  if (options.rxAfterUart && options.rxBytes.empty()) {\n"
        "    throw std::runtime_error(\"--rx-after-uart requires at least one --rx-byte\");\n"
        "  }\n"
        "  return options;\n",
        "prompt-armed RX validation",
    )

    # The generic harness checks x3==12 for its tiny smoke program. NuttX uses
    # x3 as the architectural global pointer, so that assertion is invalid for
    # an OS image. Remove only the assertion; normal x3 commit observation is
    # left intact.
    text = replace_once(
        text,
        "            if (!options.selfCheckExit && !options.faultCheck() && value != kExpectedX3) {\n"
        "              std::cerr << \"\\nFAIL: x3 committed 0x\" << std::hex << value\n"
        "                        << \", expected 0x\" << kExpectedX3 << std::dec << '\\n';\n"
        "              return 3;\n"
        "            }\n",
        "            // Protected NuttX legitimately uses x3 as its global pointer.\n",
        "generic x3 toy assertion disable",
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
        "    std::uint64_t mretCommits = 0;\n"
        "    std::uint64_t commandStartUserCommits = 0;\n"
        "    std::uint64_t commandStartUserEnvironmentCalls = 0;\n"
        "    std::uint64_t commandStartMretCommits = 0;\n"
        "    bool protectedCommandStarted = false;\n"
        "    bool protectedComplete = false;\n",
        "U-mode counters",
    )

    text = replace_once(
        text,
        "    for (; cycles < options.maxCycles && !top.io_halted && !exitRequested; ++cycles) {\n",
        "    for (; cycles < options.maxCycles && !top.io_halted && !exitRequested &&\n"
        "           !protectedComplete; ++cycles) {\n",
        "protected completion loop condition",
    )

    text = replace_once(
        text,
        "      const bool rxValid = !top.reset && rxIndex < options.rxBytes.size() &&\n"
        "                           cycles >= nextRxCycle;\n",
        "      const bool rxArmed =\n"
        "          !options.rxAfterUart || uart.find(*options.rxAfterUart) != std::string::npos;\n"
        "      if (rxArmed && !protectedCommandStarted) {\n"
        "        protectedCommandStarted = true;\n"
        "        commandStartUserCommits = userCommits;\n"
        "        commandStartUserEnvironmentCalls = userEnvironmentCalls;\n"
        "        commandStartMretCommits = mretCommits;\n"
        "      }\n"
        "      const bool rxValid = !top.reset && rxArmed &&\n"
        "                           rxIndex < options.rxBytes.size() &&\n"
        "                           cycles >= nextRxCycle;\n",
        "prompt-armed RX scheduling",
    )

    text = replace_once(
        text,
        "        if (top.io_uartValid) {\n"
        "          const char byte = static_cast<char>(top.io_uartByte);\n"
        "          uart.push_back(byte);\n"
        "          std::cout << byte << std::flush;\n"
        "        }\n",
        "        if (top.io_uartValid) {\n"
        "          const char byte = static_cast<char>(top.io_uartByte);\n"
        "          uart.push_back(byte);\n"
        "          std::cout << byte << std::flush;\n"
        "\n"
        "          std::size_t promptCount = 0;\n"
        "          for (std::size_t pos = 0;\n"
        "               (pos = uart.find(kProtectedPrompt, pos)) != std::string::npos;\n"
        "               pos += kProtectedPromptLength) {\n"
        "            ++promptCount;\n"
        "          }\n"
        "          if (promptCount >= 2 && rxIndex == options.rxBytes.size()) {\n"
        "            protectedComplete = true;\n"
        "          }\n"
        "        }\n",
        "second NSH prompt completion",
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
        "    const auto commandUserCommits = protectedCommandStarted\n"
        "        ? userCommits - commandStartUserCommits : 0;\n"
        "    const auto commandUserEnvironmentCalls = protectedCommandStarted\n"
        "        ? userEnvironmentCalls - commandStartUserEnvironmentCalls : 0;\n"
        "    const auto commandMretCommits = protectedCommandStarted\n"
        "        ? mretCommits - commandStartMretCommits : 0;\n"
        "    std::cerr << \"UMODE_EVIDENCE user-commits=\" << userCommits\n"
        "              << \" u-ecalls=\" << userEnvironmentCalls\n"
        "              << \" mrets=\" << mretCommits << '\\n';\n"
        "    std::cerr << \"UMODE_COMMAND_EVIDENCE user-commits=\"\n"
        "              << commandUserCommits << \" u-ecalls=\"\n"
        "              << commandUserEnvironmentCalls << \" mrets=\"\n"
        "              << commandMretCommits << '\\n';\n"
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
        "    }\n"
        "    if (!protectedCommandStarted) {\n"
        "      std::cerr << \"FAIL: protected command phase never started\\n\";\n"
        "      return 14;\n"
        "    }\n"
        "    if (commandUserCommits == 0) {\n"
        "      std::cerr << \"FAIL: hello command retired no user instruction\\n\";\n"
        "      return 15;\n"
        "    }\n"
        "    if (commandUserEnvironmentCalls == 0) {\n"
        "      std::cerr << \"FAIL: hello command issued no ECALL-from-U\\n\";\n"
        "      return 16;\n"
        "    }\n"
        "    if (commandMretCommits == 0) {\n"
        "      std::cerr << \"FAIL: hello command observed no MRET return\\n\";\n"
        "      return 17;\n"
        "    }\n"
        "    if (protectedComplete) {\n"
        "      std::cout << \"PASS: protected NSH returned after U-mode hello at \"\n"
        "                << cycles << \" cycles, \" << committed\n"
        "                << \" committed instructions\";\n"
        "      if (options.stallPeriod != 0) {\n"
        "        std::cout << \", stall-period=\" << options.stallPeriod;\n"
        "      }\n"
        "      std::cout << '\\n';\n"
        "      return 0;\n"
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
