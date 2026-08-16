#!/usr/bin/env python3
"""Fail-closed RISC-V ELF attribute/profile audit used by NuttX qualification."""

from __future__ import annotations

import argparse
from pathlib import Path
import re

_ARCH_RE = re.compile(r'Tag_RISCV_arch:\s*"([^"]+)"', re.I)
_TRAILING_VERSION_RE = re.compile(r"\d+p\d+$")
_EXTENSION_NAME_RE = re.compile(r"^[a-z][a-z0-9]*$")
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
    rv32i2p1_m2p0_a2p1_zicsr2p0_zifencei2p0_zmmul1p0. Strip only a trailing
    ``<major>p<minor>`` version from each extension component, then validate
    the remaining exact extension name. This avoids substring matching and
    also avoids a regex ambiguity where the extension name can accidentally
    absorb its numeric version (for example ``m2p0`` instead of ``m``).
    """

    parts = arch.lower().split("_")
    if not parts:
        raise ValueError(f"empty RISC-V architecture string: {arch!r}")

    base = _BASE_RE.fullmatch(parts[0])
    if not base:
        raise ValueError(f"unsupported/non-canonical base architecture: {arch}")

    extensions = {"i"}
    for component in parts[1:]:
        name = _TRAILING_VERSION_RE.sub("", component)
        if name == component or not _EXTENSION_NAME_RE.fullmatch(name):
            raise ValueError(f"unparseable RISC-V extension component {component!r} in {arch}")
        extensions.add(name)

    return int(base.group("xlen")), extensions


def count_word_atomics(disassembly: str) -> int:
    return len(_ATOMIC_RE.findall(disassembly.lower()))


def count_compressed_encodings(disassembly: str) -> int:
    # GNU objdump prints a 16-bit RVC encoding as four hex digits in the raw
    # instruction field, while 32-bit instructions use eight. Match the raw
    # field rather than instruction mnemonics so aliases do not affect the
    # architectural evidence.
    return sum(
        bool(re.match(r"^\s*[0-9a-f]+:\s+[0-9a-f]{4}\s", line, re.I))
        for line in disassembly.splitlines()
    )


def audit_image(
    name: str,
    attributes: str,
    disassembly: str,
    *,
    require_c: bool = False,
) -> tuple[str, int, int]:
    arch = extract_arch(attributes)
    xlen, extensions = parse_arch(arch)

    if xlen != 32:
        raise ValueError(f"{name} is not RV32: {arch}")

    required = {"i", "m", "a", "zicsr", "zifencei"}
    if require_c:
        required.add("c")
    missing = required - extensions
    if missing:
        raise ValueError(f"{name} lost required extensions {sorted(missing)}: {arch}")

    forbidden = {"f", "d", "v"} & extensions
    if not require_c and "c" in extensions:
        forbidden.add("c")
    if forbidden:
        raise ValueError(f"{name} retained unsupported extensions {sorted(forbidden)}: {arch}")

    atomics = count_word_atomics(disassembly)
    if atomics == 0:
        raise ValueError(f"{name} contains no real RV32A word instruction")

    compressed = count_compressed_encodings(disassembly)
    if require_c and compressed == 0:
        raise ValueError(f"{name} advertises C but contains no real 16-bit instruction encoding")
    if not require_c and compressed != 0:
        raise ValueError(f"{name} contains unexpected 16-bit instruction encodings")

    return arch, atomics, compressed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kernel-attributes", type=Path, required=True)
    parser.add_argument("--kernel-disassembly", type=Path, required=True)
    parser.add_argument("--user-attributes", type=Path, required=True)
    parser.add_argument("--user-disassembly", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--require-c",
        action="store_true",
        help="require the C extension and at least one real 16-bit encoding in kernel and userspace",
    )
    args = parser.parse_args()

    lines: list[str] = []
    try:
        for name, attributes_path, disassembly_path in (
            ("kernel", args.kernel_attributes, args.kernel_disassembly),
            ("user", args.user_attributes, args.user_disassembly),
        ):
            arch, atomics, compressed = audit_image(
                name,
                attributes_path.read_text(errors="replace"),
                disassembly_path.read_text(errors="replace"),
                require_c=args.require_c,
            )
            lines += [
                f"{name}_arch={arch}",
                f"{name}_atomic_instructions={atomics}",
                f"{name}_compressed_instructions={compressed}",
            ]
    except ValueError as exc:
        raise SystemExit(f"N5-B FAIL: {exc}") from exc

    args.output.write_text("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
