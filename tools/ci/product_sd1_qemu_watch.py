#!/usr/bin/env python3
"""Bounded QEMU-virt Debian init/login observer, never Product RTL evidence.

The Product SD1 image is used via QEMU's ephemeral -snapshot copy-on-write
drive. QEMU provides its own OpenSBI, virt machine, UART and virtio-mmio disk;
this oracle says nothing about the Product SPI-SD/PLIC/RTL path.
"""

import argparse
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time


FAILURE_PATTERNS = (
    b"Kernel panic - not syncing",
    b"VFS: Unable to mount root fs",
    b"VFS: Cannot open root device",
    b"No working init found",
    b"Failed to execute /sbin/init",
)
ROOT_MOUNT = b"VFS: Mounted root (ext4 filesystem)"
PID1_EXEC = b"Run /sbin/init as init process"
LOGIN_PROMPT = re.compile(rb"(?:^|[\r\n\s])(?:[^\r\n\s]+\s+)?login:\s*$")


class BootProof:
    def __init__(self) -> None:
        self.root_mounted = False
        self.pid1_executed = False
        self.login_prompt = False
        self.failure: str | None = None
        self.tail = b""
        self.pending = b""

    def observe(self, chunk: bytes) -> None:
        self.tail = (self.tail + chunk)[-16384:]
        self.pending = (self.pending + chunk)[-16384:]
        for bad in FAILURE_PATTERNS:
            if bad in self.tail:
                self.failure = bad.decode("ascii")
                return
        if not self.root_mounted:
            position = self.pending.find(ROOT_MOUNT)
            if position >= 0:
                self.root_mounted = True
                self.pending = self.pending[position + len(ROOT_MOUNT):]
        if self.root_mounted and not self.pid1_executed:
            position = self.pending.find(PID1_EXEC)
            if position >= 0:
                self.pid1_executed = True
                self.pending = self.pending[position + len(PID1_EXEC):]
        # Getty often emits a prompt without newline. It must occur after
        # both the ext4 root mount and the native /sbin/init exec marker.
        if self.root_mounted and self.pid1_executed and LOGIN_PROMPT.search(self.pending):
            self.login_prompt = True


def run(args: argparse.Namespace) -> None:
    for path in (args.kernel, args.sd):
        if not path.is_file() or not path.stat().st_size:
            raise ValueError(f"missing nonempty exact input: {path}")
        if "," in str(path):
            raise ValueError(f"unsupported comma in QEMU drive path: {path}")
    for path in (args.log, args.result):
        if path.exists():
            raise ValueError(f"refusing to overwrite existing QEMU evidence: {path}")
    if not (10 <= args.timeout <= 900):
        raise ValueError("QEMU wall timeout must be 10..900 seconds")

    command = [
        "qemu-system-riscv64", "-machine", "virt", "-m", "256M", "-smp", "1",
        "-nographic", "-monitor", "none", "-serial", "stdio",
        "-bios", "default", "-kernel", str(args.kernel),
        "-append", (
            "earlycon=sbi console=ttyS0,115200 "
            "root=/dev/vda1 rootfstype=ext4 rootwait rw init=/sbin/init "
            "systemd.unit=multi-user.target"
        ),
        "-drive", f"file={args.sd},format=raw,if=none,id=sd1,snapshot=on",
        "-device", "virtio-blk-device,drive=sd1",
        "-no-reboot",
    ]
    proof = BootProof()
    started = time.monotonic()
    last_uart = started
    byte_count = 0
    max_log_bytes = 64 * 1024 * 1024
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with args.log.open("xb") as evidence, subprocess.Popen(
        command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, bufsize=0,
    ) as process:
        assert process.stdout is not None
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        try:
            while True:
                now = time.monotonic()
                if now - started >= args.timeout:
                    raise TimeoutError(f"QEMU root/init/login did not complete in {args.timeout}s")
                if now - last_uart >= min(90, args.timeout):
                    raise TimeoutError("QEMU UART has not advanced for 90s")
                ready = selector.select(timeout=0.5)
                if ready:
                    chunk = os.read(process.stdout.fileno(), 65536)
                    if chunk:
                        byte_count += len(chunk)
                        if byte_count > max_log_bytes:
                            raise ValueError("QEMU UART exceeded 64MiB diagnostic budget")
                        evidence.write(chunk)
                        evidence.flush()
                        last_uart = time.monotonic()
                        proof.observe(chunk)
                        if proof.failure:
                            raise ValueError(f"QEMU Linux failed: {proof.failure}")
                        if proof.login_prompt:
                            break
                    elif process.poll() is not None:
                        raise ValueError(f"QEMU exited before native login: {process.returncode}")
                elif process.poll() is not None:
                    raise ValueError(f"QEMU exited before native login: {process.returncode}")
        finally:
            selector.close()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)

    if not (proof.root_mounted and proof.pid1_executed and proof.login_prompt):
        raise ValueError("QEMU emitted no qualified root/init/login sequence")
    elapsed = time.monotonic() - started
    with args.result.open("x") as result:
        result.write(
            "PRODUCT_SD1_QEMU_NATIVE_DEBIAN_INIT_LOGIN_PROMPT_PASS_NOT_RTL\n"
            "qualification=QEMU_VIRTIO_BLOCK_ONLY_NOT_PRODUCT_SPI_RTL\n"
            "bootargs_root=/dev/vda1\n"
            "root_mounted=ext4\n"
            "native_pid1=/sbin/init\n"
            "login_prompt=OBSERVED_NOT_AUTHENTICATED\n"
            f"elapsed_seconds={elapsed:.3f}\n"
            f"uart_bytes={byte_count}\n"
        )
    print(f"PRODUCT_SD1_QEMU_NATIVE_DEBIAN_INIT_LOGIN_PROMPT_PASS_NOT_RTL elapsed_seconds={elapsed:.3f}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kernel", type=Path, required=True)
    parser.add_argument("--sd", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--result", type=Path, required=True)
    args = parser.parse_args()
    try:
        run(args)
    except (OSError, ValueError, TimeoutError, subprocess.SubprocessError) as error:
        sys.exit(f"ERROR: Product SD1 QEMU native-init oracle: {error}")


if __name__ == "__main__":
    main()
