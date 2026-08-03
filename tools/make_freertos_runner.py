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
