#!/usr/bin/env python3
"""Generic fail-closed RISC-V ELF ISA-profile audit.

This helper owns only generic ELF/ISA facts. Workload-specific requirements
(such as demanding that a particular image actually contains an atomic
instruction) remain with that workload's qualification layer.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess


ARCH_RE = re.compile(r'Tag_RISCV_arch:\s*"([^"]+)"', re.I)
TRAILING_VERSION_RE = re.compile(r"\d+p\d+$")
EXTENSION_NAME_RE = re.compile(r"^[a-z][a-z0-9]*$")
BASE_RE = re.compile(r"^rv(?P<xlen>32|64)i(?:\d+p\d+)?$")
COMPRESSED_LINE_RE = re.compile(r"^\s*[0-9a-f]+:\s+[0-9a-f]{4}\s", re.I)
EF_RISCV_RVC = 0x0001
EF_RISCV_FLOAT_ABI = 0x0006
PT_LOAD = 1
PF_X = 0x1


def extract_arch(attributes: str) -> str:
    match = ARCH_RE.search(attributes)
    if not match:
        raise ValueError("missing Tag_RISCV_arch")
    return match.group(1).lower()


def parse_arch(arch: str) -> tuple[int, set[str]]:
    parts = arch.lower().split("_")
    if not parts:
        raise ValueError(f"empty RISC-V architecture string: {arch!r}")
    base = BASE_RE.fullmatch(parts[0])
    if not base:
        raise ValueError(f"unsupported/non-canonical base architecture: {arch}")
    extensions = {"i"}
    for component in parts[1:]:
        name = TRAILING_VERSION_RE.sub("", component)
        if name == component or not EXTENSION_NAME_RE.fullmatch(name):
            raise ValueError(f"unparseable RISC-V extension component {component!r} in {arch}")
        extensions.add(name)
    return int(base.group("xlen")), extensions


def elf_flags(data: bytes) -> int:
    if len(data) < 52 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    if data[4] != 1:
        raise ValueError(f"expected ELF32, got EI_CLASS={data[4]}")
    if data[5] != 1:
        raise ValueError("expected little-endian ELF")
    machine = int.from_bytes(data[18:20], "little")
    if machine != 243:
        raise ValueError(f"expected EM_RISCV=243, got {machine}")
    return int.from_bytes(data[36:40], "little")


def executable_load_end(data: bytes) -> int:
    elf_flags(data)
    phoff = int.from_bytes(data[28:32], "little")
    phentsize = int.from_bytes(data[42:44], "little")
    phnum = int.from_bytes(data[44:46], "little")
    if phnum == 0:
        raise ValueError("ELF has no program headers")
    if phentsize < 32:
        raise ValueError(f"ELF32 program header entry is too small: {phentsize}")

    ends: list[int] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset + 32 > len(data):
            raise ValueError("ELF program header table is truncated")
        ph = data[offset : offset + 32]
        p_type = int.from_bytes(ph[0:4], "little")
        p_vaddr = int.from_bytes(ph[8:12], "little")
        p_memsz = int.from_bytes(ph[20:24], "little")
        p_flags = int.from_bytes(ph[24:28], "little")
        if p_type == PT_LOAD and (p_flags & PF_X):
            end = p_vaddr + p_memsz
            if end > 0x1_0000_0000:
                raise ValueError("ELF32 executable PT_LOAD overflows the 32-bit virtual address space")
            ends.append(end)
    if not ends:
        raise ValueError("ELF has no executable PT_LOAD segment")
    return max(ends)


def count_compressed_encodings(disassembly: str) -> int:
    return sum(bool(COMPRESSED_LINE_RE.match(line)) for line in disassembly.splitlines())


def audit_evidence(
    name: str,
    data: bytes,
    attributes: str,
    disassembly: str,
    *,
    require_c: bool,
) -> tuple[str, int, int]:
    flags = elf_flags(data)
    if flags & EF_RISCV_FLOAT_ABI:
        raise ValueError(f"{name} expected soft-float but e_flags=0x{flags:x}")

    arch = extract_arch(attributes)
    xlen, extensions = parse_arch(arch)
    if xlen != 32:
        raise ValueError(f"{name} is not RV32: {arch}")

    required = {"i", "m", "a", "zicsr", "zifencei"}
    missing = required - extensions
    if missing:
        raise ValueError(f"{name} lost required extensions {sorted(missing)}: {arch}")

    forbidden = {"f", "d", "v"} & extensions
    if forbidden:
        raise ValueError(f"{name} retained forbidden extensions {sorted(forbidden)}: {arch}")

    compressed = count_compressed_encodings(disassembly)
    if require_c:
        if "c" not in extensions:
            raise ValueError(f"{name} does not advertise C: {arch}")
        if not (flags & EF_RISCV_RVC):
            raise ValueError(f"{name} C profile lost RVC ELF flag: e_flags=0x{flags:x}")
        if compressed == 0:
            raise ValueError(f"{name} advertises C but contains no real 16-bit encoding")
    else:
        if "c" in extensions:
            raise ValueError(f"{name} unexpectedly advertises C: {arch}")
        if flags & EF_RISCV_RVC:
            raise ValueError(f"{name} unexpectedly carries RVC ELF flag: e_flags=0x{flags:x}")
        if compressed != 0:
            raise ValueError(f"{name} contains unexpected 16-bit encodings: {compressed}")

    return arch, flags, compressed


def run_tool(tool: str, *args: str) -> str:
    completed = subprocess.run(
        [tool, *args],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    return completed.stdout


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--elf", type=Path, required=True)
    parser.add_argument("--name", default="image")
    parser.add_argument("--readelf", required=True)
    parser.add_argument("--objdump", required=True)
    policy = parser.add_mutually_exclusive_group(required=True)
    policy.add_argument("--require-c", action="store_true")
    policy.add_argument("--forbid-c", action="store_true")
    parser.add_argument("--max-exec-vaddr-exclusive", type=lambda value: int(value, 0))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    try:
        data = args.elf.read_bytes()
        attributes = run_tool(args.readelf, "-A", str(args.elf))
        disassembly = run_tool(args.objdump, "-d", str(args.elf))
        arch, flags, compressed = audit_evidence(
            args.name,
            data,
            attributes,
            disassembly,
            require_c=args.require_c,
        )
        exec_vaddr_end = None
        if args.max_exec_vaddr_exclusive is not None:
            exec_vaddr_end = executable_load_end(data)
            if exec_vaddr_end >= args.max_exec_vaddr_exclusive:
                raise ValueError(
                    f"{args.name} executable PT_LOAD end 0x{exec_vaddr_end:x} is not below "
                    f"0x{args.max_exec_vaddr_exclusive:x}"
                )
    except (OSError, subprocess.CalledProcessError, ValueError) as exc:
        raise SystemExit(f"RISC-V ELF PROFILE FAIL: {exc}") from exc

    lines = [
        "RISC_V_ELF_PROFILE_PASS",
        f"name={args.name}",
        f"arch={arch}",
        f"elf_flags=0x{flags:x}",
        f"compressed_instructions={compressed}",
        f"require_c={1 if args.require_c else 0}",
    ]
    if exec_vaddr_end is not None:
        lines.append(f"exec_vaddr_end=0x{exec_vaddr_end:x}")
        lines.append(f"exec_vaddr_limit=0x{args.max_exec_vaddr_exclusive:x}")
    result = "\n".join(lines) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result)
    print(result, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
