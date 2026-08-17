#!/usr/bin/env python3
"""Generate the frozen single-hart L32 FDT as a DTB without host dtc."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import struct

FDT_MAGIC = 0xD00DFEED
FDT_BEGIN_NODE = 1
FDT_END_NODE = 2
FDT_PROP = 3
FDT_END = 9
FDT_VERSION = 17
FDT_LAST_COMP_VERSION = 16
CPU_INTC_PHANDLE = 1
PLIC_PHANDLE = 2
DEFAULT_CPU_ISA = "rv32ima_zicsr_zifencei_sstc"


def be32(value: int) -> bytes:
    return struct.pack(">I", value & 0xFFFFFFFF)


def cells(*values: int) -> bytes:
    return b"".join(be32(value) for value in values)


def string(value: str) -> bytes:
    return value.encode("ascii") + b"\0"


def stringlist(*values: str) -> bytes:
    return b"".join(string(value) for value in values)


def pad4(data: bytes) -> bytes:
    return data + b"\0" * ((-len(data)) & 3)


class DtbBuilder:
    def __init__(self) -> None:
        self.struct_block = bytearray()
        self.strings = bytearray()
        self.name_offsets: dict[str, int] = {}

    def name_offset(self, name: str) -> int:
        if name not in self.name_offsets:
            self.name_offsets[name] = len(self.strings)
            self.strings.extend(name.encode("ascii") + b"\0")
        return self.name_offsets[name]

    def begin_node(self, name: str) -> None:
        self.struct_block.extend(be32(FDT_BEGIN_NODE))
        self.struct_block.extend(pad4(name.encode("ascii") + b"\0"))

    def end_node(self) -> None:
        self.struct_block.extend(be32(FDT_END_NODE))

    def prop(self, name: str, value: bytes = b"") -> None:
        self.struct_block.extend(be32(FDT_PROP))
        self.struct_block.extend(be32(len(value)))
        self.struct_block.extend(be32(self.name_offset(name)))
        self.struct_block.extend(pad4(value))

    def finish(self) -> bytes:
        self.struct_block.extend(be32(FDT_END))
        reserve_map = struct.pack(">QQ", 0, 0)
        header_size = 40
        off_mem_rsvmap = header_size
        off_dt_struct = off_mem_rsvmap + len(reserve_map)
        off_dt_strings = off_dt_struct + len(self.struct_block)
        total_size = off_dt_strings + len(self.strings)
        header = struct.pack(
            ">10I",
            FDT_MAGIC,
            total_size,
            off_dt_struct,
            off_dt_strings,
            off_mem_rsvmap,
            FDT_VERSION,
            FDT_LAST_COMP_VERSION,
            0,
            len(self.strings),
            len(self.struct_block),
        )
        return header + reserve_map + bytes(self.struct_block) + bytes(self.strings)


def validate_cpu_isa(isa: str) -> str:
    normalized = isa.strip().lower()
    if not normalized.startswith("rv32i"):
        raise ValueError(f"L32 CPU ISA must be an RV32I-derived profile: {isa!r}")
    try:
        normalized.encode("ascii")
    except UnicodeEncodeError as exc:
        raise ValueError("L32 CPU ISA must be ASCII") from exc
    if "\x00" in normalized:
        raise ValueError("L32 CPU ISA must not contain NUL")
    return normalized


def build_l32_dtb(
    bootargs: str | None = None,
    isa: str = DEFAULT_CPU_ISA,
) -> bytes:
    cpu_isa = validate_cpu_isa(isa)
    b = DtbBuilder()
    b.begin_node("")
    b.prop("#address-cells", cells(2))
    b.prop("#size-cells", cells(2))
    b.prop("compatible", string("aethercore,l32"))
    b.prop("model", string("AetherCore RV32 L32"))

    b.begin_node("chosen")
    b.prop("stdout-path", string("/soc/serial@10000000"))
    if bootargs:
        b.prop("bootargs", string(bootargs))
    b.end_node()

    b.begin_node("cpus")
    b.prop("#address-cells", cells(1))
    b.prop("#size-cells", cells(0))
    b.prop("timebase-frequency", cells(10_000_000))

    b.begin_node("cpu@0")
    b.prop("device_type", string("cpu"))
    b.prop("reg", cells(0))
    b.prop("status", string("okay"))
    b.prop("compatible", string("riscv"))
    b.prop("riscv,isa", string(cpu_isa))
    b.prop("mmu-type", string("riscv,sv32"))

    b.begin_node("interrupt-controller")
    b.prop("#interrupt-cells", cells(1))
    b.prop("interrupt-controller")
    b.prop("compatible", string("riscv,cpu-intc"))
    b.prop("phandle", cells(CPU_INTC_PHANDLE))
    b.end_node()

    b.end_node()
    b.end_node()

    b.begin_node("memory@80000000")
    b.prop("device_type", string("memory"))
    b.prop("reg", cells(0, 0x80000000, 0, 0x10000000))
    b.end_node()

    b.begin_node("soc")
    b.prop("#address-cells", cells(2))
    b.prop("#size-cells", cells(2))
    b.prop("compatible", string("simple-bus"))
    b.prop("ranges")

    # QEMU-virt-compatible PLIC window. Context 0 (hart0 M-mode) is explicitly
    # absent using the standard 0xffffffff interrupt specifier, preserving the
    # hardware context index. Context 1 is hart0 Supervisor external interrupt,
    # so Linux maps enable/threshold/claim at 0x2080/0x201000/0x201004 while
    # OpenSBI correctly skips the absent M-mode context.
    b.begin_node("interrupt-controller@c000000")
    b.prop("compatible", stringlist("sifive,plic-1.0.0", "riscv,plic0"))
    b.prop("reg", cells(0, 0x0C000000, 0, 0x00400000))
    b.prop("#address-cells", cells(0))
    b.prop("#interrupt-cells", cells(1))
    b.prop("interrupt-controller")
    b.prop("riscv,ndev", cells(52))
    b.prop(
        "interrupts-extended",
        cells(CPU_INTC_PHANDLE, 0xFFFFFFFF, CPU_INTC_PHANDLE, 9),
    )
    b.prop("phandle", cells(PLIC_PHANDLE))
    b.end_node()

    b.begin_node("serial@10000000")
    b.prop("compatible", string("ns16550a"))
    b.prop("reg", cells(0, 0x10000000, 0, 0x8))
    b.prop("clock-frequency", cells(3_686_400))
    b.prop("current-speed", cells(115_200))
    b.prop("reg-shift", cells(0))
    b.prop("reg-io-width", cells(1))
    b.prop("interrupt-parent", cells(PLIC_PHANDLE))
    b.prop("interrupts", cells(10))
    b.prop("status", string("okay"))
    b.end_node()

    b.begin_node("mtimer@200bff8")
    b.prop("compatible", string("riscv,aclint-mtimer"))
    b.prop("reg", cells(0, 0x0200BFF8, 0, 0x8, 0, 0x02004000, 0, 0x8))
    b.prop("reg-names", stringlist("mtime", "mtimecmp"))
    b.prop("interrupts-extended", cells(CPU_INTC_PHANDLE, 7))
    b.end_node()

    b.end_node()
    b.end_node()
    return b.finish()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--bootargs")
    parser.add_argument(
        "--isa",
        default=DEFAULT_CPU_ISA,
        help="CPU riscv,isa property; defaults to the frozen RV32IMA profile",
    )
    args = parser.parse_args()

    try:
        cpu_isa = validate_cpu_isa(args.isa)
    except ValueError as exc:
        parser.error(str(exc))
    blob = build_l32_dtb(args.bootargs, cpu_isa)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest()

    lines = [
        "L32_DTB_RESULT: status=PASS",
        f"bytes={len(blob)}",
        f"sha256={digest}",
        "hart=0",
        f"isa={cpu_isa}",
        "mmu=sv32",
        "ram=0x80000000+0x10000000",
        "plic=0x0c000000+0x00400000",
        "plic_ndev=52",
        "plic_m_context=absent",
        "plic_s_context=1",
        "plic_s_ext_irq=9",
        "uart=ns16550a@0x10000000",
        "uart_irq=10",
        "mtime=0x0200bff8",
        "mtimecmp=0x02004000",
        "mtime_irq=7",
        "timebase_frequency=10000000",
    ]
    if args.bootargs:
        lines.append(f"bootargs={args.bootargs}")
    summary = "\n".join(lines) + "\n"
    if args.summary:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        args.summary.write_text(summary)
    print(summary, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
