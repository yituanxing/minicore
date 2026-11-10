#!/usr/bin/env python3
"""Read-only sizing preflight for a *separate* RAM-only Product Debian run.

This deliberately cannot qualify an SD1 boot or amend the fixed 256-MiB
Product memory/DTB contract.  It answers whether a pinned full Debian tree
would fit the existing D3 initramfs allowance before any costly rebuild.
"""

import argparse
import json
import re
import sys
from pathlib import Path


MIB = 1024 * 1024
RESERVED_KERNEL_MIB = 64
RESERVED_SERVICES_MIB = 64
RESERVED_BOOT_STAGING_MIB = 32
RESERVED_MIB = RESERVED_KERNEL_MIB + RESERVED_SERVICES_MIB + RESERVED_BOOT_STAGING_MIB


class BudgetError(ValueError):
    pass


def read_pinned_rootfs_result(path: Path) -> tuple[str, int, str]:
    """Read the builder's exact pass, stage, byte count and tree digest."""
    fields: dict[str, str] = {}
    try:
        contents = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise BudgetError(f"cannot read pinned rootfs manifest: {exc}") from exc
    status = "RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS"
    if contents.splitlines().count(status) != 1:
        raise BudgetError("rootfs manifest has no unique build PASS")
    for line in contents.splitlines():
        if "=" not in line or line == status:
            continue
        key, value = line.split("=", 1)
        if key in ("stage", "rootfs_bytes", "rootfs_tree_sha256"):
            if key in fields:
                raise BudgetError(f"duplicate rootfs manifest field: {key}")
            fields[key] = value
    stage = fields.get("stage", "")
    if stage != "sd1":
        raise BudgetError("only the signed SD1 full Debian rootfs can be reassessed")
    raw_bytes = fields.get("rootfs_bytes", "")
    if not re.fullmatch(r"[1-9][0-9]*", raw_bytes):
        raise BudgetError("rootfs_bytes missing or malformed")
    # This input needs the exact SD1 source-tree identity; the D3 builder has
    # its own separate 96-MiB gate and must not be conflated with SD1.
    digest = fields.get("rootfs_tree_sha256", "")
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise BudgetError("full Debian rootfs tree SHA256 missing")
    return stage, int(raw_bytes), digest


def budget(rootfs_bytes: int, ram_mib: int) -> dict[str, object]:
    if rootfs_bytes <= 0 or ram_mib < 256 or ram_mib > 8192:
        raise BudgetError("invalid rootfs size or RAM profile")
    allowance = (ram_mib - RESERVED_MIB) * MIB
    enough = rootfs_bytes <= allowance
    required_mib = (rootfs_bytes + RESERVED_MIB * MIB + MIB - 1) // MIB
    recommended_mib = 256
    while recommended_mib < required_mib:
        recommended_mib *= 2
    return {
        "verdict": (
            "RAM_BUDGET_INSUFFICIENT_NOT_RTL_PASS" if not enough else
            "RAM_BUDGET_FITS_ON_PAPER_NEW_MODEL_REQUIRED_NOT_RTL_PASS" if ram_mib != 256 else
            "RAM_BUDGET_FITS_ONLY_NOT_RTL_PASS"
        ),
        "ram_mib": ram_mib,
        "rootfs_bytes": rootfs_bytes,
        "reserved_kernel_mib": RESERVED_KERNEL_MIB,
        "reserved_services_mib": RESERVED_SERVICES_MIB,
        "reserved_boot_staging_mib": RESERVED_BOOT_STAGING_MIB,
        "available_rootfs_bytes": allowance,
        "minimum_ram_mib": required_mib,
        "recommended_power_of_two_ram_mib": recommended_mib,
        "current_product_ram_contract_mib": 256,
        "requires_new_host_ram_and_dtb_identity": ram_mib != 256,
        "native_spi_sd1_qualified": False,
        "real_product_rtl_run_verified": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rootfs-result", type=Path, required=True)
    parser.add_argument("--ram-mib", type=int, default=256)
    args = parser.parse_args()
    try:
        stage, rootfs_bytes, digest = read_pinned_rootfs_result(args.rootfs_result)
        result = budget(rootfs_bytes, args.ram_mib)
    except BudgetError as exc:
        print(f"FAST_RAM_DEBIAN_BUDGET_REJECTED: {exc}", file=sys.stderr)
        return 2
    result["rootfs_stage"] = stage
    result["rootfs_tree_sha256"] = digest
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["verdict"] == "RAM_BUDGET_FITS_ONLY_NOT_RTL_PASS" else 3


if __name__ == "__main__":
    raise SystemExit(main())
