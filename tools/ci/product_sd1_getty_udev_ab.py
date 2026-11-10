#!/usr/bin/env python3
"""Isolated QEMU-only 110s udev coldplug A/B against already built SD1 media.

Original signed/candidate media is NEVER written or mounted.  Independently
clone its MBR and ext4 partition, then use debugfs on the *clone* only.  Both
variants delay native systemd-udev-trigger.service by 110s; baseline removes
the candidate's 10min device deadline, while candidate preserves it.  This
is an experimental causality check, NOT Product SPI-SD or RTL login evidence.

Default action only prepares variants.  Pass --run to spend QEMU guest cycles.
If the default-90s baseline does not visibly fail, report INCONCLUSIVE, never
claim that the extended deadline fixed a problem which was not reproduced.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import struct
import subprocess
import sys
import time

if __package__:
    from .product_sd1_getty_candidate_uart_proof import ANSI, ordered_service_login
    from .product_sd1_qemu_watch import BootProof, CursorQueryResponder, CURSOR_POSITION_REPLY
else:
    from product_sd1_getty_candidate_uart_proof import ANSI, ordered_service_login
    from product_sd1_qemu_watch import BootProof, CursorQueryResponder, CURSOR_POSITION_REPLY


ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = ROOT / "build/rv64-debian-sd1-sd"
ORACLE_DIR = ROOT / "build/rv64-debian-sd1-qemu-oracle"
OUTPUT_DIR = ROOT / "build/product-sd1-getty-udev-ab"
TIMEOUT_CONFIG = "/etc/systemd/system.conf.d/90-sd1-device-coldplug.conf"
DELAY_DIR = "/etc/systemd/system/systemd-udev-trigger.service.d"
DELAY_CONFIG = f"{DELAY_DIR}/90-sd1-ab-delay.conf"
SECTOR = 512
FIRST_LBA = 2048
PARTITION_OFFSET = SECTOR * FIRST_LBA
BLOCK = 4 * 1024 * 1024
DEVICE_TIMEOUT = b"Timed out waiting for device dev-ttyS0.device"

def has_serial_getty_dependency_failure(clean_serial: bytes) -> bool:
    """Match exact or systemd-ellipsized serial-getty dependency failure."""
    return any(
        b"Dependency failed for" in line and
        b"serial-getty" in line and
        (b"serial-getty@ttyS0.service" in line or b"Serial Getty on ttyS0" in line)
        for line in clean_serial.splitlines()
    )


class QualificationError(ValueError):
    pass


def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(BLOCK), b""):
            sha.update(chunk)
    return sha.hexdigest()


def fields(path: Path) -> dict[str, str]:
    if not path.is_file() or path.is_symlink():
        raise QualificationError(f"missing regular manifest: {path}")
    result: dict[str, str] = {}
    for line in path.read_text().splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key in result or not key or not value:
            raise QualificationError(f"duplicate/empty manifest field {key!r}")
        result[key] = value
    return result


def ensure_file(path: Path) -> None:
    if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
        raise QualificationError(f"missing nonempty regular file: {path}")


def validate_media(source: Path, partition: Path, sd_result: Path,
                   kernel: Path, oracle_result: Path) -> dict[str, str]:
    for path in (source, partition, sd_result, kernel, oracle_result):
        ensure_file(path)
    sd, oracle = fields(sd_result), fields(oracle_result)
    if (sd.get("stage") != "sd1" or sd.get("partition_table") != "MBR" or
            sd.get("partition1_first_lba") != "2048" or
            sd.get("partition1_type") != "0x83" or
            oracle.get("qualification") != "QEMU_VIRT_ONLY_NOT_PRODUCT_SPI_RTL"):
        raise QualificationError("SD1 source and QEMU kernel qualification mismatch")
    actual = {
        "source_sd_sha256": digest(source),
        "source_partition_sha256": digest(partition),
        "oracle_image_sha256": digest(kernel),
    }
    for key, expected in (
        ("source_sd_sha256", sd.get("sd_image_sha256")),
        ("source_partition_sha256", sd.get("partition_ext4_sha256")),
        ("oracle_image_sha256", oracle.get("oracle_image_sha256")),
    ):
        if actual[key] != expected:
            raise QualificationError(f"{key} differs from SHA-verified candidate media")
    if oracle.get("sd_image_sha256") != actual["source_sd_sha256"]:
        raise QualificationError("QEMU kernel was built against a different SD image")
    disk_bytes = source.stat().st_size
    if disk_bytes != int(sd.get("disk_bytes", "0")):
        raise QualificationError("source disk size differs from manifest")
    with source.open("rb") as disk:
        mbr = disk.read(SECTOR)
        disk.seek(PARTITION_OFFSET + 1024 + 56)
        ext_magic = disk.read(2)
    if mbr[510:512] != b"\x55\xaa" or ext_magic != b"\x53\xef":
        raise QualificationError("MBR or ext4 magic differs from SD1 media contract")
    entry = mbr[446:462]
    if (entry[4] != 0x83 or struct.unpack_from("<I", entry, 8)[0] != FIRST_LBA or
            (FIRST_LBA + struct.unpack_from("<I", entry, 12)[0]) * SECTOR != disk_bytes):
        raise QualificationError("MBR p1 geometry is not expected SD1 full-disk ext4")
    if partition.stat().st_size != disk_bytes - PARTITION_OFFSET:
        raise QualificationError("external ext4 partition size differs from MBR p1 extent")
    return actual


def debugfs(partition: Path, command: str, *, write: bool = False) -> bytes:
    args = ["debugfs"]
    if write:
        args.append("-w")
    args += ["-R", command, str(partition)]
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            check=False, timeout=60)
    if result.returncode:
        raise QualificationError(
            f"debugfs {command.split(' ', 1)[0]} failed: {result.stderr.decode(errors='replace')[-300:]}")
    # debugfs may return 0 after filesystem-level failure (printed on stderr).
    if write and re.search(rb"(File not found|File exists|Operation not permitted|Command not found|No such file)",
                           result.stderr, re.I):
        raise QualificationError(f"debugfs rejected {command!r}: {result.stderr[-300:]!r}")
    return result.stdout


def prepare_variant(source: Path, partition: Path, output: Path,
                    *, delay_seconds: int, baseline: bool) -> dict[str, str]:
    name = "baseline" if baseline else "extended"
    part_clone = output / f"{name}.ext4"
    raw_clone = output / f"{name}.img"
    with partition.open("rb") as src, part_clone.open("xb") as dst:
        shutil.copyfileobj(src, dst, BLOCK)
    if baseline:
        existing = debugfs(part_clone, f"cat {TIMEOUT_CONFIG}")
        if b"DefaultDeviceTimeoutSec=10min" not in existing:
            raise QualificationError("baseline source lacks candidate timeout to remove")
        debugfs(part_clone, f"rm {TIMEOUT_CONFIG}", write=True)
        if b"DefaultDeviceTimeoutSec=10min" in debugfs(part_clone, f"cat {TIMEOUT_CONFIG}"):
            raise QualificationError("baseline timeout removal did not take effect")
    else:
        if b"DefaultDeviceTimeoutSec=10min" not in debugfs(part_clone, f"cat {TIMEOUT_CONFIG}"):
            raise QualificationError("extended candidate timeout missing in ext4 media")
    # Delaying udev-trigger is narrower than delaying systemd-udevd startup:
    # the device coldplug event is withheld while systemd's device job waits.
    if debugfs(part_clone, f"cat {DELAY_CONFIG}").strip():
        raise QualificationError("SD media already contains a competing udev-delay drop-in")
    debugfs(part_clone, f"mkdir {DELAY_DIR}", write=True)
    delay_conf = output / f"{name}-udev-delay.conf"
    delay_conf.write_text(
        "[Service]\n"
        f"ExecStartPre=/usr/bin/sleep {delay_seconds}\n"
        f"TimeoutStartSec={delay_seconds + 130}s\n"
    )
    debugfs(part_clone, f"write {delay_conf} {DELAY_CONFIG}", write=True)
    if f"ExecStartPre=/usr/bin/sleep {delay_seconds}".encode() not in debugfs(part_clone, f"cat {DELAY_CONFIG}"):
        raise QualificationError("injected udev delay missing from cloned ext4")
    # Fresh raw disk, preserving exactly the original 1MiB MBR/padding prefix.
    # Never sparse-overlay a nonzero pre-existing partition: zeroed extents
    # would otherwise leave stale bytes behind.
    with raw_clone.open("xb") as dest, source.open("rb") as original:
        header = original.read(PARTITION_OFFSET)
        if len(header) != PARTITION_OFFSET:
            raise QualificationError("source MBR header is truncated")
        dest.write(header)
        with part_clone.open("rb") as src:
            shutil.copyfileobj(src, dest, BLOCK)
    if raw_clone.stat().st_size != source.stat().st_size:
        raise QualificationError("derived disk size differs from signed source extent")
    return {
        f"{name}_sd_sha256": digest(raw_clone),
        f"{name}_partition_sha256": digest(part_clone),
        f"{name}_injected_delay_sha256": digest(delay_conf),
    }


def qemu_probe(kernel: Path, image: Path, output: Path, *, mode: str,
               timeout_seconds: int) -> dict[str, object]:
    """QEMU bounded UART observer: no 90s idle watchdog during injected sleep."""
    log = output / f"{mode}-uart.log"
    result = output / f"{mode}-qemu-result.txt"
    if log.exists() or result.exists():
        raise QualificationError(f"refusing to overwrite QEMU output for {mode}")
    command = [
        "qemu-system-riscv64", "-machine", "virt", "-m", "256M", "-smp", "1",
        "-nographic", "-monitor", "none", "-serial", "stdio", "-bios", "default",
        "-kernel", str(kernel),
        "-append", ("earlycon=sbi console=ttyS0,115200 root=/dev/vda1 "
                    "rootfstype=ext4 rootwait rw init=/sbin/init systemd.unit=multi-user.target "
                    "systemd.getty_auto=no systemd.mask=console-getty.service"),
        "-drive", f"file={image},format=raw,if=none,id=sd1,snapshot=on",
        "-device", "virtio-blk-device,drive=sd1", "-no-reboot",
    ]
    proof = BootProof()
    cursor = CursorQueryResponder()
    started = time.monotonic()
    timed_out_device = False
    getty_dependency_failed = False
    bytes_seen = 0
    raw_tail = b""
    with log.open("xb") as serial, subprocess.Popen(
        command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, bufsize=0,
    ) as guest:
        assert guest.stdin is not None and guest.stdout is not None
        poller = selectors.DefaultSelector()
        poller.register(guest.stdout, selectors.EVENT_READ)
        try:
            while time.monotonic() - started < timeout_seconds:
                ready = poller.select(timeout=0.5)
                if ready:
                    chunk = os.read(guest.stdout.fileno(), 65536)
                    if not chunk:
                        if guest.poll() is not None:
                            break
                        continue
                    bytes_seen += len(chunk)
                    if bytes_seen > 64 * 1024 * 1024:
                        raise QualificationError("QEMU UART exceeded 64MiB evidence budget")
                    serial.write(chunk)
                    serial.flush()
                    proof.observe(chunk, time.monotonic() - started)
                    raw_tail = (raw_tail + chunk)[-32768:]
                    clean_tail = ANSI.sub(b"", raw_tail)
                    timed_out_device |= DEVICE_TIMEOUT in clean_tail
                    getty_dependency_failed |= has_serial_getty_dependency_failure(clean_tail)
                    if proof.failure:
                        raise QualificationError(f"QEMU Linux failed: {proof.failure}")
                    if mode == "baseline" and timed_out_device and getty_dependency_failed:
                        break
                    if proof.login_prompt:
                        break
                    for _ in range(cursor.feed(
                            chunk, pid1_seen=proof.pid1_executed,
                            login_seen=proof.login_prompt)):
                        os.write(guest.stdin.fileno(), CURSOR_POSITION_REPLY)
                elif guest.poll() is not None:
                    break
        finally:
            poller.close()
            if guest.poll() is None:
                guest.terminate()
                try:
                    guest.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    guest.kill()
                    guest.wait(timeout=5)
    observed = log.read_bytes()
    getty_login = ordered_service_login(observed)
    evidence: dict[str, object] = {
        "mode": mode,
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "uart_bytes": bytes_seen,
        "native_ext4_root": proof.root_mounted,
        "native_pid1": proof.pid1_executed,
        "native_login_prompt": proof.login_prompt,
        "ordered_serial_getty_login": getty_login,
        "console_getty_started": b"Started console-getty.service" in ANSI.sub(b"", observed),
        "dev_ttyS0_device_timeout": timed_out_device,
        "serial_getty_dependency_failed": getty_dependency_failed,
        "terminal_dsr_replies": cursor.replies,
    }
    result.write_text("\n".join(f"{k}={v}" for k, v in evidence.items()) + "\n")
    return evidence


def run(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sd", type=Path, default=SOURCE_DIR / "sd-rootfs.img")
    parser.add_argument("--partition", type=Path, default=SOURCE_DIR / "partition-rootfs.ext4")
    parser.add_argument("--sd-manifest", type=Path, default=SOURCE_DIR / "result.txt")
    parser.add_argument("--kernel", type=Path, default=ORACLE_DIR / "obj/arch/riscv/boot/Image")
    parser.add_argument("--qemu-manifest", type=Path, default=ORACLE_DIR / "result.txt")
    parser.add_argument("--output", type=Path, default=OUTPUT_DIR)
    parser.add_argument("--delay-seconds", type=int, default=110)
    parser.add_argument("--timeout-seconds", type=int, default=480)
    parser.add_argument("--run", action="store_true", help="actually boot both QEMU variants")
    args = parser.parse_args(argv)
    if not (100 <= args.delay_seconds <= 180 and
            args.delay_seconds + 180 <= args.timeout_seconds <= 900):
        raise QualificationError("require delay 100..180s and bounded QEMU timeout with margin")
    if shutil.which("debugfs") is None:
        raise QualificationError("e2fsprogs debugfs is required; no loop mounts are used")
    if args.run and shutil.which("qemu-system-riscv64") is None:
        raise QualificationError("QEMU RISC-V executable missing")
    for candidate in (args.sd, args.partition, args.sd_manifest, args.kernel,
                      args.qemu_manifest, args.output):
        if candidate.is_symlink():
            raise QualificationError(f"input/output symlinks are forbidden: {candidate}")
    source = args.sd.resolve(strict=True)
    partition = args.partition.resolve(strict=True)
    kernel = args.kernel.resolve(strict=True)
    output = args.output.resolve(strict=False)
    for forbidden in (source, partition, kernel, args.sd_manifest.resolve(strict=True),
                      args.qemu_manifest.resolve(strict=True)):
        if output == forbidden or output in forbidden.parents or forbidden in output.parents:
            raise QualificationError("output directory overlaps trusted source media")
    if re.search(r"[^A-Za-z0-9_./-]", str(output)):
        raise QualificationError("debugfs diagnostic output path contains unsafe characters")
    identity = validate_media(source, partition, args.sd_manifest, kernel, args.qemu_manifest)
    if output.exists() or output.is_symlink():
        raise QualificationError("output already exists; never overwrite diagnostic images")
    output.mkdir(parents=True)
    report: dict[str, object] = {
        "qualification": "QEMU_DELAYED_UDEV_AB_ONLY_NOT_PRODUCT_RTL",
        "delay_seconds": args.delay_seconds,
        "timeout_seconds": args.timeout_seconds,
        **identity,
    }
    try:
        for baseline in (True, False):
            report.update(prepare_variant(source, partition, output,
                                          delay_seconds=args.delay_seconds,
                                          baseline=baseline))
            if digest(source) != identity["source_sd_sha256"] or digest(partition) != identity["source_partition_sha256"]:
                raise QualificationError("original candidate media changed during offline A/B preparation")
        report["prepared"] = True
        if args.run:
            baseline = qemu_probe(kernel, output / "baseline.img", output,
                                  mode="baseline", timeout_seconds=args.timeout_seconds)
            extended = qemu_probe(kernel, output / "extended.img", output,
                                  mode="extended", timeout_seconds=args.timeout_seconds)
            report.update({"baseline": baseline, "extended": extended})
            for name in ("baseline", "extended"):
                if (digest(output / f"{name}.img") != report[f"{name}_sd_sha256"] or
                        digest(output / f"{name}.ext4") != report[f"{name}_partition_sha256"]):
                    raise QualificationError(f"QEMU or another process modified {name} source image")
            # This is an actual A/B experiment only if the default control
            # demonstrates the same device/getty timeout seen on real RTL.
            if not (baseline["dev_ttyS0_device_timeout"] and
                    baseline["serial_getty_dependency_failed"] and
                    baseline["native_pid1"] and
                    not baseline["console_getty_started"] and
                    not baseline["native_login_prompt"] and
                    not baseline["ordered_serial_getty_login"]):
                raise QualificationError("INCONCLUSIVE: baseline did not reproduce ttyS0 getty timeout")
            if not (extended["native_ext4_root"] and extended["native_pid1"] and
                    extended["ordered_serial_getty_login"] and
                    extended["elapsed_seconds"] >= args.delay_seconds and
                    not extended["console_getty_started"] and
                    not extended["serial_getty_dependency_failed"] and
                    not extended["dev_ttyS0_device_timeout"]):
                raise QualificationError("FAIL: candidate did not reach native serial getty/login after delay")
            if (digest(source) != identity["source_sd_sha256"] or
                    digest(partition) != identity["source_partition_sha256"] or
                    digest(kernel) != identity["oracle_image_sha256"]):
                raise QualificationError("immutable candidate source media changed during QEMU A/B")
            report["verdict"] = "QEMU_DELAYED_UDEV_AB_PASS_NOT_PRODUCT_RTL"
        else:
            report["verdict"] = "PREPARED_NOT_RUN"
    except Exception as exc:
        report["verdict"] = "INCONCLUSIVE" if str(exc).startswith("INCONCLUSIVE:") else "UNQUALIFIED"
        report["error"] = str(exc)
        raise
    finally:
        report["source_unchanged"] = (digest(source) == identity["source_sd_sha256"] and
                                       digest(partition) == identity["source_partition_sha256"] and
                                       digest(kernel) == identity["oracle_image_sha256"])
        (output / "result.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(report["verdict"])
    return 0


if __name__ == "__main__":
    try:
        sys.exit(run())
    except (OSError, QualificationError, ValueError, subprocess.SubprocessError) as exc:
        sys.exit(f"SD1 delayed-udev QEMU A/B UNQUALIFIED: {exc}")
