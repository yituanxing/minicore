#!/usr/bin/env python3
"""Verify ordered genuine serial-getty then login in bounded *QEMU* UART.

This supplements the existing native-root/PID1 checks in the QEMU observer.
It is strictly NOT a Product RTL proof; there is no checkpoint or SD SPI here.
"""

from pathlib import Path
import re
import sys


ANSI = re.compile(rb"\x1b\[[0-?]*[ -/]*[@-~]")
GETTY_STARTED = b"Started serial-getty@ttyS0.service"
LOGIN = re.compile(rb"(?:^|[\r\n])[^\r\n]*\blogin:")


def ordered_service_login(data: bytes) -> bool:
    clean = ANSI.sub(b"", data)
    service = clean.find(GETTY_STARTED)
    if service < 0:
        return False
    login = LOGIN.search(clean, service + len(GETTY_STARTED))
    return login is not None


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: product_sd1_getty_candidate_uart_proof.py BOOT-UART-LOG")
    uart = Path(sys.argv[1]).read_bytes()
    if not ordered_service_login(uart):
        sys.exit("CANDIDATE_QEMU_GETTY_LOGIN_UNPROVEN: native serial-getty was not observed before login")
    print("CANDIDATE_QEMU_ORDERED_GETTY_LOGIN_PASS_NOT_RTL")
