#!/usr/bin/env python3
"""Promote an applied N2 NuttX tree to the bounded N3 timer profile."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
from typing import Sequence

KCONFIG_BLOCK = r'''

config AETHERCORE_TIMER
	bool "AetherCore machine timer"
	default n
	help
	  Enable the CLINT-compatible machine timer used by the bounded N3
	  scheduler and ostest qualification.  External interrupts remain off.
'''

IRQ_N2 = r'''  riscv_exception_attach();
  return;
#endif
'''

IRQ_N3 = r'''  riscv_exception_attach();
#ifdef CONFIG_AETHERCORE_TIMER
  /* The timer driver enables MTIE when its lower half is registered.  N3
   * enables only the global machine interrupt gate here; MEIE/PLIC remain
   * untouched until N4.
   */

  SET_CSR(CSR_STATUS, STATUS_IE);
#endif
  return;
#endif
'''

BOOL_SETTINGS = {
    "CONFIG_AETHERCORE_TIMER": True,
    "CONFIG_SUPPRESS_INTERRUPTS": False,
    "CONFIG_TESTING_OSTEST": True,
    "CONFIG_TESTING_OSTEST_WAITRESULT": True,
}

VALUE_SETTINGS = {
    "CONFIG_INIT_ENTRYPOINT": '"ostest_main"',
    "CONFIG_INIT_ENTRYNAME": '"ostest_main"',
    "CONFIG_TESTING_OSTEST_LOOPS": "1",
    "CONFIG_TESTING_OSTEST_NBARRIER_THREADS": "2",
    "CONFIG_TESTING_OSTEST_RR_RANGE": "100",
    "CONFIG_TESTING_OSTEST_RR_RUNS": "1",
    "CONFIG_TESTING_OSTEST_SPINLOCK_THREADS": "2",
    "CONFIG_TEST_LOOP_SCALE": "1",
}


class OverlayError(RuntimeError):
    pass


def append_once(path: Path, marker: str, block: str) -> None:
    text = path.read_text()
    if marker in text:
        return
    path.write_text(text.rstrip() + "\n" + block.lstrip())


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count == 0:
        if new in text:
            return
        raise OverlayError(f"expected N2 patch anchor missing in {path}")
    if count != 1:
        raise OverlayError(f"expected one N2 patch anchor in {path}, found {count}")
    path.write_text(text.replace(old, new, 1))


def set_config(path: Path, symbol: str, value: str | bool) -> None:
    lines = path.read_text().splitlines()
    pattern = re.compile(
        rf"^(?:{re.escape(symbol)}=.*|# {re.escape(symbol)} is not set)$"
    )
    lines = [line for line in lines if not pattern.match(line)]
    if isinstance(value, bool):
        replacement = f"{symbol}=y" if value else f"# {symbol} is not set"
    else:
        replacement = f"{symbol}={value}"
    lines.append(replacement)
    path.write_text("\n".join(lines) + "\n")


def install(root: Path) -> None:
    kconfig = root / "arch/risc-v/src/qemu-rv/Kconfig"
    irq = root / "arch/risc-v/src/qemu-rv/qemu_rv_irq.c"
    config = root / ".config"
    required = [kconfig, irq, config]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise OverlayError("missing N3 overlay inputs: " + ", ".join(missing))

    config_text = config.read_text().splitlines()
    if "CONFIG_AETHERCORE_UART=y" not in config_text:
        raise OverlayError("N3 requires the already-applied N2 UART overlay")

    append_once(kconfig, "config AETHERCORE_TIMER", KCONFIG_BLOCK)
    replace_once(irq, IRQ_N2, IRQ_N3)

    for symbol, value in BOOL_SETTINGS.items():
        set_config(config, symbol, value)
    for symbol, value in VALUE_SETTINGS.items():
        set_config(config, symbol, value)

    irq_text = irq.read_text()
    if "SET_CSR(CSR_STATUS, STATUS_IE);" not in irq_text:
        raise OverlayError("N3 did not enable the global machine interrupt gate")


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("nuttx_root", type=Path)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        install(args.nuttx_root.resolve())
    except OverlayError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    print(f"N3 overlay PASS: {args.nuttx_root.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
