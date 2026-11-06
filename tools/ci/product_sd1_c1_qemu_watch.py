#!/usr/bin/env python3
"""Bounded C1 Linux initramfs handoff oracle on QEMU virt, not Product RTL.

The exact pinned SD1 ext4 media is provided as an ephemeral virtio block disk.
This checks a rootfs-agnostic /init PAUSES before mounting the real root,
accepts an explicit serial GO, then mounts, switches root and reaches Debian's
unaltered native /sbin/init/login. A Product SPI C1 proof is still required.
"""

import argparse
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time


READY = b"AETHERCORE_C1_READY rootfs_unmounted=1\n"
MOUNT_BEGIN = b"AETHERCORE_C1_ROOTFS_MOUNT_BEGIN\n"
MOUNT_OK = b"AETHERCORE_C1_ROOTFS_MOUNT_OK\n"
NATIVE_EXEC = b"AETHERCORE_C1_NATIVE_INIT_EXEC\n"
RELEASE = b"AETHERCORE_C1_GO\n"
LOGIN = re.compile(rb"(?:^|[\r\n\s])(?:[^\r\n\s]+\s+)?login:\s*$")
FAILURE = (
    b"AETHERCORE_C1_FAIL stage=", b"Kernel panic - not syncing",
    b"VFS: Cannot open root device", b"VFS: Unable to mount root fs",
    b"No working init found",
)


class C1Proof:
    def __init__(self) -> None:
        self.trace = b""
        self.ready = False
        self.go_sent = False
        self.mount_started = False
        self.mount_done = False
        self.native_exec = False
        self.login = False
        self.failure = None

    def observe(self, data: bytes) -> None:
        self.trace = (self.trace + data)[-65536:]
        for marker in FAILURE:
            if marker in self.trace:
                self.failure = marker.decode("ascii")
                return
        if not self.ready:
            if MOUNT_BEGIN in self.trace or MOUNT_OK in self.trace:
                self.failure = "rootfs mounted before C1 gate"
                return
            self.ready = READY in self.trace
        if not self.go_sent:
            if MOUNT_BEGIN in self.trace or MOUNT_OK in self.trace:
                self.failure = "rootfs consumed before host C1 GO"
            return
        begin_at = self.trace.find(MOUNT_BEGIN)
        mounted_at = self.trace.find(MOUNT_OK)
        exec_at = self.trace.find(NATIVE_EXEC)
        if begin_at >= 0:
            if not self.ready:
                self.failure = "C1 rootfs mount without READY"
                return
            self.mount_started = True
        if mounted_at >= 0:
            if not self.mount_started or (begin_at >= 0 and mounted_at < begin_at):
                self.failure = "C1 rootfs mount lacked BEGIN"
                return
            self.mount_done = True
        if exec_at >= 0:
            if not self.mount_done or (mounted_at >= 0 and exec_at < mounted_at):
                self.failure = "native init executed before ext4 mount"
                return
            self.native_exec = True
        if self.native_exec and LOGIN.search(self.trace):
            self.login = True


def run(args: argparse.Namespace) -> None:
    for path in (args.kernel, args.media):
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"missing nonempty pinned C1 input: {path}")
        if "," in str(path):
            raise ValueError(f"QEMU drive path contains comma: {path}")
    if args.log.exists() or args.result.exists():
        raise ValueError("refusing to overwrite old C1 UART/result evidence")
    if not (10 <= args.timeout <= 900):
        raise ValueError("C1 QEMU guest timeout must be 10..900s")

    command = [
        "qemu-system-riscv64", "-machine", "virt", "-m", "256M", "-smp", "1",
        "-nographic", "-monitor", "none", "-serial", "stdio",
        "-bios", "default", "-kernel", str(args.kernel),
        "-append", (
            "earlycon=sbi console=ttyS0,115200 root=/dev/vda1 rootfstype=ext4 "
            "rootwait rw rdinit=/init systemd.unit=multi-user.target"
        ),
        "-drive", f"file={args.media},format=raw,if=none,id=c1sd,snapshot=on",
        "-device", "virtio-blk-device,drive=c1sd", "-no-reboot",
    ]
    proof = C1Proof()
    started = last_uart = time.monotonic()
    total = 0
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with args.log.open("xb") as evidence, subprocess.Popen(
        command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, bufsize=0,
    ) as process:
        assert process.stdin is not None and process.stdout is not None
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        try:
            while not proof.login:
                now = time.monotonic()
                if now - started >= args.timeout:
                    raise TimeoutError("C1 QEMU READY/GO/ext4/native login timed out")
                if now - last_uart >= min(90, args.timeout):
                    raise TimeoutError("C1 QEMU UART produced no bytes for 90s")
                events = selector.select(timeout=0.5)
                if events:
                    data = os.read(process.stdout.fileno(), 65536)
                    if not data:
                        if process.poll() is not None:
                            raise ValueError(f"C1 QEMU exited early: {process.returncode}")
                        continue
                    total += len(data)
                    if total > 64 * 1024 * 1024:
                        raise ValueError("C1 QEMU UART exceeded bounded 64MiB budget")
                    evidence.write(data)
                    evidence.flush()
                    last_uart = time.monotonic()
                    proof.observe(data)
                    if proof.failure:
                        raise ValueError(proof.failure)
                    if proof.ready and not proof.go_sent:
                        # No distribution root is mounted at this point.
                        # QEMU's serial input resumes exactly the gated /init.
                        process.stdin.write(RELEASE)
                        process.stdin.flush()
                        proof.go_sent = True
                elif process.poll() is not None:
                    raise ValueError(f"C1 QEMU exited early: {process.returncode}")
        finally:
            selector.close()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

    if not all((proof.ready, proof.go_sent, proof.mount_started,
                proof.mount_done, proof.native_exec, proof.login)):
        raise ValueError("incomplete C1 rootfs handoff evidence")
    elapsed = time.monotonic() - started
    with args.result.open("x") as out:
        out.write(
            "PRODUCT_SD1_C1_QEMU_ROOTFS_HANDOFF_PASS_NOT_RTL\n"
            "qualification=QEMU_VIRTIO_ONLY_NOT_PRODUCT_SPI_C1\n"
            "before_release=rootfs_unmounted\n"
            "root_device=/dev/vda1\n"
            "release=AETHERCORE_C1_GO\n"
            "native_pid1=/sbin/init\n"
            "login_prompt=OBSERVED_NOT_AUTHENTICATED\n"
            f"elapsed_seconds={elapsed:.3f}\n"
            f"uart_bytes={total}\n"
        )
    print(f"PRODUCT_SD1_C1_QEMU_ROOTFS_HANDOFF_PASS_NOT_RTL seconds={elapsed:.3f}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kernel", type=Path, required=True)
    parser.add_argument("--media", type=Path, required=True)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--result", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=240)
    arguments = parser.parse_args()
    try:
        run(arguments)
    except (OSError, ValueError, TimeoutError, subprocess.SubprocessError) as error:
        sys.exit(f"PRODUCT_SD1_C1_QEMU_REJECTED: {error}")
