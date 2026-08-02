#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

BASE = 0x80000000
MESSAGE = b"hello from U-mode via SYS_WRITE\n"
ECALL = 0x00000073
EBREAK = 0x00100073
SYSTEM_OPCODE = 0x73
STORE_OPCODE = 0x23


def load_labels(path: Path) -> dict[str, int]:
    labels: dict[str, int] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        name, address = line.split()
        labels[name] = int(address, 0)
    return labels


def word_at(image: bytes, address: int) -> int:
    offset = address - BASE
    if offset < 0 or offset + 4 > len(image) or offset % 4:
        raise SystemExit(f"invalid image word address: {address:#x}")
    return int.from_bytes(image[offset : offset + 4], "little")


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: check_rv32imu_syscall_contract.py IMAGE LABELS OUTPUT")

    image_path = Path(sys.argv[1])
    labels_path = Path(sys.argv[2])
    output_path = Path(sys.argv[3])
    image = image_path.read_bytes()
    labels = load_labels(labels_path)

    required = (
        "trap_handler",
        "sys_write",
        "sys_get_ticks",
        "sys_exit",
        "return_to_user",
        "user_main",
        "user_fail_write",
        "user_fail_ticks",
        "message",
    )
    missing = [name for name in required if name not in labels]
    if missing:
        raise SystemExit(f"missing labels: {', '.join(missing)}")

    ordered = [labels[name] for name in required]
    if ordered != sorted(ordered):
        raise SystemExit("kernel/user labels are not in the expected order")
    if any(address % 4 for address in ordered):
        raise SystemExit("an image label is not four-byte aligned")

    message_offset = labels["message"] - BASE
    observed_message = image[message_offset : message_offset + len(MESSAGE)]
    if observed_message != MESSAGE:
        raise SystemExit(
            f"message bytes changed: {observed_message!r}, expected {MESSAGE!r}"
        )

    # The formal user region is deliberately unable to touch any memory-mapped
    # device directly: it contains no Store instruction at all. SYSTEM
    # instructions are limited to ECALL plus unreachable EBREAK sentinels.
    user_start = labels["user_main"]
    user_end = labels["message"]
    ecall_sites: list[int] = []
    ebreak_sites: list[int] = []
    for address in range(user_start, user_end, 4):
        word = word_at(image, address)
        opcode = word & 0x7F
        if opcode == STORE_OPCODE:
            raise SystemExit(f"U-mode region contains a Store at {address:#x}")
        if opcode == SYSTEM_OPCODE:
            if word == ECALL:
                ecall_sites.append(address)
            elif word == EBREAK:
                ebreak_sites.append(address)
            else:
                raise SystemExit(
                    f"U-mode region contains privileged SYSTEM {word:#010x} at {address:#x}"
                )

    if len(ecall_sites) != 5:
        raise SystemExit(f"expected five static ECALL sites, found {len(ecall_sites)}")
    if len(ebreak_sites) != 3:
        raise SystemExit(f"expected three unreachable EBREAK sentinels, found {len(ebreak_sites)}")

    sys_write_start = labels["sys_write"]
    sys_write_end = labels["sys_get_ticks"]
    kernel_store_count = sum(
        1
        for address in range(sys_write_start, sys_write_end, 4)
        if (word_at(image, address) & 0x7F) == STORE_OPCODE
    )
    if kernel_store_count != 1:
        raise SystemExit(
            f"SYS_WRITE must contain exactly one UART Store site, found {kernel_store_count}"
        )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        "\n".join(
            (
                "status=PASS",
                f"user_start=0x{user_start:08x}",
                f"user_end=0x{user_end:08x}",
                f"user_bytes={user_end - user_start}",
                f"user_ecall_sites={len(ecall_sites)}",
                f"user_ebreak_sentinels={len(ebreak_sites)}",
                "user_store_sites=0",
                f"kernel_sys_write_store_sites={kernel_store_count}",
                f"message_bytes={len(MESSAGE)}",
            )
        )
        + "\n",
        encoding="utf-8",
    )
    print(output_path.read_text(encoding="utf-8"), end="")


if __name__ == "__main__":
    main()
