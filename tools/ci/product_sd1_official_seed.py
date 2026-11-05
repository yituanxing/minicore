#!/usr/bin/env python3
"""Fail-closed, offline Product SD1 source lock and seed cache identities.

The seed is an *artifact supply* gate: no simulator or guest boot is invoked.
The caller must separately run product_sim_sd1_manual.sh preflight before
sealing; that preflight validates the actual SDHC pin-level card model.
"""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
LOCK = ROOT / "software/rv64/product_sd1_official_seed.lock"
DEST = ROOT / "build/product-sd1-official-seed"
SUPPLIER = ROOT / "build/rv64-debian-sd1-official-rootfs"
PINS = {
    "RV64_SD1_DEBIAN_OCI_DIGEST": r"sha256:[a-fA-F0-9]{64}",
    "RV64_SD1_DEBIAN_SNAPSHOT_UTC": r"[0-9]{8}T[0-9]{6}Z",
    "RV64_SD1_DEBIAN_SECURITY_SNAPSHOT_UTC": r"[0-9]{8}T[0-9]{6}Z",
    "RV64_SD1_DEBIAN_INRELEASE_SHA256": r"[a-fA-F0-9]{64}",
    "RV64_SD1_DEBIAN_SECURITY_INRELEASE_SHA256": r"[a-fA-F0-9]{64}",
}
FILES = {
    "rootfs_result": "build/rv64-debian-rootfs/result.txt",
    "linux_result": "build/rv64-debian-linux/result.txt",
    "linux_image": "build/rv64-debian-linux/obj/arch/riscv/boot/Image",
    "linux_config": "build/rv64-debian-linux/obj/.config",
    "payload_result": "build/rv64-debian-boot/result.txt",
    "firmware": "build/rv64-debian-boot/opensbi/platform/generic/firmware/fw_payload.bin",
    "firmware_elf": "build/rv64-debian-boot/opensbi/platform/generic/firmware/fw_payload.elf",
    "dtb": "build/rv64-debian-boot/aethercore-rv64-initramfs.dtb",
    "sd_result": "build/rv64-debian-sd1-sd/result.txt",
    "sd_image": "build/rv64-debian-sd1-sd/sd-rootfs.img",
    "partition_image": "build/rv64-debian-sd1-sd/partition-rootfs.ext4",
    "payload_sha_evidence": "build/rv64-debian-boot/evidence/sha256.txt",
    "sd_sha_evidence": "build/rv64-debian-sd1-sd/evidence/sha256.txt",
    "supplier_provenance": "build/product-sd1-official-seed/source-provenance.txt",
    "supplier_packages": "build/product-sd1-official-seed/installed-packages.tsv",
    "supplier_release_checks": "build/product-sd1-official-seed/signed-release-check.log",
    "supplier_dpkg_audit": "build/product-sd1-official-seed/dpkg-audit.txt",
    "supplier_console": "build/product-sd1-official-seed/console-provisioning.txt",
}
HEADER = "PRODUCT_SD1_OFFICIAL_SEED_RESULT: status=OFFLINE_READY_UNVERIFIED"


def fail(message: str) -> None:
    raise ValueError(message)


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def fields(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    if not path.is_file():
        fail(f"missing manifest: {path}")
    for raw in path.read_text().splitlines():
        if not raw or raw.startswith("#") or raw == HEADER or raw.endswith(": status=PASS") or raw.endswith(": status=READY_UNVERIFIED") or raw.endswith(": status=OFFLINE_READY_UNVERIFIED"):
            continue
        if "=" not in raw:
            fail(f"malformed manifest entry in {path}: {raw!r}")
        key, value = raw.split("=", 1)
        if not key or key in result:
            fail(f"duplicate/empty manifest field {key!r} in {path}")
        result[key] = value
    return result


def read_lock(path: Path = LOCK) -> dict[str, str]:
    values = fields(path)
    if set(values) != set(PINS):
        fail(f"SD1 source lock requires exactly five independently verified pins (found {sorted(values)})")
    for key, pattern in PINS.items():
        if re.fullmatch(pattern, values[key]) is None:
            fail(f"{key} is unset or malformed: independently verify and commit the real official pin")
        if key.endswith("SHA256") or key.endswith("DIGEST"):
            values[key] = values[key].lower()
    return values


def check_content(values: dict[str, str], files: dict[str, str]) -> dict[str, str]:
    paths = {key: ROOT / rel for key, rel in FILES.items()}
    for key, path in paths.items():
        if not path.is_file() or (path.stat().st_size == 0 and key != "supplier_dpkg_audit"):
            fail(f"missing/empty exact SD1 supply file {key}: {path}")
    rootfs, linux, payload, sd = (fields(paths[key]) for key in
                                  ("rootfs_result", "linux_result", "payload_result", "sd_result"))
    provenance = fields(paths["supplier_provenance"])
    expected = {
        "oci": f"docker.io/library/debian@{values['RV64_SD1_DEBIAN_OCI_DIGEST']}",
        "main_snapshot": values["RV64_SD1_DEBIAN_SNAPSHOT_UTC"],
        "security_snapshot": values["RV64_SD1_DEBIAN_SECURITY_SNAPSHOT_UTC"],
        "main_inrelease_sha256": values["RV64_SD1_DEBIAN_INRELEASE_SHA256"],
        "security_inrelease_sha256": values["RV64_SD1_DEBIAN_SECURITY_INRELEASE_SHA256"],
    }
    for key, value in expected.items():
        if provenance.get(key) != value:
            fail(f"official source provenance {key} differs from the reviewed SD1 lock")
    if not re.fullmatch(r"[0-9a-f]{64}", provenance.get("archive_sha256", "")):
        fail("supplier rootfs archive exact SHA256 is missing")
    if (rootfs.get("stage") != "sd1" or rootfs.get("architecture") != "riscv64" or
            rootfs.get("abi") != "lp64d" or linux.get("stage") != "sd1" or
            linux.get("isa") != "rv64gc_zicsr_zifencei" or linux.get("abi") != "lp64d" or
            payload.get("linux_stage") != "sd1" or payload.get("product_board") != "1" or
            payload.get("spi_sd") != "1" or sd.get("stage") != "sd1"):
        fail("SD1 Product, kernel, ISA/ABI, or rootfs stages are not consistently pinned")
    if (fields(paths["supplier_console"]).get("console_login_credentials") != "NOT_PROVISIONED" or
            fields(paths["supplier_console"]).get("console_login_user") != "NONE"):
        fail("cannot place a provisioned password hash in a GitHub Actions seed cache")
    for manifest, field, label in (
        (linux, "image_sha256", "linux_image"),
        (linux, "config_sha256", "linux_config"),
        (payload, "firmware_bin_sha256", "firmware"),
        (payload, "fdt_sha256", "dtb"),
        (sd, "sd_image_sha256", "sd_image"),
        (sd, "partition_ext4_sha256", "partition_image"),
    ):
        if manifest.get(field) != files[label]:
            fail(f"SD1 stage manifest SHA disagrees with actual {label}")
    for manifest, field, label in (
        (sd, "rootfs_result_sha256", "rootfs_result"),
        (linux, "rootfs_result_sha256", "rootfs_result"),
        (payload, "rootfs_result_sha256", "rootfs_result"),
        (payload, "linux_result_sha256", "linux_result"),
        (payload, "sd_image_manifest_sha256", "sd_result"),
    ):
        if manifest.get(field) != files[label]:
            fail(f"SD1 cross-stage manifest binding {field} differs from actual {label}")
    if (rootfs.get("rootfs_tree_sha256") != sd.get("rootfs_tree_sha256") or
            rootfs.get("rootfs_tree_sha256") != linux.get("rootfs_tree_sha256") or
            rootfs.get("rootfs_tree_sha256") != payload.get("rootfs_tree_sha256")):
        fail("SD1 rootfs directory content identity differs across stages")
    for key, expected_path in (("image", paths["linux_image"]),
                               ("rootfs", ROOT / "build/rv64-debian-rootfs/rootfs")):
        manifest = linux if key == "image" else rootfs
        if manifest.get(key) != str(expected_path):
            fail(f"wrong absolute workspace path in SD1 {key} manifest")
    for key, expected_path in (("firmware_bin", paths["firmware"]), ("fdt", paths["dtb"])):
        if payload.get(key) != str(expected_path):
            fail(f"wrong absolute workspace path in SD1 firmware {key} manifest")
    if sd.get("sd_image") != str(paths["sd_image"]):
        fail("wrong absolute workspace path in SD1 image manifest")
    if sd.get("bootargs") != "root=/dev/mmcblk0p1 rootfstype=ext4 rootwait rw init=/sbin/init":
        fail("SD1 MBR/ext4 root bootargs drifted")
    if sd.get("disk_bytes") != str(paths["sd_image"].stat().st_size):
        fail("SD1 SD byte length differs from MBR manifest")
    if paths["sd_image"].stat().st_size > 2 * 1024**3:
        fail("SD1 source media exceeds explicit 2GiB seed cold/disk budget")
    # Source provenance is intentionally retained without the often-large
    # Docker export tar; its exact SHA is still bound into the seed manifest.
    return {"rootfs_archive_sha256": provenance["archive_sha256"],
            "rootfs_tree_sha256": rootfs["rootfs_tree_sha256"]}


def collect() -> tuple[dict[str, str], dict[str, str], dict[str, str]]:
    pins = read_lock()
    files = {key: digest(ROOT / path) for key, path in FILES.items()}
    facts = check_content(pins, files)
    return pins, files, facts


def seal() -> None:
    if DEST.exists():
        fail(f"seed evidence already exists: {DEST}; refuse overwrite")
    DEST.mkdir(parents=True)
    for name in ("source-provenance.txt", "installed-packages.tsv", "signed-release-check.log",
                 "dpkg-audit.txt", "console-provisioning.txt"):
        source = SUPPLIER / name
        if not source.is_file():
            fail(f"missing official supplier provenance: {source}")
        shutil.copy2(source, DEST / name)
    pins, files, facts = collect()
    sha = digest(LOCK)
    commit = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True).strip()
    entries = [("qualification", "OFFLINE_SD1_MEDIA_ONLY_NOT_RTL_NOT_LINUX_BOOT"),
               ("source_lock_sha256", sha), ("source_commit", commit)]
    entries += [(key, pins[key]) for key in sorted(pins)]
    entries += [(key, facts[key]) for key in sorted(facts)]
    entries += [(f"sha256_{key}", files[key]) for key in sorted(files)]
    (DEST / "seed-manifest.txt").write_text(HEADER + "\n" +
                                              "".join(f"{key}={value}\n" for key, value in entries))
    verify()


def verify() -> None:
    manifest = DEST / "seed-manifest.txt"
    if not manifest.is_file() or not manifest.read_text().startswith(HEADER + "\n"):
        fail("SD1 offline seed manifest has no exact READY_UNVERIFIED marker")
    recorded = fields(manifest)
    if recorded.get("qualification") != "OFFLINE_SD1_MEDIA_ONLY_NOT_RTL_NOT_LINUX_BOOT":
        fail("SD1 seed claims a qualification beyond offline media supply")
    if recorded.get("source_lock_sha256") != digest(LOCK):
        fail("SD1 seed is not tied to the reviewed exact source lock")
    pins, files, facts = collect()
    for key, value in pins.items():
        if recorded.get(key) != value:
            fail(f"SD1 seed source pin {key} changed")
    for key, value in files.items():
        if recorded.get(f"sha256_{key}") != value:
            fail(f"SD1 seed file {key} hash changed")
    for key, value in facts.items():
        if recorded.get(key) != value:
            fail(f"SD1 seed evidence {key} changed")
    print(f"PRODUCT_SD1_OFFICIAL_SEED_OFFLINE_IDENTITY_PASS sd_image_sha256={files['sd_image']}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("validate-lock", "seal", "verify"))
    args = parser.parse_args()
    if args.action == "validate-lock":
        for key, value in read_lock().items():
            print(f"{key}={value}")
    elif args.action == "seal":
        seal()
    else:
        verify()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(f"ERROR: official SD1 seed: {error}")
