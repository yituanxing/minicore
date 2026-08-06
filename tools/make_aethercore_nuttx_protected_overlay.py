#!/usr/bin/env python3
"""Install the bounded AetherCore NuttX protected-userspace PMP overlay.

The upstream qemu-rv protected profile appends PMP regions by scanning for the
next free entry across the architectural 16-entry namespace.  AetherCore
intentionally implements four PMP entries.  The P1 profile needs exactly two
NAPOT entries, so bind user flash and user RAM to entries 0 and 1 explicitly.
The profile also requires per-CPU scratch state and a kernel-only exception
stack so user-controlled stack memory is never used for syscall handling.
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
    "CONFIG_RISCV_PERCPU_SCRATCH": True,
    "CONFIG_ARCH_KERNEL_STACK": True,
    "CONFIG_ARCH_USE_S_MODE": False,
}

VALUE_SETTINGS = {
    "CONFIG_ARCH_KERNEL_STACKSIZE": "2048",
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
    for symbol, value in VALUE_SETTINGS.items():
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

    resolved = set(config.read_text().splitlines())
    required_config = (
        "CONFIG_BUILD_PROTECTED=y",
        "CONFIG_ARCH_USE_MPU=y",
        "CONFIG_LIB_SYSCALL=y",
        "CONFIG_RISCV_PERCPU_SCRATCH=y",
        "CONFIG_ARCH_KERNEL_STACK=y",
        "CONFIG_ARCH_KERNEL_STACKSIZE=2048",
        "# CONFIG_ARCH_USE_S_MODE is not set",
    )
    for line in required_config:
        if line not in resolved:
            raise OverlayError(f"protected configuration missing {line}")


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
