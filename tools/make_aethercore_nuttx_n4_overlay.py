#!/usr/bin/env python3
"""Promote an applied N3 NuttX tree to the bounded N4 UART RX/PLIC profile."""

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
#include <nuttx/irq.h>
#include <nuttx/serial/serial.h>

#include "riscv_internal.h"
#include "chip.h"

#define AETHERCORE_UART_TX_ADDR       0x10000000u
#define AETHERCORE_UART_RX_BASE       0x10000100u
#define AETHERCORE_UART_RX_DATA       (AETHERCORE_UART_RX_BASE + 0x0u)
#define AETHERCORE_UART_RX_STATUS     (AETHERCORE_UART_RX_BASE + 0x4u)
#define AETHERCORE_UART_RX_CONTROL    (AETHERCORE_UART_RX_BASE + 0x8u)
#define AETHERCORE_UART_RX_READY      (1u << 0)
#define AETHERCORE_UART_RX_OVERRUN    (1u << 1)
#define AETHERCORE_UART_RX_IRQ_ENABLE (1u << 0)
#define AETHERCORE_UART_RX_IRQ        (RISCV_IRQ_EXT + 1)
#define AETHERCORE_RX_BUFSIZE         64
#define AETHERCORE_TX_BUFSIZE         256

struct aethercore_uart_priv_s
{
  int irq;
};

static bool aethercore_rxavailable(struct uart_dev_s *dev);

static int aethercore_interrupt(int irq, void *context, void *arg)
{
  struct uart_dev_s *dev = (struct uart_dev_s *)arg;

  if (dev != NULL && aethercore_rxavailable(dev))
    {
      uart_recvchars(dev);
    }

  return OK;
}

static int aethercore_setup(struct uart_dev_s *dev)
{
  return OK;
}

static void aethercore_shutdown(struct uart_dev_s *dev)
{
  putreg32(0, AETHERCORE_UART_RX_CONTROL);
}

static int aethercore_attach(struct uart_dev_s *dev)
{
  struct aethercore_uart_priv_s *priv = dev->priv;
  int ret;

  putreg32(0, AETHERCORE_UART_RX_CONTROL);
  ret = irq_attach(priv->irq, aethercore_interrupt, dev);
  if (ret == OK)
    {
      up_enable_irq(priv->irq);
    }

  return ret;
}

static void aethercore_detach(struct uart_dev_s *dev)
{
  struct aethercore_uart_priv_s *priv = dev->priv;

  putreg32(0, AETHERCORE_UART_RX_CONTROL);
  up_disable_irq(priv->irq);
  irq_detach(priv->irq);
}

static int aethercore_ioctl(struct file *filep, int cmd, unsigned long arg)
{
  return -ENOTTY;
}

static int aethercore_receive(struct uart_dev_s *dev, unsigned int *status)
{
  uint32_t rxstatus = getreg32(AETHERCORE_UART_RX_STATUS);

  if (status != NULL)
    {
      *status = (rxstatus & AETHERCORE_UART_RX_OVERRUN) != 0 ? 1u : 0u;
    }

  return (int)getreg32(AETHERCORE_UART_RX_DATA);
}

static void aethercore_rxint(struct uart_dev_s *dev, bool enable)
{
  irqstate_t flags = enter_critical_section();

  putreg32(enable ? AETHERCORE_UART_RX_IRQ_ENABLE : 0,
           AETHERCORE_UART_RX_CONTROL);
  leave_critical_section(flags);
}

static bool aethercore_rxavailable(struct uart_dev_s *dev)
{
  return (getreg32(AETHERCORE_UART_RX_STATUS) & AETHERCORE_UART_RX_READY) != 0;
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
static struct aethercore_uart_priv_s g_aethercore_priv =
{
  .irq = AETHERCORE_UART_RX_IRQ,
};

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
  .ops  = &g_aethercore_uart_ops,
  .priv = &g_aethercore_priv,
};

void riscv_lowputc(char ch)
{
  putreg8((uint8_t)ch, AETHERCORE_UART_TX_ADDR);
}

void aethercore_earlyserialinit(void)
{
  putreg32(0, AETHERCORE_UART_RX_CONTROL);
  putreg32(AETHERCORE_UART_RX_OVERRUN, AETHERCORE_UART_RX_STATUS);
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

config AETHERCORE_UART_RX_IRQ
	bool "AetherCore UART RX through PLIC"
	default n
	depends on AETHERCORE_UART
	help
	  Route the native AetherCore UART RX interrupt through PLIC source one.
	  N4 validates claim, dispatch, completion, ISR return, and NSH console I/O.
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

IRQ_N4 = r'''  riscv_exception_attach();
#ifdef CONFIG_AETHERCORE_UART_RX_IRQ
  /* AetherCore implements one 32-bit M-mode PLIC enable word.  Do not touch
   * QEMU_RV_PLIC_ENABLE2: that wider QEMU register is intentionally outside
   * the frozen SoC map and would raise a bus fault.  The serial attach path
   * enables source one after installing its ISR.
   */

  putreg32(0, QEMU_RV_PLIC_ENABLE1);
  putreg32(1, QEMU_RV_PLIC_PRIORITY + 4);
  putreg32(0, QEMU_RV_PLIC_THRESHOLD);
  SET_CSR(CSR_IE, IE_EIE);
#endif
#if defined(CONFIG_AETHERCORE_TIMER) || defined(CONFIG_AETHERCORE_UART_RX_IRQ)
  SET_CSR(CSR_STATUS, STATUS_IE);
#endif
  return;
#endif
'''

BOOL_SETTINGS = {
    "CONFIG_AETHERCORE_UART_RX_IRQ": True,
    "CONFIG_SUPPRESS_INTERRUPTS": False,
}

VALUE_SETTINGS = {
    "CONFIG_INIT_ENTRYPOINT": '"nsh_main"',
    "CONFIG_INIT_ENTRYNAME": '"nsh_main"',
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
        raise OverlayError(f"expected N3 patch anchor missing in {path}")
    if count != 1:
        raise OverlayError(f"expected one N3 patch anchor in {path}, found {count}")
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
    serial = root / "arch/risc-v/src/qemu-rv/aethercore_serial.c"
    config = root / ".config"
    required = [kconfig, irq, serial, config]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise OverlayError("missing N4 overlay inputs: " + ", ".join(missing))

    config_lines = config.read_text().splitlines()
    for line in ("CONFIG_AETHERCORE_UART=y", "CONFIG_AETHERCORE_TIMER=y"):
        if line not in config_lines:
            raise OverlayError(f"N4 requires the already-applied N3 contract: {line}")

    append_once(kconfig, "config AETHERCORE_UART_RX_IRQ", KCONFIG_BLOCK)
    replace_once(irq, IRQ_N3, IRQ_N4)
    serial.write_text(UART_SOURCE)

    for symbol, value in BOOL_SETTINGS.items():
        set_config(config, symbol, value)
    for symbol, value in VALUE_SETTINGS.items():
        set_config(config, symbol, value)

    serial_text = serial.read_text()
    required_serial = (
        "0x10000100u",
        "RISCV_IRQ_EXT + 1",
        "irq_attach(priv->irq, aethercore_interrupt, dev)",
        "uart_recvchars(dev)",
        "AETHERCORE_UART_RX_IRQ_ENABLE",
    )
    for marker in required_serial:
        if marker not in serial_text:
            raise OverlayError(f"generated N4 serial driver missing {marker}")

    irq_text = irq.read_text()
    required_irq = (
        "QEMU_RV_PLIC_ENABLE1",
        "QEMU_RV_PLIC_PRIORITY + 4",
        "QEMU_RV_PLIC_THRESHOLD",
        "SET_CSR(CSR_IE, IE_EIE)",
    )
    for marker in required_irq:
        if marker not in irq_text:
            raise OverlayError(f"generated N4 IRQ boundary missing {marker}")
    if "putreg32(0, QEMU_RV_PLIC_ENABLE2);" in irq_text.split("#ifdef CONFIG_AETHERCORE_UART", 1)[1].split("return;", 1)[0]:
        raise OverlayError("N4 AetherCore path touches unsupported PLIC enable word two")


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
    print(f"N4 overlay PASS: {args.nuttx_root.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
