#!/usr/bin/env python3
"""Install the bounded AetherCore NuttX protected-userspace PMP overlay.

The upstream qemu-rv protected profile appends PMP regions by scanning for the
next free entry across the architectural 16-entry namespace.  AetherCore
intentionally implements four PMP entries.  The P1 profile needs exactly two
NAPOT entries, so bind user flash and user RAM to entries 0 and 1 explicitly.
This keeps the platform contract honest and prevents later code changes from
probing unimplemented PMP CSRs during early userspace setup.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
from typing import Sequence

PMP_OLD = r'''  int ret;
  ret = riscv_append_pmp_region(UFLASH_F, UFLASH_START, UFLASH_SIZE);
  DEBUGASSERT(ret == 0);
  ret = riscv_append_pmp_region(USRAM_F, USRAM_START, USRAM_SIZE);
  DEBUGASSERT(ret == 0);
'''

PMP_NEW = r'''  int ret;

  /* AetherCore exposes four PMP entries.  Protected NuttX uses exactly two
   * naturally aligned regions, so configure the implemented entries directly
   * instead of scanning the full architectural 16-entry CSR namespace.
   */

  ret = riscv_config_pmp_region(0, UFLASH_F, UFLASH_START, UFLASH_SIZE);
  DEBUGASSERT(ret == 0);
  ret = riscv_config_pmp_region(1, USRAM_F, USRAM_START, USRAM_SIZE);
  DEBUGASSERT(ret == 0);
'''

BOOL_SETTINGS = {
    "CONFIG_BUILD_PROTECTED": True,
    "CONFIG_ARCH_USE_MPU": True,
    "CONFIG_LIB_SYSCALL": True,
    "CONFIG_ARCH_USE_S_MODE": False,
}


class OverlayError(RuntimeError):
    pass


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count == 0:
        if new in text:
            return
        raise OverlayError(f"expected protected PMP anchor missing in {path}")
    if count != 1:
        raise OverlayError(f"expected one protected PMP anchor in {path}, found {count}")
    path.write_text(text.replace(old, new, 1))


def set_config(path: Path, symbol: str, value: bool) -> None:
    lines = path.read_text().splitlines()
    pattern = re.compile(
        rf"^(?:{re.escape(symbol)}=.*|# {re.escape(symbol)} is not set)$"
    )
    lines = [line for line in lines if not pattern.match(line)]
    lines.append(f"{symbol}=y" if value else f"# {symbol} is not set")
    path.write_text("\n".join(lines) + "\n")


def install(root: Path) -> None:
    userspace = root / "arch/risc-v/src/qemu-rv/qemu_rv_userspace.c"
    config = root / ".config"
    missing = [str(path) for path in (userspace, config) if not path.is_file()]
    if missing:
        raise OverlayError(
            "missing protected overlay inputs: " + ", ".join(missing)
        )

    config_lines = config.read_text().splitlines()
    if "CONFIG_AETHERCORE_UART_RX_IRQ=y" not in config_lines:
        raise OverlayError("protected overlay requires the applied N4 platform boundary")

    replace_once(userspace, PMP_OLD, PMP_NEW)
    for symbol, value in BOOL_SETTINGS.items():
        set_config(config, symbol, value)

    generated = userspace.read_text()
    required = (
        "riscv_config_pmp_region(0, UFLASH_F",
        "riscv_config_pmp_region(1, USRAM_F",
        "AetherCore exposes four PMP entries",
    )
    for fragment in required:
        if fragment not in generated:
            raise OverlayError(f"generated protected PMP code missing {fragment}")
    if "riscv_append_pmp_region(" in generated:
        raise OverlayError("protected userspace still scans for free PMP entries")


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
    print(f"Protected userspace overlay PASS: {args.nuttx_root.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
