#!/usr/bin/env python3
"""Fail-closed source verifier for one *partial* G5 retirement from QEMU RAM.

Input is the independently pinned, downloaded artifact from run 42489471408,
sv39-no-h, not an arbitrary QEMU image. The verifier cannot qualify full
architectural/device-state restoration or Debian boot.
"""

import argparse
import hashlib
import json
from pathlib import Path

SOURCE_RAM_SHA = "42129f32725e43c1db5bb38b400bb7d593bd92936271d0250e0d5273547810f2"
SOURCE_MANIFEST_SHA = "f97fb0f33377702eb43e582f5af2d6fc0ac4ba91b368627ea3d36d98a6ca776e"
ORIGINAL_REVIEWED_MANIFEST_SHA = "9e501e37ba0486160459c249732e53c31f29f131703152805be266c27187b203"
ROOT = 0x80000000
RAM_SIZE = 256 * 1024 * 1024
PC = 0xFFFFFFFF808C3490
SOURCE_REGS = {
    "pc": "0xffffffff808c3490",
    "priv": "0x1",
    "misa": "0x800000000014112d",
    "mstatus": "0xa000000a2",
    "satp": "0x80000000000856fe",
    "x2": "0xffffffc80000bec0",
    "pmpcfg0": "0x1f1b18181b1818",
    "pmpcfg2": "0x0",
}
PMP_ADDRS = [0x801000, 0x802FFE, 0x40001FF, 0x20013FFF, 0x20007FFF,
             0x307FFFF, 0xFFFFFFFFFFFFFFFF] + [0] * 9
PTE_ORACLE = ((0x856FEFF0, 0x23FFF401), (0x8FFFD020, 0x202800EB))
INSTRUCTION_PA = 0x80AC3490


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate(source: Path) -> dict:
    manifest_path = source / "export/manifest.json"
    ram_path = source / "export/ram-0x80000000-0x10000000.bin"
    insn_path = source / "source-insn.json"
    review_path = source / "source-review.json"
    oracle_path = source / "first-retire-reference.json"
    require(manifest_path.is_file() and ram_path.is_file() and insn_path.is_file(),
            "required authenticated artifact members missing")
    require(review_path.is_file() and oracle_path.is_file(),
            "original reviewed QEMU source provenance missing")
    require(sha(manifest_path) == SOURCE_MANIFEST_SHA, "manifest SHA changed")
    manifest = json.loads(manifest_path.read_text())
    require(manifest.get("arch_restore_ready") is False and
            manifest.get("rtl_cpu_injected") is False,
            "source falsely claims full architectural restoration")
    require(manifest.get("qemu_cpu_count") == 1, "not the one-hart source")
    mem = manifest.get("memory", {})
    require(mem.get("bytes") == RAM_SIZE and mem.get("start") == hex(ROOT) and
            mem.get("sha256") == SOURCE_RAM_SHA and
            ram_path.stat().st_size == RAM_SIZE, "DDR extent/source drift")
    require(sha(ram_path) == SOURCE_RAM_SHA, "DDR full bytes SHA changed")
    regs = manifest.get("qemu_gdb_registers", {})
    for name, expected in SOURCE_REGS.items():
        require(regs.get(name) == expected, f"source architectural {name} drift")
    for i, expected in enumerate(PMP_ADDRS):
        require(int(regs.get(f"pmpaddr{i}", "-1"), 0) == expected,
                f"QEMU PMP address {i} missing/drift")
    gprs = []
    for i in range(32):
        raw = regs.get(f"x{i}")
        require(isinstance(raw, str) and raw.startswith("0x"),
                f"QEMU XPR x{i} missing")
        value = int(raw, 16)
        require(0 <= value < (1 << 64), f"QEMU XPR x{i} out of range")
        gprs.append(value)
    require(gprs[0] == 0 and gprs[2] == int(SOURCE_REGS["x2"], 16),
            "QEMU x0/x2 inconsistent with first retire")
    require(len(manifest.get("unexported_device_and_rtl_state", [])) >= 4,
            "missing platform/RTL-state blocker disclosure")
    with ram_path.open("rb") as data:
        for pa, expected in PTE_ORACLE:
            data.seek(pa - ROOT)
            require(int.from_bytes(data.read(8), "little") == expected,
                    f"real source Sv39 PTE {pa:#x} drift")
        data.seek(INSTRUCTION_PA - ROOT)
        require(data.read(2) == bytes.fromhex("7971"),
                "real source first RVC instruction drift")
    insn = json.loads(insn_path.read_text())
    require(insn.get("source_pc") == SOURCE_REGS["pc"] and
            insn.get("translated_physical_pc") == hex(INSTRUCTION_PA) and
            insn.get("instruction_bytes_le") == "7971", "source walk evidence drift")
    review = json.loads(review_path.read_text())
    oracle = json.loads(oracle_path.read_text())
    require(review.get("source_manifest_sha256") == ORIGINAL_REVIEWED_MANIFEST_SHA and
            review.get("source_ram_sha256") == SOURCE_RAM_SHA and
            review.get("source_misa") == SOURCE_REGS["misa"] and
            review.get("source_pc") == SOURCE_REGS["pc"] and
            review.get("misa_exact_match_product_g5_debian") is True and
            review.get("rtl_handoff_pass") is False,
            "original QEMU source review provenance differs")
    require(oracle.get("source_manifest_sha256") == ORIGINAL_REVIEWED_MANIFEST_SHA and
            oracle.get("source_ram_sha256") == SOURCE_RAM_SHA and
            oracle.get("source_instruction") == "0x7179" and
            oracle.get("source_pc") == SOURCE_REGS["pc"] and
            oracle.get("x2_before") == SOURCE_REGS["x2"] and
            oracle.get("x2_expected_after") == "0xffffffc80000be90" and
            oracle.get("rtl_handoff_pass") is False,
            "QEMU first-retire oracle lineage differs")
    return {
        "qualification": "REAL_QEMU_SV39_H_OFF_DDR_AND_PARTIAL_ARCH_NOT_FULL_HANDOFF",
        "ram_sha256": SOURCE_RAM_SHA,
        "manifest_sha256": SOURCE_MANIFEST_SHA,
        "original_reviewed_manifest_sha256": ORIGINAL_REVIEWED_MANIFEST_SHA,
        "first_pc": hex(PC),
        "first_physical_pc": hex(INSTRUCTION_PA),
        "source_mstatus": regs["mstatus"],
        "source_satp": regs["satp"],
        "source_pmp": "all_16_addresses_and_two_cfg_words_verified",
        "source_xpr": "all_32_registers_present_and_verified",
        "gpr_values": gprs,
        "unrestored": "FPR_other_CSRS_PLIC_UART_CLINT_LRSC_and_full_retirement_diff",
        "rtl_pass": False,
    }


def emit_header(path: Path, gprs: list[int]) -> None:
    """Generate a deterministic C++ XPR preload table from verified bytes."""
    require(len(gprs) == 32 and gprs[0] == 0, "invalid captured register table")
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = ["// Autogenerated ONLY after exact QEMU artifact SHA verification.",
             "#pragma once", "#include <array>", "#include <cstdint>",
             "inline constexpr std::array<std::uint64_t,32> kCapturedGprs{{"]
    lines += [f"  0x{gprs[i]:016x}ULL{',' if i < 31 else ''} // x{i}"
              for i in range(32)]
    lines += ["}};"]
    path.write_text("\n".join(lines) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--gpr-header", type=Path, required=True)
    args = parser.parse_args()
    report = validate(args.source)
    gprs = report.pop("gpr_values")
    emit_header(args.gpr_header, gprs)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print("PRODUCT_C1_G5_REAL_QEMU_RAM_SOURCE_VERIFIED_NOT_RTL_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
