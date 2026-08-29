#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import subprocess
import sys


BASE_DIFFTEST_CTOR = (
    "          *options.difftestSharedObject, options.image, kRamBase, kRamSize);"
)
TARGET_DIFFTEST_CTOR = (
    "          *options.difftestSharedObject, options.image, kRamBase, kRamSize,\n"
    "          Rv32DifftestIsaProfile::rv32imc());"
)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: make_freertos_rv32imc_difftest_runner.py SOURCE OUTPUT")

    base_tool = Path(__file__).with_name("make_freertos_difftest_runner.py")
    subprocess.run([sys.executable, str(base_tool), sys.argv[1], sys.argv[2]], check=True)

    output = Path(sys.argv[2])
    text = output.read_text(encoding="utf-8")
    count = text.count(BASE_DIFFTEST_CTOR)
    if count != 1:
        raise SystemExit(
            f"ERROR: expected exactly one four-argument DiffTest constructor anchor, found {count}"
        )
    if "Rv32DifftestIsaProfile::rv32imc()" in text:
        raise SystemExit("ERROR: DiffTest runner unexpectedly already selects RV32IMC")

    text = text.replace(BASE_DIFFTEST_CTOR, TARGET_DIFFTEST_CTOR, 1)
    output.write_text(text, encoding="utf-8")
    rewritten = output.read_text(encoding="utf-8")
    if BASE_DIFFTEST_CTOR in rewritten or "Rv32DifftestIsaProfile::rv32imc()" not in rewritten:
        raise SystemExit("ERROR: RV32IMC DiffTest profile selection did not converge")

    print("retargeted_freertos_difftest_profile=rv32imc")


if __name__ == "__main__":
    main()
