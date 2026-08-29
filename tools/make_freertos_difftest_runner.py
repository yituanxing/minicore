#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()

    text = arguments.source.read_text(encoding="utf-8")
    old = '      if (difftest) std::cout << ", difftest=" << difftest->checkedCommits();\n'
    new = (
        "      if (difftest) {\n"
        "        std::cout << \", difftest=\" << difftest->checkedCommits()\n"
        "                  << \", zicsr-shadow=\" << difftest->zicsrShadowSteps()\n"
        "                  << \", trap-shadow=\" << difftest->trapShadowSteps()\n"
        "                  << \", fence-shadow=\" << difftest->fenceShadowSteps()\n"
        "                  << \", wfi-shadow=\" << difftest->wfiShadowSteps()\n"
        "                  << \", mret-shadow=\" << difftest->mretShadowSteps()\n"
        "                  << \", interrupt-shadow=\" << difftest->interruptShadowSteps();\n"
        "      }\n"
    )
    if text.count(old) != 1:
        raise SystemExit(
            f"ERROR: expected exactly one DiffTest summary anchor, found {text.count(old)}"
        )
    output = text.replace(old, new, 1)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(output, encoding="utf-8")
    print(f"wrote FreeRTOS DiffTest runner: {arguments.output}")


if __name__ == "__main__":
    main()
