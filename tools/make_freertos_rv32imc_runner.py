#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import subprocess
import sys


BASE_TOP = "VAetherCoreRV32IMTrapSimTop"
TARGET_TOP = "VAetherCoreRV32IMCTrapSimTop"
BASE_DIFFTEST_CTOR = (
    "          *options.difftestSharedObject, options.image, kRamBase, kRamSize);"
)
TARGET_DIFFTEST_CTOR = (
    "          *options.difftestSharedObject, options.image, kRamBase, kRamSize,\n"
    "          Rv32DifftestIsaProfile::rv32imc());"
)


def main() -> None:
    if len(sys.argv) < 3:
        raise SystemExit("usage: make_freertos_rv32imc_runner.py SOURCE OUTPUT [base options...]")

    base_tool = Path(__file__).with_name("make_freertos_runner.py")
    subprocess.run([sys.executable, str(base_tool), *sys.argv[1:]], check=True)

    output = Path(sys.argv[2])
    text = output.read_text()

    top_count = text.count(BASE_TOP)
    if top_count == 0:
        raise SystemExit(f"ERROR: generated FreeRTOS runner contains no {BASE_TOP} anchor")
    if TARGET_TOP in text:
        raise SystemExit(f"ERROR: generated FreeRTOS runner unexpectedly already contains {TARGET_TOP}")
    text = text.replace(BASE_TOP, TARGET_TOP)

    ctor_count = text.count(BASE_DIFFTEST_CTOR)
    if ctor_count != 1:
        raise SystemExit(
            f"ERROR: expected exactly one four-argument DiffTest constructor anchor, found {ctor_count}"
        )
    text = text.replace(BASE_DIFFTEST_CTOR, TARGET_DIFFTEST_CTOR, 1)

    output.write_text(text)
    rewritten = output.read_text()
    if BASE_TOP in rewritten or TARGET_TOP not in rewritten:
        raise SystemExit("ERROR: FreeRTOS RV32IMC runner top retarget did not converge")
    if BASE_DIFFTEST_CTOR in rewritten or "Rv32DifftestIsaProfile::rv32imc()" not in rewritten:
        raise SystemExit("ERROR: FreeRTOS RV32IMC DiffTest profile selection did not converge")

    print(f"retargeted_freertos_runner_occurrences={top_count}")
    print(f"retargeted_freertos_runner_top={TARGET_TOP}")
    print("retargeted_freertos_difftest_profile=rv32imc")


if __name__ == "__main__":
    main()
