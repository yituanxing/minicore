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


def rootfs_content_hashes(manifest: Path) -> dict[str, str]:
    required = {"bin/sh", "usr/bin/dpkg", "init", "etc/debian_version"}
    entries: dict[str, str] = {}
    if not manifest.is_file():
        die(f"missing Debian rootfs hash manifest: {manifest}")
    for line in manifest.read_text(encoding="utf-8").splitlines():
        match = HASH_LINE.fullmatch(line.strip())
        if not match:
            continue
        digest, name = match.groups()
        if "/rootfs/" not in name:
            die(f"rootfs hash entry is not bound to a rootfs tree: {name}")
        suffix = name.split("/rootfs/", 1)[1]
        if suffix in entries:
            die(f"duplicate rootfs hash entry: {suffix}")
        entries[suffix] = digest.lower()
    if set(entries) != required:
        die(f"rootfs hash manifest entries differ: {set(entries)}")
    return entries


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


def candidate_identity(
    image: Path, vmlinux: Path, manifest: Path, *,
    source_dir: Path, source_revision: str, config: Path, config_sha: str,
    rootfs_result: Path, rootfs_bytes: int, debian_digest: str,
    original_rootfs_manifest: Path, candidate_rootfs_manifest: Path,
    candidate_rootfs_dir: Path,
) -> tuple[str, str, str, str]:
    """Guard a useful but explicitly UNVERIFIED symbol candidate.

    The original vmlinux is unavailable.  Even when source, config and key
    rootfs files match, the reconstructed Image bytes can still differ for
    reasons not yet established.  Never conflate a candidate mapping with
    exact-symbol qualification.
    """
    for label, path in (
        ("Image", image), ("vmlinux", vmlinux), ("manifest", manifest),
        ("config", config), ("rootfs result", rootfs_result),
    ):
        if not path.is_file() or path.stat().st_size == 0:
            die(f"missing candidate {label}: {path}")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", source_revision):
        die("candidate source revision must be an exact 40-character commit")
    actual_revision = invoke(["git", "-C", str(source_dir), "rev-parse", "HEAD"]).strip()
    if actual_revision != source_revision:
        die(f"candidate source mismatch: actual={actual_revision} expected={source_revision}")
    if sha256(config) != config_sha.lower():
        die("candidate resolved.config SHA mismatch")
    result = rootfs_result.read_text(encoding="utf-8")
    if f"rootfs_bytes={rootfs_bytes}" not in result.splitlines():
        die("candidate Debian rootfs byte count mismatch")
    if f"image_digest={debian_digest}" not in result.splitlines():
        die("candidate official Debian image digest mismatch")
    original_contents = rootfs_content_hashes(original_rootfs_manifest)
    candidate_contents = rootfs_content_hashes(candidate_rootfs_manifest)
    if original_contents != candidate_contents:
        die("candidate Debian /init, /bin/sh, /usr/bin/dpkg or release content differs")
    for relative, expected in candidate_contents.items():
        actual_path = candidate_rootfs_dir / relative
        if not actual_path.is_file() or sha256(actual_path) != expected:
            die(f"candidate rootfs content does not match its hash manifest: {relative}")
    expected_image, expected_vmlinux = frozen_hashes(manifest)
    return sha256(image), sha256(vmlinux), expected_image, expected_vmlinux


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


def symbolize(vmlinux: Path, prefix: str, pcs: list[int], *, candidate_only: bool = False) -> None:
    addresses, names = kernel_symbols(vmlinux, prefix)
    for pc in pcs:
        index = bisect.bisect_right(addresses, pc) - 1
        if index < 0 or pc - addresses[index] > 0x10000:
            die(f"PC {pc:#x} cannot be associated with a nearby kernel text symbol")
        print(f"PC {pc:#018x}: {names[index]}+0x{pc - addresses[index]:x}")
    resolved = invoke([prefix + "addr2line", "-a", "-f", "-C", "-e", str(vmlinux), *[hex(pc) for pc in pcs]])
    provenance = "CANDIDATE_ONLY UNVERIFIED" if candidate_only else "EXACT"
    print(f"\n=== addr2line ({provenance} G5 vmlinux) ===")
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
    parser.add_argument("--candidate-only", action="store_true",
                        help="emit explicitly UNVERIFIED mapping if historical binary hashes differ")
    parser.add_argument("--source-dir", type=Path)
    parser.add_argument("--source-revision")
    parser.add_argument("--config", type=Path)
    parser.add_argument("--expected-config-sha256")
    parser.add_argument("--rootfs-result", type=Path)
    parser.add_argument("--expected-rootfs-bytes", type=int)
    parser.add_argument("--expected-debian-digest")
    parser.add_argument("--original-rootfs-manifest", type=Path)
    parser.add_argument("--candidate-rootfs-manifest", type=Path)
    parser.add_argument("--candidate-rootfs-dir", type=Path)
    args = parser.parse_args()
    try:
        if args.candidate_only:
            required = (
                args.source_dir, args.source_revision, args.config,
                args.expected_config_sha256, args.rootfs_result,
                args.expected_rootfs_bytes, args.expected_debian_digest,
                args.original_rootfs_manifest, args.candidate_rootfs_manifest,
                args.candidate_rootfs_dir,
            )
            if any(item is None for item in required):
                die("CANDIDATE_ONLY requires source, config and Debian rootfs identity guards")
            image_sha, elf_sha, old_image, old_elf = candidate_identity(
                args.image, args.vmlinux, args.sha256_manifest,
                source_dir=args.source_dir, source_revision=args.source_revision,
                config=args.config, config_sha=args.expected_config_sha256,
                rootfs_result=args.rootfs_result,
                rootfs_bytes=args.expected_rootfs_bytes,
                debian_digest=args.expected_debian_digest,
                original_rootfs_manifest=args.original_rootfs_manifest,
                candidate_rootfs_manifest=args.candidate_rootfs_manifest,
                candidate_rootfs_dir=args.candidate_rootfs_dir,
            )
            print("PRODUCT_G5_CANDIDATE_IMAGE_UNVERIFIED "
                  f"candidate_image_sha256={image_sha} original_image_sha256={old_image} "
                  f"candidate_vmlinux_sha256={elf_sha} original_vmlinux_sha256={old_elf}")
        else:
            image_sha, elf_sha = verify_identity(args.image, args.vmlinux, args.sha256_manifest)
            print(f"PRODUCT_G5_EXACT_IMAGE_PASS image_sha256={image_sha} vmlinux_sha256={elf_sha}")
        fields, pcs, last_printk = parse_frontier(args.boot_log)
        print("last_kernel_printk=" + last_printk)
        print("terminal_cycles=" + fields.get("cycles", "<missing>"))
        print("terminal_commits=" + fields.get("commits", "<missing>"))
        print("uart_silent_cycles_inferred=" + fields.get("uart-silent-cycles-inferred", "<unavailable>"))
        # Preserve exception first and the retire range thereafter.
        exception = int(fields["last-exception-pc"], 16)
        retire = sorted({int(pc, 16) for pc in fields["recent-pcs"].split(",")})
        symbolize(args.vmlinux, args.cross_prefix, [exception, *retire],
                  candidate_only=args.candidate_only)
        if args.candidate_only:
            print("PRODUCT_G5_CANDIDATE_PC_SYMBOLIZATION_UNVERIFIED no_exact_image_claim=1")
        else:
            print("PRODUCT_G5_EXACT_PC_SYMBOLIZATION_PASS")
    except ValueError as exc:
        kind = "CANDIDATE_ONLY_UNVERIFIED" if args.candidate_only else "EXACT"
        print(f"PRODUCT_G5_{kind}_PC_SYMBOLIZATION_FAIL: " + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
