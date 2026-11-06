#!/usr/bin/env python3
"""Read G5 checkpoint Observer PC samples without loading its 256 MiB RAM."""

import argparse
import bisect
import collections
import pathlib
import struct


def read_map(path):
    symbols = []
    with open(path, encoding="utf-8", errors="replace") as source:
        for line in source:
            words = line.split()
            if len(words) >= 3:
                try:
                    addr = int(words[0], 16)
                except ValueError:
                    continue
                symbols.append((addr, words[2]))
    symbols.sort()
    return [entry[0] for entry in symbols], [entry[1] for entry in symbols]


def parse_observer(path):
    with open(path, "rb") as source:
        def word():
            value = source.read(8)
            if len(value) != 8:
                raise ValueError("truncated Observer word")
            return struct.unpack("<Q", value)[0]

        def sized(maximum):
            length = word()
            if length > maximum:
                raise ValueError("invalid Observer string length")
            value = source.read(length)
            if len(value) != length:
                raise ValueError("truncated Observer string")
            return value

        if source.read(8) != b"AETG5CP1" or word() != 1:
            raise ValueError("wrong G5 checkpoint format")
        for max_size in (64, 64, 128, 128, 4096):
            sized(max_size)
        for _ in range(3):
            word()
        counters = [word() for _ in range(15)]
        uart = sized(32 * 1024 * 1024)
        for _ in range(14):
            word()
        pcs = [word() for _ in range(16)]
        count, index = word(), word()
        if count > 16 or index >= 16:
            raise ValueError("invalid PC ring")
        recent = [pcs[(index - count + i) % 16] for i in range(count)]
        return counters, uart, recent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--system-map", type=pathlib.Path, required=True)
    parser.add_argument("snapshots", nargs="+", type=pathlib.Path)
    args = parser.parse_args()
    addresses, names = read_map(args.system_map)
    overall = collections.Counter()
    for path in args.snapshots:
        counters, uart, pcs = parse_observer(path)
        counts = collections.Counter()
        for pc in pcs:
            idx = bisect.bisect_right(addresses, pc) - 1
            sym = names[idx] if idx >= 0 else "[unmapped]"
            counts[sym] += 1
            overall[sym] += 1
        d2_markers = (b"AETHERCORE_DEBIAN_D2_START",
                      b"AETHERCORE_D2_NATIVE_FP_THREADS_FUTEX_VM_OK",
                      b"DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK")
        print(f"{path.name}: cycles={counters[0]} commits={counters[1]} "
              f"exceptions={counters[5]} interrupts={counters[6]} "
              f"stip={counters[7]} uart_bytes={len(uart)} "
              f"d2_start={int(d2_markers[0] in uart)} "
              f"d2_all_markers={int(all(marker in uart for marker in d2_markers))} "
              f"recent16={dict(counts.most_common())}")
    print(f"ALL_SAMPLES top_functions={overall.most_common(20)}")


if __name__ == "__main__":
    main()
