#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

BASE = 0x80000000
KERNEL_LIMIT = 0x80001000
USER_TEXT = 0x80001000
USER_DATA = 0x80002000
USER_LIMIT = 0x80003000
MESSAGE = b"PMP isolation via SYS_WRITE\n"

LOAD_OPCODE = 0x03
JALR_OPCODE = 0x67
STORE_OPCODE = 0x23
SYSTEM_OPCODE = 0x73
ECALL = 0x00000073
EBREAK = 0x00100073

PMP_CSRS = {0x3A0, 0x3B0, 0x3B1, 0x3B2}


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


def words_in(image: bytes, start: int, end: int) -> list[tuple[int, int]]:
    return [(address, word_at(image, address)) for address in range(start, end, 4)]


def require_opcode(image: bytes, labels: dict[str, int], label: str, opcode: int) -> None:
    observed = word_at(image, labels[label]) & 0x7F
    if observed != opcode:
        raise SystemExit(
            f"{label} opcode changed: {observed:#x}, expected {opcode:#x}"
        )


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: check_rv32imu_pmp_contract.py IMAGE LABELS OUTPUT")

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
        "forbidden_kernel_target",
        "user_main",
        "attack_uart_store",
        "attack_kernel_load",
        "attack_kernel_store",
        "attack_text_store",
        "attack_kernel_execute",
        "after_kernel_execute",
        "attack_data_execute",
        "after_data_execute",
        "message",
        "marker",
        "forbidden_data_target",
    )
    missing = [name for name in required if name not in labels]
    if missing:
        raise SystemExit(f"missing labels: {', '.join(missing)}")

    if not all(BASE <= labels[name] < KERNEL_LIMIT for name in (
        "trap_handler", "sys_write", "sys_get_ticks", "sys_exit",
        "return_to_user", "forbidden_kernel_target"
    )):
        raise SystemExit("a Machine-mode label escaped the kernel PMP page")
    if not all(USER_TEXT <= labels[name] < USER_DATA for name in (
        "user_main", "attack_uart_store", "attack_kernel_load",
        "attack_kernel_store", "attack_text_store", "attack_kernel_execute",
        "after_kernel_execute", "attack_data_execute", "after_data_execute"
    )):
        raise SystemExit("a user instruction label escaped the R-X PMP page")
    if not all(USER_DATA <= labels[name] < USER_LIMIT for name in (
        "message", "marker", "forbidden_data_target"
    )):
        raise SystemExit("a user data label escaped the RW- PMP page")

    message_offset = labels["message"] - BASE
    observed_message = image[message_offset : message_offset + len(MESSAGE)]
    if observed_message != MESSAGE:
        raise SystemExit(
            f"message bytes changed: {observed_message!r}, expected {MESSAGE!r}"
        )

    require_opcode(image, labels, "attack_uart_store", STORE_OPCODE)
    require_opcode(image, labels, "attack_kernel_load", LOAD_OPCODE)
    require_opcode(image, labels, "attack_kernel_store", STORE_OPCODE)
    require_opcode(image, labels, "attack_text_store", STORE_OPCODE)
    require_opcode(image, labels, "attack_kernel_execute", JALR_OPCODE)
    require_opcode(image, labels, "attack_data_execute", JALR_OPCODE)

    user_words = words_in(image, labels["user_main"], USER_DATA)
    user_store_sites = sum(1 for _, word in user_words if (word & 0x7F) == STORE_OPCODE)
    user_load_sites = sum(1 for _, word in user_words if (word & 0x7F) == LOAD_OPCODE)
    user_jalr_sites = sum(1 for _, word in user_words if (word & 0x7F) == JALR_OPCODE)
    if user_store_sites != 5:
        raise SystemExit(f"expected five user Store sites, found {user_store_sites}")
    if user_load_sites != 3:
        raise SystemExit(f"expected three user Load sites, found {user_load_sites}")
    if user_jalr_sites != 2:
        raise SystemExit(f"expected two user JALR attack sites, found {user_jalr_sites}")

    user_privileged_system: list[int] = []
    user_ecalls = 0
    user_ebreaks = 0
    for address, word in user_words:
        if (word & 0x7F) != SYSTEM_OPCODE:
            continue
        if word == ECALL:
            user_ecalls += 1
        elif word == EBREAK:
            user_ebreaks += 1
        else:
            user_privileged_system.append(address)
    if user_privileged_system:
        formatted = ", ".join(f"{address:#x}" for address in user_privileged_system)
        raise SystemExit(f"user text contains privileged SYSTEM instructions at {formatted}")

    kernel_words = words_in(image, BASE, USER_TEXT)
    pmp_csr_writes: dict[int, int] = {address: 0 for address in PMP_CSRS}
    for _, word in kernel_words:
        if (word & 0x7F) != SYSTEM_OPCODE or ((word >> 12) & 0x7) == 0:
            continue
        csr = word >> 20
        if csr in pmp_csr_writes and ((word >> 12) & 0x7) == 1:
            pmp_csr_writes[csr] += 1
    if any(count != 1 for count in pmp_csr_writes.values()):
        raise SystemExit(f"PMP CSR setup is not one-shot: {pmp_csr_writes}")

    sys_write_stores = sum(
        1
        for _, word in words_in(image, labels["sys_write"], labels["sys_get_ticks"])
        if (word & 0x7F) == STORE_OPCODE
    )
    if sys_write_stores != 1:
        raise SystemExit(
            f"SYS_WRITE must contain one UART Store site, found {sys_write_stores}"
        )

    digest = hashlib.sha256(image).hexdigest()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        "\n".join(
            (
                "status=PASS",
                f"binary_sha256={digest}",
                f"kernel_start=0x{BASE:08x}",
                f"kernel_end=0x{KERNEL_LIMIT:08x}",
                f"user_text_start=0x{USER_TEXT:08x}",
                f"user_text_end=0x{USER_DATA:08x}",
                f"user_data_start=0x{USER_DATA:08x}",
                f"user_data_end=0x{USER_LIMIT:08x}",
                "pmp_csr_writes=4",
                f"user_store_sites={user_store_sites}",
                f"user_load_sites={user_load_sites}",
                f"user_jalr_attack_sites={user_jalr_sites}",
                f"user_ecall_sites={user_ecalls}",
                f"user_ebreak_sentinels={user_ebreaks}",
                f"kernel_sys_write_store_sites={sys_write_stores}",
                "expected_fault_stages=6",
            )
        )
        + "\n",
        encoding="utf-8",
    )
    print(output_path.read_text(encoding="utf-8"), end="")


if __name__ == "__main__":
    main()
