#!/usr/bin/env python3
"""Read the G5 checkpoint's *serialized Observer.uart*, not strings in DDR.

Usage: python3 tools/ci/product_g5_checkpoint_uart_extract.py STAGE_ZIP \
         --cycle 1895000000 --cycle 1900000000 --tail 500

The layout is pinned to sim/product_rv64_checkpoint_g4_main.cpp at commit
99a5c86e99f52ed12f95b304635d77613c9505c4, format AETG5CP1 v1.
No guest execution or checkpoint mutation is performed.
"""

import argparse
import hashlib
import io
import json
import struct
import zipfile

MAGIC = b"AETG5CP1"
FNVOFFSET = 14695981039346656037
FNVPRIME = 1099511628211
MASK64 = (1 << 64) - 1
SOURCE = "99a5c86e99f52ed12f95b304635d77613c9505c4"
MODEL = "aa35c80024238420bea3976db677db2e0ef028aad9800482926b926e50155b71"
FIRMWARE = "d525de6a58422de93da6de5312d38867611153da94c580bca31f90ebf13eff2c"
OBSERVER_FIELDS = (
    "cycles", "commits", "compressed", "opensbi_compressed", "linux_compressed",
    "exceptions", "interrupts", "stip", "seip", "last_exception_cause",
    "last_exception_value", "last_exception_pc", "milestone_commit_count",
    "uart_hash", "trace_hash",
)
MARKERS = {
    "linux_banner": b"Linux version 6.6.143",
    "kernel_pid1_handoff": b"Run /init as init process",
    "d2_start": b"AETHERCORE_DEBIAN_D2_START",
    "d2_process_fs_signals_pass": b"DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK",
    "d2_fp_threads_futex_vm_pass": b"AETHERCORE_D2_NATIVE_FP_THREADS_FUTEX_VM_OK",
    "d2_fail": b"AETHERCORE_DEBIAN_D2_FAIL",
    "kernel_panic": b"Kernel panic",
}


def read_u64(stream: io.BufferedIOBase) -> int:
    data = stream.read(8)
    if len(data) != 8:
        raise ValueError("truncated u64 in checkpoint header")
    return struct.unpack("<Q", data)[0]


def read_string(stream: io.BufferedIOBase, maximum: int) -> bytes:
    length = read_u64(stream)
    if length > maximum:
        raise ValueError(f"checkpoint string length {length} exceeds limit {maximum}")
    data = stream.read(length)
    if len(data) != length:
        raise ValueError("truncated checkpoint string")
    return data


def fnv64(data: bytes) -> int:
    value = FNVOFFSET
    for byte in data:
        value = ((value ^ byte) * FNVPRIME) & MASK64
    return value


def fields_of(manifest: bytes) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in manifest.decode("ascii").splitlines():
        key, sep, value = line.partition("=")
        if not sep or key in result:
            raise ValueError("noncanonical or duplicate manifest field")
        result[key] = value
    return result


def checkpoint_from_zip(archive: zipfile.ZipFile, cycle: int, tail: int) -> dict:
    directory = f"checkpoints/c{cycle}/"
    manifest = fields_of(archive.read(directory + "manifest.txt"))
    if (manifest.get("format") != "g5-d2-portable-v1"
            or manifest.get("source_commit") != SOURCE
            or manifest.get("model_sha256") != MODEL
            or manifest.get("firmware_sha256") != FIRMWARE
            or manifest.get("cycles") != str(cycle)):
        raise ValueError(f"checkpoint identity differs at cycle {cycle}")

    # Verify the exact host bytes against the artifact's COMPLETE record.
    checksums = {}
    for line in archive.read(directory + "COMPLETE").decode("ascii").splitlines():
        value, sep, name = line.partition("  ")
        if not sep or name in checksums or len(value) != 64:
            raise ValueError("invalid COMPLETE record")
        checksums[name] = value
    if set(checksums) != {"checkpoint.host", "checkpoint.rtl", "manifest.txt"}:
        raise ValueError("incomplete COMPLETE record")
    if hashlib.sha256(archive.read(directory + "manifest.txt")).hexdigest() != checksums["manifest.txt"]:
        raise ValueError("manifest bytes fail COMPLETE")
    with archive.open(directory + "checkpoint.host") as stream:
        digest = hashlib.sha256()
        while chunk := stream.read(1 << 20):
            digest.update(chunk)
        if digest.hexdigest() != checksums["checkpoint.host"]:
            raise ValueError("host snapshot bytes fail COMPLETE")

    with archive.open(directory + "checkpoint.host") as stream:
        if stream.read(8) != MAGIC or read_u64(stream) != 1:
            raise ValueError("invalid G5 host checkpoint magic/version")
        if read_string(stream, 64).decode("ascii") != MODEL:
            raise ValueError("checkpoint host model identity differs")
        if read_string(stream, 64).decode("ascii") != FIRMWARE:
            raise ValueError("checkpoint host firmware identity differs")
        policy_marker = read_string(stream, 128)
        read_string(stream, 128)  # UART trigger
        read_string(stream, 4096)  # UART input
        read_u64(stream)  # post-input budget
        saved_time, memory_wait = read_u64(stream), read_u64(stream)
        observer = dict(zip(OBSERVER_FIELDS, (read_u64(stream) for _ in OBSERVER_FIELDS)))
        uart = read_string(stream, 32 << 20)
        flags = [read_u64(stream) for _ in range(3)]
        read_u64(stream)  # input index
        read_u64(stream)  # SEIP at input start
        additional_flags = [read_u64(stream) for _ in range(3)]
        counters = [read_u64(stream) for _ in range(6)]
        ring = [read_u64(stream) for _ in range(16)]
        ring_count, ring_index = read_u64(stream), read_u64(stream)

    if (observer["cycles"] != cycle or saved_time != cycle or memory_wait != 0
            or policy_marker != b"Linux version 6.6.143"
            or observer["uart_hash"] != fnv64(uart)
            or any(flag > 1 for flag in flags + additional_flags)
            or ring_count > 16 or ring_index >= 16
            or counters[-1] > cycle):
        raise ValueError("host UART/observer header fails canonical validation")
    last_pcs = ring[:ring_count] if ring_count < 16 else ring[ring_index:] + ring[:ring_index]
    return {
        "cycle": cycle,
        "commits": observer["commits"],
        "uart_bytes": len(uart),
        "uart_sha256": hashlib.sha256(uart).hexdigest(),
        "last_uart_cycle": counters[-1],
        "last_exception_pc": f"0x{observer['last_exception_pc']:016x}",
        "recent_commit_pcs": [f"0x{pc:016x}" for pc in last_pcs],
        "marker_present": {key: token in uart for key, token in MARKERS.items()},
        "uart_tail": uart[-tail:].decode("utf-8", "replace") if tail else "",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact_zip", help="Downloaded, original stage artifact ZIP")
    parser.add_argument("--cycle", type=int, action="append", required=True)
    parser.add_argument("--tail", type=int, default=0, help="Tail bytes of serialized UART (0-8192)")
    args = parser.parse_args()
    if not 0 <= args.tail <= 8192:
        parser.error("--tail must be 0..8192")
    with zipfile.ZipFile(args.artifact_zip) as archive:
        for cycle in args.cycle:
            if cycle < 0 or cycle % 5_000_000:
                parser.error("checkpoint cycle must be nonnegative multiple of 5M")
            print(json.dumps(checkpoint_from_zip(archive, cycle, args.tail), ensure_ascii=False))


if __name__ == "__main__":
    main()
