#!/usr/bin/env python3
"""Install the bounded AetherCore NuttX N2 polling-UART overlay.

The N1 image intentionally uses the upstream qemu-rv board only as a build
qualification target.  N2 keeps the proven RISC-V start implementation, but
replaces the 16550 console boundary with the native AetherCore byte-wide MMIO
UART at 0x10000000, constrains RAM to the frozen SoC map, and deliberately
suppresses interrupts until the N3 timer/scheduler stage.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
from typing import Sequence

UART_SOURCE = r'''/****************************************************************************
 * arch/risc-v/src/qemu-rv/aethercore_serial.c
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include <sys/types.h>
#include <stdbool.h>
#include <stdint.h>
#include <errno.h>

#include <nuttx/arch.h>
#include <nuttx/serial/serial.h>

#include "riscv_internal.h"

#define AETHERCORE_UART_TX_ADDR  0x10000000u
#define AETHERCORE_RX_BUFSIZE    64
#define AETHERCORE_TX_BUFSIZE    256

static int aethercore_setup(struct uart_dev_s *dev)
{
  return OK;
}

static void aethercore_shutdown(struct uart_dev_s *dev)
{
}

static int aethercore_attach(struct uart_dev_s *dev)
{
  return OK;
}

static void aethercore_detach(struct uart_dev_s *dev)
{
}

static int aethercore_ioctl(struct file *filep, int cmd, unsigned long arg)
{
  return -ENOTTY;
}

static int aethercore_receive(struct uart_dev_s *dev, unsigned int *status)
{
  if (status != NULL)
    {
      *status = 0;
    }

  return 0;
}

static void aethercore_rxint(struct uart_dev_s *dev, bool enable)
{
}

static bool aethercore_rxavailable(struct uart_dev_s *dev)
{
  return false;
}

static void aethercore_send(struct uart_dev_s *dev, int ch)
{
  putreg8((uint8_t)ch, AETHERCORE_UART_TX_ADDR);
}

static void aethercore_txint(struct uart_dev_s *dev, bool enable)
{
  if (enable)
    {
      while (dev->xmit.head != dev->xmit.tail)
        {
          uart_xmitchars(dev);
        }
    }
}

static bool aethercore_txready(struct uart_dev_s *dev)
{
  return true;
}

static bool aethercore_txempty(struct uart_dev_s *dev)
{
  return true;
}

static const struct uart_ops_s g_aethercore_uart_ops =
{
  .setup       = aethercore_setup,
  .shutdown    = aethercore_shutdown,
  .attach      = aethercore_attach,
  .detach      = aethercore_detach,
  .ioctl       = aethercore_ioctl,
  .receive     = aethercore_receive,
  .rxint       = aethercore_rxint,
  .rxavailable = aethercore_rxavailable,
#ifdef CONFIG_SERIAL_IFLOWCONTROL
  .rxflowcontrol = NULL,
#endif
  .send        = aethercore_send,
  .txint       = aethercore_txint,
  .txready     = aethercore_txready,
  .txempty     = aethercore_txempty,
};

static char g_aethercore_rxbuffer[AETHERCORE_RX_BUFSIZE];
static char g_aethercore_txbuffer[AETHERCORE_TX_BUFSIZE];

static struct uart_dev_s g_aethercore_console =
{
  .isconsole = true,
  .recv =
  {
    .size   = sizeof(g_aethercore_rxbuffer),
    .buffer = g_aethercore_rxbuffer,
  },
  .xmit =
  {
    .size   = sizeof(g_aethercore_txbuffer),
    .buffer = g_aethercore_txbuffer,
  },
  .ops = &g_aethercore_uart_ops,
};

void riscv_lowputc(char ch)
{
  putreg8((uint8_t)ch, AETHERCORE_UART_TX_ADDR);
}

void aethercore_earlyserialinit(void)
{
  g_aethercore_console.isconsole = true;
}

void aethercore_serialinit(void)
{
  uart_register("/dev/console", &g_aethercore_console);
  uart_register("/dev/ttyS0", &g_aethercore_console);
}

void up_putc(int ch)
{
  riscv_lowputc((char)ch);
}
'''

KCONFIG_BLOCK = r'''

config AETHERCORE_UART
	bool "AetherCore simulation polling UART"
	default n
	select SERIAL
	select DEV_CONSOLE
	help
	  Use the native AetherCore byte-wide simulation UART at 0x10000000.
	  N2 deliberately supports polling TX only; timer scheduling is enabled in
	  N3 and UART RX plus PLIC are added in the bounded N4 stage.
'''

MAKE_BLOCK = r'''

ifeq ($(CONFIG_AETHERCORE_UART),y)
CHIP_CSRCS += aethercore_serial.c
endif
'''

START_OLD = r'''void riscv_earlyserialinit(void)
{
#ifdef CONFIG_16550_UART
  u16550_earlyserialinit();
#endif
}

void riscv_serialinit(void)
{
#ifdef CONFIG_16550_UART
  u16550_serialinit();
#endif
}
'''

START_NEW = r'''#ifdef CONFIG_AETHERCORE_UART
void aethercore_earlyserialinit(void);
void aethercore_serialinit(void);
#endif

void riscv_earlyserialinit(void)
{
#ifdef CONFIG_AETHERCORE_UART
  aethercore_earlyserialinit();
#elif defined(CONFIG_16550_UART)
  u16550_earlyserialinit();
#endif
}

void riscv_serialinit(void)
{
#ifdef CONFIG_AETHERCORE_UART
  aethercore_serialinit();
#elif defined(CONFIG_16550_UART)
  u16550_serialinit();
#endif
}
'''

IRQ_INIT_OLD = r'''  up_irq_save();

  /* Disable all global interrupts */
'''

IRQ_INIT_NEW = r'''  up_irq_save();

#if defined(CONFIG_AETHERCORE_UART) && defined(CONFIG_SUPPRESS_INTERRUPTS)
  /* N2 has no accepted PLIC contract yet.  Install the architectural trap
   * vector, but do not touch the wider QEMU PLIC register window.  N3 removes
   * interrupt suppression for the machine timer and N4 adds the bounded PLIC
   * UART RX path.
   */

#if defined(CONFIG_STACK_COLORATION) && CONFIG_ARCH_INTERRUPTSTACK > 15
  size_t n2_intstack_size = (CONFIG_ARCH_INTERRUPTSTACK & ~15);
  riscv_stack_color(g_intstackalloc, n2_intstack_size);
#endif

  riscv_exception_attach();
  return;
#endif

  /* Disable all global interrupts */
'''

BOOL_SETTINGS = {
    "CONFIG_AETHERCORE_UART": True,
    "CONFIG_16550_UART": False,
    "CONFIG_16550_UART0": False,
    "CONFIG_16550_UART0_SERIAL_CONSOLE": False,
    "CONFIG_FS_HOSTFS": False,
    "CONFIG_RISCV_SEMIHOSTING_HOSTFS": False,
    "CONFIG_SERIAL": True,
    "CONFIG_DEV_CONSOLE": True,
    "CONFIG_SUPPRESS_INTERRUPTS": True,
}

VALUE_SETTINGS = {
    "CONFIG_RAM_START": "0x80000000",
    "CONFIG_RAM_SIZE": "67108856",
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
        raise OverlayError(f"expected patch anchor missing in {path}")
    if count != 1:
        raise OverlayError(f"expected one patch anchor in {path}, found {count}")
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
    required = [
        root / "arch/risc-v/src/qemu-rv/Kconfig",
        root / "arch/risc-v/src/qemu-rv/Make.defs",
        root / "arch/risc-v/src/qemu-rv/qemu_rv_start.c",
        root / "arch/risc-v/src/qemu-rv/qemu_rv_irq.c",
        root / ".config",
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise OverlayError("missing NuttX overlay inputs: " + ", ".join(missing))

    serial = root / "arch/risc-v/src/qemu-rv/aethercore_serial.c"
    serial.write_text(UART_SOURCE)
    append_once(required[0], "config AETHERCORE_UART", KCONFIG_BLOCK)
    append_once(required[1], "CONFIG_AETHERCORE_UART", MAKE_BLOCK)
    replace_once(required[2], START_OLD, START_NEW)
    replace_once(required[3], IRQ_INIT_OLD, IRQ_INIT_NEW)

    config = required[4]
    for symbol, value in BOOL_SETTINGS.items():
        set_config(config, symbol, value)
    for symbol, value in VALUE_SETTINGS.items():
        set_config(config, symbol, value)

    # Fail closed on the exact frozen N2 address and interrupt contract.
    generated = serial.read_text()
    if "0x10000000u" not in generated or "putreg8" not in generated:
        raise OverlayError("generated UART does not implement byte-wide TX MMIO")
    irq_text = required[3].read_text()
    if IRQ_INIT_NEW not in irq_text:
        raise OverlayError("generated N2 IRQ boundary still reaches QEMU PLIC MMIO")
    config_text = config.read_text()
    required_config = (
        "CONFIG_AETHERCORE_UART=y",
        "# CONFIG_16550_UART is not set",
        "CONFIG_SUPPRESS_INTERRUPTS=y",
        "CONFIG_RAM_START=0x80000000",
        "CONFIG_RAM_SIZE=67108856",
    )
    for line in required_config:
        if line not in config_text.splitlines():
            raise OverlayError(f"resolved overlay config missing {line}")


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
    print(f"N2 overlay PASS: {args.nuttx_root.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
