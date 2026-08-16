#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import subprocess
import sys


BASE_TOP = "VAetherCoreRV32IMTrapSimTop"
TARGET_TOP = "VAetherCoreRV32IMCTrapSimTop"


def main() -> None:
    if len(sys.argv) < 3:
        raise SystemExit("usage: make_freertos_rv32imc_runner.py SOURCE OUTPUT [base options...]")

    base_tool = Path(__file__).with_name("make_freertos_runner.py")
    subprocess.run([sys.executable, str(base_tool), *sys.argv[1:]], check=True)

    output = Path(sys.argv[2])
    text = output.read_text()
    count = text.count(BASE_TOP)
    if count == 0:
        raise SystemExit(f"ERROR: generated FreeRTOS runner contains no {BASE_TOP} anchor")
    if TARGET_TOP in text:
        raise SystemExit(f"ERROR: generated FreeRTOS runner unexpectedly already contains {TARGET_TOP}")

    output.write_text(text.replace(BASE_TOP, TARGET_TOP))
    rewritten = output.read_text()
    if BASE_TOP in rewritten or TARGET_TOP not in rewritten:
        raise SystemExit("ERROR: FreeRTOS RV32IMC runner retarget did not converge")

    print(f"retargeted_freertos_runner_occurrences={count}")
    print(f"retargeted_freertos_runner_top={TARGET_TOP}")


if __name__ == "__main__":
    main()
