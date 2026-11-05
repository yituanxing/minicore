#!/usr/bin/env python3
"""Symbolize a Product G5 boot frontier without rerunning billions of RTL cycles.

The Image and vmlinux must BOTH match the hashes captured by the original G5
workflow.  A similar kernel configuration is not sufficient: even one changed
symbol layout makes a plausible-looking PC mapping dangerously misleading.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
from pathlib import Path
import re
import subprocess
import sys


HASH_LINE = re.compile(r"^([0-9a-fA-F]{64})\s+\*?(.+)$")
HEX_PC = re.compile(r"^0x[0-9a-fA-F]+$")
PRINTK = re.compile(r"^\[\s*\d+\.\d+\] .+")
SYMBOL = re.compile(r"^([0-9a-fA-F]+)\s+([tT])\s+(.+)$")


def die(message: str) -> None:
    raise ValueError(message)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def frozen_hashes(manifest: Path) -> tuple[str, str]:
    """Return (Image, vmlinux) hashes from the G5 build artifact manifest."""
    image = None
    vmlinux = None
    for line in manifest.read_text(encoding="utf-8").splitlines():
        match = HASH_LINE.fullmatch(line.strip())
        if not match:
            continue
        digest, name = match.groups()
        if name.endswith("/arch/riscv/boot/Image"):
            image = digest.lower()
        elif name.endswith("/vmlinux"):
            vmlinux = digest.lower()
    if image is None or vmlinux is None:
        die("G5 hash manifest must identify both exact Image and vmlinux")
    return image, vmlinux


def verify_identity(image: Path, vmlinux: Path, manifest: Path) -> tuple[str, str]:
    for label, path in (("Image", image), ("vmlinux", vmlinux), ("manifest", manifest)):
        if not path.is_file() or path.stat().st_size == 0:
            die(f"missing {label}: {path}")
    expected_image, expected_vmlinux = frozen_hashes(manifest)
    actual_image, actual_vmlinux = sha256(image), sha256(vmlinux)
    if actual_image != expected_image:
        die(f"Image SHA mismatch: actual={actual_image} expected={expected_image}")
    if actual_vmlinux != expected_vmlinux:
        die(f"vmlinux SHA mismatch: actual={actual_vmlinux} expected={expected_vmlinux}")
    return actual_image, actual_vmlinux


def parse_frontier(log: Path) -> tuple[dict[str, str], list[int], str]:
    if not log.is_file():
        die(f"missing boot log: {log}")
    lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
    terminals = [line for line in lines if line.startswith("L32_OPENSBI_TIMEOUT ")]
    if len(terminals) != 1:
        die(f"expected one terminal L32_OPENSBI_TIMEOUT, got {len(terminals)}")
    fields = dict(field.split("=", 1) for field in terminals[0].split()[1:] if "=" in field)
    if "last-exception-pc" not in fields or "recent-pcs" not in fields:
        die("terminal record lacks last-exception-pc or recent-pcs")
    addresses = [fields["last-exception-pc"], *fields["recent-pcs"].split(",")]
    if any(not HEX_PC.fullmatch(pc) for pc in addresses):
        die("invalid PC in terminal record")
    pc_values = sorted({int(pc, 16) for pc in addresses})
    if len(pc_values) < 2:
        die("terminal record has insufficient PC frontier")
    # The terminal summary omits UART silence length, whereas interval
    # progress contains it.  Extrapolate ONLY when UART bytes did not change.
    progress = [line for line in lines if line.startswith("L32_SIM_PROGRESS ")]
    if progress:
        last_progress = dict(item.split("=", 1) for item in progress[-1].split()[1:] if "=" in item)
        if (last_progress.get("uart-bytes") == fields.get("uart-bytes") and
                "uart-silent-cycles" in last_progress):
            remaining = int(fields["cycles"]) - int(last_progress["cycles"])
            if remaining >= 0:
                fields["uart-silent-cycles-inferred"] = str(
                    int(last_progress["uart-silent-cycles"]) + remaining
                )
    printk = [line for line in lines if PRINTK.match(line)]
    return fields, pc_values, (printk[-1] if printk else "<no kernel printk observed>")


def invoke(command: list[str]) -> str:
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True)
    except (OSError, subprocess.CalledProcessError) as exc:
        die(f"symbol tool failed: {command[0]}: {exc}")
    return result.stdout


def kernel_symbols(vmlinux: Path, prefix: str) -> tuple[list[int], list[str]]:
    elf_header = invoke([prefix + "readelf", "-h", str(vmlinux)])
    if not re.search(r"Machine:\s*RISC-V", elf_header):
        die("vmlinux is not a RISC-V ELF")
    entries: list[tuple[int, str]] = []
    for line in invoke([prefix + "nm", "-n", str(vmlinux)]).splitlines():
        match = SYMBOL.match(line)
        if match:
            entries.append((int(match.group(1), 16), match.group(3)))
    if not entries:
        die("vmlinux has no text symbols; refusing an unsymbolized success")
    entries.sort()
    return [entry[0] for entry in entries], [entry[1] for entry in entries]


def symbolize(vmlinux: Path, prefix: str, pcs: list[int]) -> None:
    addresses, names = kernel_symbols(vmlinux, prefix)
    for pc in pcs:
        index = bisect.bisect_right(addresses, pc) - 1
        if index < 0 or pc - addresses[index] > 0x10000:
            die(f"PC {pc:#x} cannot be associated with a nearby kernel text symbol")
        print(f"PC {pc:#018x}: {names[index]}+0x{pc - addresses[index]:x}")
    resolved = invoke([prefix + "addr2line", "-a", "-f", "-C", "-e", str(vmlinux), *[hex(pc) for pc in pcs]])
    print("\n=== addr2line (exact G5 vmlinux) ===")
    print(resolved.rstrip())

    last_exception = pcs[0]
    others = pcs[1:]
    # One small disassembly window for the retire frontier, one for the last
    # exception.  Never dump the entire large vmlinux into CI logs.
    for label, start, end in (
        ("last exception", max(0, last_exception - 32), last_exception + 48),
        ("recent retire PCs", max(0, min(others) - 32), max(others) + 48),
    ):
        print(f"\n=== {label}: {start:#x}..{end:#x} ===")
        disasm = invoke([
            prefix + "objdump", "-d", "-C", f"--start-address={start:#x}",
            f"--stop-address={end:#x}", str(vmlinux),
        ])
        if not any(re.search(r"^\s*[0-9a-f]+:\s", line) for line in disasm.splitlines()):
            die(f"empty disassembly for {label}; wrong ELF or address mapping")
        print(disasm.rstrip())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--boot-log", type=Path, required=True)
    parser.add_argument("--vmlinux", type=Path, required=True)
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--sha256-manifest", type=Path, required=True)
    parser.add_argument("--cross-prefix", required=True,
                        help="e.g. /opt/riscv64-linux/bin/riscv64-linux-")
    args = parser.parse_args()
    try:
        image_sha, elf_sha = verify_identity(args.image, args.vmlinux, args.sha256_manifest)
        fields, pcs, last_printk = parse_frontier(args.boot_log)
        print(f"PRODUCT_G5_EXACT_IMAGE_PASS image_sha256={image_sha} vmlinux_sha256={elf_sha}")
        print("last_kernel_printk=" + last_printk)
        print("terminal_cycles=" + fields.get("cycles", "<missing>"))
        print("terminal_commits=" + fields.get("commits", "<missing>"))
        print("uart_silent_cycles_inferred=" + fields.get("uart-silent-cycles-inferred", "<unavailable>"))
        # Preserve exception first and the retire range thereafter.
        exception = int(fields["last-exception-pc"], 16)
        retire = sorted({int(pc, 16) for pc in fields["recent-pcs"].split(",")})
        symbolize(args.vmlinux, args.cross_prefix, [exception, *retire])
        print("PRODUCT_G5_EXACT_PC_SYMBOLIZATION_PASS")
    except ValueError as exc:
        print("PRODUCT_G5_EXACT_PC_SYMBOLIZATION_FAIL: " + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
