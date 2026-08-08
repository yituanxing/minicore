#!/usr/bin/env python3
"""Fail-closed RISC-V ELF attribute/profile audit used by NuttX qualification."""

from __future__ import annotations

import argparse
from pathlib import Path
import re

_ARCH_RE = re.compile(r'Tag_RISCV_arch:\s*"([^"]+)"', re.I)
_VERSION_RE = re.compile(r"^(?P<name>[a-z][a-z0-9]*?)(?:\d+p\d+)?$")
_BASE_RE = re.compile(r"^rv(?P<xlen>32|64)i(?:\d+p\d+)?$")
_ATOMIC_RE = re.compile(
    r"\b(?:lr\.w|sc\.w|amo(?:swap|add|xor|and|or|min|max|minu|maxu)\.w)"
    r"(?:\.(?:aq|rl|aqrl))?\b"
)


def extract_arch(attributes: str) -> str:
    match = _ARCH_RE.search(attributes)
    if not match:
        raise ValueError("missing Tag_RISCV_arch")
    return match.group(1).lower()


def parse_arch(arch: str) -> tuple[int, set[str]]:
    """Return XLEN and exact canonical extension names.

    GNU readelf emits versioned components such as
    rv32i2p1_m2p0_a2p1_zicsr2p0_zifencei2p0_zmmul1p0.  Parse components,
    rather than looking for individual letters as substrings, so e.g. the
    letter 'f' inside unrelated text can never masquerade as the F extension.
    """

    parts = arch.lower().split("_")
    if not parts:
        raise ValueError(f"empty RISC-V architecture string: {arch!r}")

    base = _BASE_RE.fullmatch(parts[0])
    if not base:
        raise ValueError(f"unsupported/non-canonical base architecture: {arch}")

    extensions = {"i"}
    for component in parts[1:]:
        match = _VERSION_RE.fullmatch(component)
        if not match:
            raise ValueError(f"unparseable RISC-V extension component {component!r} in {arch}")
        extensions.add(match.group("name"))

    return int(base.group("xlen")), extensions


def count_word_atomics(disassembly: str) -> int:
    return len(_ATOMIC_RE.findall(disassembly.lower()))


def audit_image(name: str, attributes: str, disassembly: str) -> tuple[str, int]:
    arch = extract_arch(attributes)
    xlen, extensions = parse_arch(arch)

    if xlen != 32:
        raise ValueError(f"{name} is not RV32: {arch}")

    missing = {"i", "m", "a", "zicsr", "zifencei"} - extensions
    if missing:
        raise ValueError(f"{name} lost required extensions {sorted(missing)}: {arch}")

    forbidden = {"c", "f", "d", "v"} & extensions
    if forbidden:
        raise ValueError(f"{name} retained unsupported extensions {sorted(forbidden)}: {arch}")

    atomics = count_word_atomics(disassembly)
    if atomics == 0:
        raise ValueError(f"{name} contains no real RV32A word instruction")

    return arch, atomics


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kernel-attributes", type=Path, required=True)
    parser.add_argument("--kernel-disassembly", type=Path, required=True)
    parser.add_argument("--user-attributes", type=Path, required=True)
    parser.add_argument("--user-disassembly", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    lines: list[str] = []
    try:
        for name, attributes_path, disassembly_path in (
            ("kernel", args.kernel_attributes, args.kernel_disassembly),
            ("user", args.user_attributes, args.user_disassembly),
        ):
            arch, atomics = audit_image(
                name,
                attributes_path.read_text(errors="replace"),
                disassembly_path.read_text(errors="replace"),
            )
            lines += [f"{name}_arch={arch}", f"{name}_atomic_instructions={atomics}"]
    except ValueError as exc:
        raise SystemExit(f"N5-B FAIL: {exc}") from exc

    args.output.write_text("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
