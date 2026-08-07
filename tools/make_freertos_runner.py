#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"ERROR: expected exactly one {label} anchor, found {count}")
    return text.replace(old, new, 1)


def adapt(source: str, trace: bool) -> str:
    text = source.replace("VAetherCoreSimTop", "VAetherCoreRV32IMTrapSimTop")
    text = replace_once(
        text,
        "constexpr std::uint32_t kEbreak = 0x00100073U;\n",
        "constexpr std::uint32_t kEbreak = 0x00100073U;\n"
        "constexpr std::uint32_t kWfi = 0x10500073U;\n",
        "WFI instruction constant",
    )
    text = replace_once(
        text,
        "  std::optional<std::uint64_t> expectedMemoryValue;\n\n"
        "  bool faultCheck() const { return expectedExceptionPc.has_value(); }",
        "  std::optional<std::uint64_t> expectedMemoryValue;\n"
        "  std::optional<std::uint32_t> uartRxByte;\n\n"
        "  bool faultCheck() const { return expectedExceptionPc.has_value(); }",
        "UART RX option field",
    )
    text = replace_once(
        text,
        '        "[--forbid-rd N] [--expect-memory64 ADDRESS VALUE]");',
        '        "[--forbid-rd N] [--expect-memory64 ADDRESS VALUE] "\n'
        '        "[--inject-uart-rx BYTE]");',
        "UART RX usage",
    )
    text = replace_once(
        text,
        '    } else if (arg == "--rx-byte" && i + 1 < argc) {\n'
        "      const auto byte = parseInteger(argv[++i]);\n"
        "      if (byte > 0xffU) throw std::runtime_error(\"--rx-byte must be in the range 0..255\");\n"
        "      options.rxBytes.push_back(static_cast<std::uint8_t>(byte));\n",
        '    } else if (arg == "--inject-uart-rx" && i + 1 < argc) {\n'
        "      const auto byte = parseInteger(argv[++i]);\n"
        "      if (byte > 0xff) throw std::runtime_error(\"--inject-uart-rx must fit in one byte\");\n"
        "      options.uartRxByte = static_cast<std::uint32_t>(byte);\n"
        '    } else if (arg == "--rx-byte" && i + 1 < argc) {\n'
        "      const auto byte = parseInteger(argv[++i]);\n"
        "      if (byte > 0xffU) throw std::runtime_error(\"--rx-byte must be in the range 0..255\");\n"
        "      options.rxBytes.push_back(static_cast<std::uint8_t>(byte));\n",
        "UART RX option parser",
    )
    text = replace_once(
        text,
        "  commit.exception = top.io_commit_exception;\n  return commit;",
        "  commit.exception = top.io_commit_exception;\n"
        "  commit.exceptionCause = static_cast<std::uint64_t>(top.io_commit_exceptionCause);\n"
        "  commit.exceptionValue = static_cast<std::uint64_t>(top.io_commit_exceptionValue);\n"
        "  commit.interrupt = top.io_commit_interrupt;\n"
        "  commit.interruptCause = static_cast<std::uint64_t>(top.io_commit_interruptCause);\n"
        "  commit.interruptPc = static_cast<std::uint64_t>(top.io_commit_interruptPc);\n"
        "  return commit;",
        "commit metadata",
    )
    text = replace_once(
        text,
        "    std::uint64_t committed = 0;\n"
        "    std::uint64_t exceptions = 0;\n",
        "    std::uint64_t committed = 0;\n"
        "    std::uint64_t wfiCommits = 0;\n"
        "    std::uint64_t maskedWfiCommits = 0;\n"
        "    std::uint64_t wfiSleepCycles = 0;\n"
        "    std::uint64_t uartRxReadySleepCycles = 0;\n"
        "    std::uint64_t externalInterruptCycles = 0;\n"
        "    std::uint64_t exceptions = 0;\n",
        "WFI counters",
    )
    text = replace_once(
        text,
        "    bool exitRequested = false;\n"
        "    bool forbiddenWriteSeen = false;\n",
        "    bool exitRequested = false;\n"
        "    bool uartRxInjected = false;\n"
        "    bool externalInterruptSeen = false;\n"
        "    bool forbiddenWriteSeen = false;\n",
        "UART RX injection state",
    )
    text = replace_once(
        text,
        "    top.reset = 1;\n"
        "    top.clock = 0;\n",
        "    top.reset = 1;\n"
        "    top.clock = 0;\n"
        "    top.io_rxValid = 0;\n"
        "    top.io_rxByte = 0;\n",
        "UART RX input initialization",
    )
    text = replace_once(
        text,
        "    for (; cycles < options.maxCycles && !top.io_halted && !exitRequested; ++cycles) {\n",
        "    for (; cycles < options.maxCycles &&\n"
        "           (options.selfCheckExit || !top.io_halted) && !exitRequested;\n"
        "         ++cycles) {\n",
        "simulation loop",
    )
    text = replace_once(
        text,
        "      top.clock = 0;\n"
        "      driveInputs(top, memory, memoryReady);\n",
        "      top.io_rxValid = 0;\n"
        "      if (options.uartRxByte && !uartRxInjected && top.io_halted && top.io_rxReady) {\n"
        "        top.io_rxByte = *options.uartRxByte;\n"
        "        top.io_rxValid = 1;\n"
        "        uartRxInjected = true;\n"
        "        std::cout << \"FREERTOS RUNNER RX INJECT cycle=\" << cycles << '\\n';\n"
        "      }\n\n"
        "      top.clock = 0;\n"
        "      driveInputs(top, memory, memoryReady);\n",
        "UART RX WFI injection",
    )
    text = replace_once(
        text,
        "      if (!top.reset) {\n"
        "        if (top.io_memValid && top.io_memWrite && top.io_memReady && !top.io_memFault) {\n",
        "      if (!top.reset) {\n"
        "        if (options.selfCheckExit && top.io_halted) {\n"
        "          ++wfiSleepCycles;\n"
        "          if (top.io_rxReady) ++uartRxReadySleepCycles;\n"
        "          if (top.io_commit_valid) {\n"
        "            std::cerr << \"FAIL: instruction retired while WFI sleep was asserted at cycle \"\n"
        "                      << cycles << '\\n';\n"
        "            return 27;\n"
        "          }\n"
        "        }\n"
        "        if (top.io_externalInterrupt) {\n"
        "          ++externalInterruptCycles;\n"
        "          if (!externalInterruptSeen) {\n"
        "            externalInterruptSeen = true;\n"
        "            std::cout << \"FREERTOS RUNNER EXTERNAL ASSERT cycle=\" << cycles << '\\n';\n"
        "          }\n"
        "        }\n\n"
        "        if (top.io_memValid && top.io_memWrite && top.io_memReady && !top.io_memFault) {\n",
        "WFI and external observation",
    )
    text = replace_once(
        text,
        "        if (top.io_commit_valid) {\n"
        "          ++committed;\n"
        "          if (difftest) difftest->check(makeDifftestCommit(top));\n",
        "        if (top.io_commit_valid) {\n"
        "          ++committed;\n"
        "          if (static_cast<std::uint32_t>(top.io_commit_inst) == kWfi) {\n"
        "            ++wfiCommits;\n"
        "            if (!top.io_commit_interrupt) ++maskedWfiCommits;\n"
        "          }\n"
        "          if (difftest) difftest->check(makeDifftestCommit(top));\n",
        "WFI retirement counter",
    )
    text = replace_once(
        text,
        "    if (!top.io_halted && cycles >= options.maxCycles) {\n"
        "      std::cerr << \"FAIL: timeout after \" << cycles << \" cycles\\n\";\n"
        "      return 2;\n"
        "    }",
        "    if (cycles >= options.maxCycles && !exitRequested) {\n"
        "      std::cerr << \"FAIL: timeout after \" << cycles << \" cycles\"\n"
        "                << \", uart-rx-injected=\" << (uartRxInjected ? 1 : 0)\n"
        "                << \", halted=\" << static_cast<unsigned>(top.io_halted)\n"
        "                << \", rx-ready=\" << static_cast<unsigned>(top.io_rxReady)\n"
        "                << \", external=\" << static_cast<unsigned>(top.io_externalInterrupt)\n"
        "                << \", rx-ready-sleep-cycles=\" << uartRxReadySleepCycles\n"
        "                << \", external-cycles=\" << externalInterruptCycles << '\\n';\n"
        "      return 2;\n"
        "    }",
        "simulation timeout diagnostics",
    )
    text = replace_once(
        text,
        "      if (exitCode != 0) {\n"
        "        std::cerr << \"FAIL: self-check program returned code \" << exitCode << '\\n';\n"
        "        return static_cast<int>(exitCode > 125 ? 125 : exitCode);\n"
        "      }\n"
        "      std::cout << \"PASS: self-check exit=0 after \" << cycles << \" cycles, \" << committed\n"
        "                << \" committed instructions\";\n",
        "      if (exitCode != 0) {\n"
        "        std::cerr << \"FAIL: self-check program returned code \" << exitCode << '\\n';\n"
        "        return static_cast<int>(exitCode > 125 ? 125 : exitCode);\n"
        "      }\n"
        "      if (options.uartRxByte && !uartRxInjected) {\n"
        "        std::cerr << \"FAIL: UART RX byte was never injected during WFI sleep\\n\";\n"
        "        return 31;\n"
        "      }\n"
        "      if (wfiCommits == 0) {\n"
        "        std::cerr << \"FAIL: self-check program completed without retiring WFI\\n\";\n"
        "        return 28;\n"
        "      }\n"
        "      if (maskedWfiCommits == 0) {\n"
        "        std::cerr << \"FAIL: self-check program completed without a masked tickless WFI wake\\n\";\n"
        "        return 30;\n"
        "      }\n"
        "      if (wfiSleepCycles == 0) {\n"
        "        std::cerr << \"FAIL: self-check program completed without an observable WFI sleep cycle\\n\";\n"
        "        return 29;\n"
        "      }\n"
        "      std::cout << \"PASS: self-check exit=0 after \" << cycles << \" cycles, \" << committed\n"
        "                << \" committed instructions\"\n"
        "                << \", wfi-commits=\" << wfiCommits\n"
        "                << \", masked-wfi-commits=\" << maskedWfiCommits\n"
        "                << \", wfi-sleep-cycles=\" << wfiSleepCycles\n"
        "                << \", uart-rx-injected=\" << (uartRxInjected ? 1 : 0)\n"
        "                << \", external-seen=\" << (externalInterruptSeen ? 1 : 0);\n",
        "self-check WFI and UART summary",
    )

    if trace:
        return text

    text = replace_once(
        text,
        '#include "verilated_vcd_c.h"\n',
        "",
        "VCD include",
    )
    text = replace_once(
        text,
        "    VerilatedVcdC* wave = nullptr;\n"
        "    if (options.trace) {\n"
        "      context.traceEverOn(true);\n"
        "      wave = new VerilatedVcdC;\n"
        "      top.trace(wave, 99);\n"
        "      wave->open(\"build/aethercore.vcd\");\n"
        "    }\n",
        "    if (options.trace) {\n"
        "      throw std::runtime_error(\"--trace is unavailable in this fast runner\");\n"
        "    }\n",
        "VCD setup",
    )
    dump = "      if (wave) wave->dump(context.time());\n"
    if text.count(dump) != 2:
        raise SystemExit(
            f"ERROR: expected exactly two VCD dump anchors, found {text.count(dump)}"
        )
    text = text.replace(dump, "")
    text = replace_once(
        text,
        "    if (wave) {\n"
        "      wave->close();\n"
        "      delete wave;\n"
        "    }\n",
        "",
        "VCD shutdown",
    )
    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--trace", choices=("0", "1"), required=True)
    arguments = parser.parse_args()

    source = arguments.source.read_text(encoding="utf-8")
    output = adapt(source, trace=arguments.trace == "1")
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(output, encoding="utf-8")
    print(
        f"wrote FreeRTOS runner: {arguments.output} trace={arguments.trace}"
    )


if __name__ == "__main__":
    main()
