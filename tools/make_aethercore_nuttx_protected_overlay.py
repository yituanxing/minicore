#!/usr/bin/env python3
"""Install the AetherCore NuttX protected-userspace configuration overlay.

AetherCore now exposes the standard RV32 PMP16 namespace used by pinned NuttX,
so the upstream qemu-rv protected profile must keep its normal
riscv_append_pmp_region() allocator. The earlier four-entry AetherCore profile
required a platform patch that forced user flash/RAM into entries 0/1; that
workaround is deliberately removed here.

NuttX 13.0.0 only allocates per-process kernel stacks when ARCH_ADDRENV is
active. The pure protected/PMP qemu-rv path used here deliberately keeps
ARCH_ADDRENV disabled because AetherCore has no S-mode/MMU address-environment
port yet. Therefore P1 freezes the real upstream semantics: per-CPU scratch is
enabled for system calls, while syscall handling still uses the caller stack.
Dedicated kernel-stack hardening is a later architecture milestone and must not
be claimed by merely forcing an otherwise inactive Kconfig symbol.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
from typing import Sequence

PMP_UPSTREAM = r'''  int ret;
  ret = riscv_append_pmp_region(UFLASH_F, UFLASH_START, UFLASH_SIZE);
  DEBUGASSERT(ret == 0);
  ret = riscv_append_pmp_region(USRAM_F, USRAM_START, USRAM_SIZE);
  DEBUGASSERT(ret == 0);
'''

BOOL_SETTINGS = {
    "CONFIG_BUILD_PROTECTED": True,
    "CONFIG_ARCH_USE_MPU": True,
    "CONFIG_LIB_SYSCALL": True,
    "CONFIG_RISCV_PERCPU_SCRATCH": True,
    "CONFIG_ARCH_ADDRENV": False,
    "CONFIG_ARCH_KERNEL_STACK": False,
    "CONFIG_ARCH_USE_S_MODE": False,
}


class OverlayError(RuntimeError):
    pass


def set_config(path: Path, symbol: str, value: bool) -> None:
    lines = path.read_text().splitlines()
    pattern = re.compile(
        rf"^(?:{re.escape(symbol)}=.*|# {re.escape(symbol)} is not set)$"
    )
    lines = [line for line in lines if not pattern.match(line)]
    replacement = f"{symbol}=y" if value else f"# {symbol} is not set"
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

    upstream = userspace.read_text()
    count = upstream.count(PMP_UPSTREAM)
    if count != 1:
        raise OverlayError(
            f"expected one upstream protected PMP allocator anchor in {userspace}, found {count}"
        )
    if "riscv_config_pmp_region(0, UFLASH_F" in upstream or \
       "riscv_config_pmp_region(1, USRAM_F" in upstream:
        raise OverlayError("protected userspace still contains the obsolete fixed-entry PMP workaround")

    for symbol, value in BOOL_SETTINGS.items():
        set_config(config, symbol, value)

    generated = userspace.read_text()
    required = (
        "riscv_append_pmp_region(UFLASH_F, UFLASH_START, UFLASH_SIZE)",
        "riscv_append_pmp_region(USRAM_F, USRAM_START, USRAM_SIZE)",
    )
    for fragment in required:
        if fragment not in generated:
            raise OverlayError(f"upstream protected PMP allocator missing {fragment}")

    resolved = set(config.read_text().splitlines())
    required_config = (
        "CONFIG_BUILD_PROTECTED=y",
        "CONFIG_ARCH_USE_MPU=y",
        "CONFIG_LIB_SYSCALL=y",
        "CONFIG_RISCV_PERCPU_SCRATCH=y",
        "# CONFIG_ARCH_ADDRENV is not set",
        "# CONFIG_ARCH_KERNEL_STACK is not set",
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
