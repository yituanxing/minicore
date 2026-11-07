#!/usr/bin/env python3
"""Read Debian D2 initramfs cursor from *existing* G5 host checkpoints.

No RTL simulation, Linux boot, firmware/toolchain rebuild, or 256 MiB RAM copy.
The archive percentage is a CPIO *input cursor*, not proof that PID1 has
started. During CopyFile, the additional per-file percentage is the kernel's
acknowledged write position; it may lag bytes in an in-flight kernel_write().
The estimate is an observed-rate extrapolation, not a cycle deadline.

Example:
    python3 tools/ci/product_g5_initramfs_progress.py \
      --vmlinux build/vmlinux --system-map build/System.map \
      build/persistent.pre.host build/persistent.bridge-950m.host \
      build/probe-10.restored.host build/p960.restored.host
"""

import argparse
import bisect
import dataclasses
import mmap
import pathlib
import struct


KERNEL_VA = 0xFFFFFFFF80000000
KERNEL_IMAGE_PHYS = 0x80200000
RAM_PHYS = 0x80000000
RAM_TRAILER_BYTES = 24  # G5 host checkpoint footer, after the DDR dump.
BOOT_IMAGE_COMPARE = 96
PID1_MARKER = b"AETHERCORE_DEBIAN_D2_START"


@dataclasses.dataclass
class Entry:
    payload: int
    end: int
    name: str
    size: int


@dataclasses.dataclass
class Sample:
    name: str
    cycles: int
    commits: int
    cursor: int
    remaining: int
    next_header: int
    state: int
    body_len: int
    remains: int
    inptr: int
    wfile_pos: int
    pid1: bool


def symbols(path):
    wanted = {"__initramfs_start", "__initramfs_size", "this_header",
              "byte_count", "next_header", "state", "body_len",
              "remains", "my_inptr", "wfile_pos"}
    found = {}
    with path.open(encoding="utf-8", errors="replace") as source:
        for line in source:
            fields = line.split()
            if len(fields) >= 3 and fields[2] in wanted:
                found[fields[2]] = int(fields[0], 16)
    if found.keys() != wanted:
        raise ValueError(f"System.map missing symbols: {sorted(wanted - found.keys())}")
    return found


def load_segment_prefix(elf):
    """ELF64 RISC-V PT_LOAD containing the kernel entry, not a guessed offset."""
    if elf[:4] != b"\x7fELF" or elf[4:6] != b"\x02\x01":
        raise ValueError("vmlinux must be ELF64 little-endian")
    if struct.unpack_from("<H", elf, 18)[0] != 243:
        raise ValueError("vmlinux is not RISC-V")
    phoff = struct.unpack_from("<Q", elf, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", elf, 54)
    if phentsize < 56 or phnum > 256:
        raise ValueError("invalid vmlinux program headers")
    segments = []
    for i in range(phnum):
        typ, _, offset, va, _, filesz, _, _ = struct.unpack_from(
            "<IIQQQQQQ", elf, phoff + i * phentsize)
        if typ == 1:
            segments.append((va, va + filesz, offset))
    return segments


def elf_bytes(elf, segments, va, size):
    for begin, end, offset in segments:
        if begin <= va and va + size <= end:
            pos = offset + va - begin
            return elf[pos:pos + size]
    raise ValueError(f"vmlinux PT_LOAD cannot map VA {va:#x}, size={size}")


def parse_cpio(blob):
    """Walk exact newc headers; the archive can contain zero alignment padding."""
    result = []
    pos = 0
    trailers = 0
    while pos < len(blob):
        hdr = blob[pos:pos + 110]
        if len(hdr) < 110:
            if any(hdr):
                raise ValueError("non-zero truncated CPIO header")
            break
        if hdr[:6] not in (b"070701", b"070702"):
            if blob[pos:pos + 4] == b"\0\0\0\0":
                pos += 4
                continue
            raise ValueError(f"bad CPIO header at byte {pos}")
        try:
            filesize = int(hdr[54:62], 16)
            namesize = int(hdr[94:102], 16)
        except ValueError as e:
            raise ValueError(f"invalid CPIO numeric field at byte {pos}") from e
        if not 1 <= namesize < 4096:
            raise ValueError(f"invalid CPIO name length {namesize}")
        name_bytes = blob[pos + 110:pos + 110 + namesize]
        if len(name_bytes) != namesize or name_bytes[-1] != 0:
            raise ValueError("invalid CPIO name")
        data_at = (pos + 110 + namesize + 3) & ~3
        data_end = data_at + filesize
        if data_end > len(blob):
            raise ValueError("CPIO file extends past archive end")
        name = name_bytes[:-1].decode("utf-8", errors="replace")
        if name == "TRAILER!!!":
            trailers += 1
        else:
            result.append(Entry(data_at, data_end, name, filesize))
        pos = (data_end + 3) & ~3
    if not result or not trailers:
        raise ValueError("no valid CPIO files/trailer")
    return result, trailers


def word(f):
    raw = f.read(8)
    if len(raw) != 8:
        raise ValueError("truncated G5 host checkpoint")
    return struct.unpack("<Q", raw)[0]


def sized(f, max_bytes):
    n = word(f)
    if n > max_bytes:
        raise ValueError(f"bad Observer string length {n}")
    data = f.read(n)
    if len(data) != n:
        raise ValueError("truncated Observer string")
    return data


def snapshot(path, expected, boot_prefix, archive_prefix, total):
    with path.open("rb") as f:
        if f.read(8) != b"AETG5CP1" or word(f) != 1:
            raise ValueError(f"{path}: unsupported Observer magic/version")
        for limit in (64, 64, 128, 128, 4096):
            sized(f, limit)
        for _ in range(3):
            word(f)
        counters = [word(f) for _ in range(15)]
        uart = sized(f, 32 * 1024 * 1024)
        for _ in range(14 + 16 + 2):
            word(f)
        # Five 64-bit words encode the G5 DDR state header; last is RAM size.
        mem_header = [word(f) for _ in range(5)]
        ram_start = f.tell()
        ram_size = mem_header[4]
        if ram_size != 256 * 1024 * 1024:
            raise ValueError(f"{path}: unexpected DDR size {ram_size}")
        if path.stat().st_size != ram_start + ram_size + RAM_TRAILER_BYTES:
            raise ValueError(f"{path}: DDR dump and footer size do not match")

        def guest_read(virtual, size):
            # Linux Image KVA+0 maps to RAM base+2MiB on this RV64 system.
            ddr_offset = (KERNEL_IMAGE_PHYS - RAM_PHYS) + (virtual - KERNEL_VA)
            if ddr_offset < 0 or ddr_offset + size > ram_size:
                raise ValueError(f"{path}: KVA outside DDR: {virtual:#x}")
            f.seek(ram_start + ddr_offset)
            b = f.read(size)
            if len(b) != size:
                raise ValueError(f"{path}: guest memory read truncated")
            return b

        if guest_read(KERNEL_VA, len(boot_prefix)) != boot_prefix:
            raise ValueError(f"{path}: kernel Image head mismatches vmlinux")
        if guest_read(expected["__initramfs_start"], len(archive_prefix)) != archive_prefix:
            raise ValueError(f"{path}: embedded CPIO mismatches vmlinux")
        if PID1_MARKER in uart:
            return Sample(path.name, counters[0], counters[1], 0, 0, 0,
                          0, 0, 0, 0, 0, True)

        def u64(name):
            return struct.unpack("<Q", guest_read(expected[name], 8))[0]

        cursor = u64("this_header")
        remaining = u64("byte_count")
        if cursor > total or remaining > total or cursor + remaining != total:
            raise ValueError(f"{path}: initramfs cursor/remaining do not sum to {total}")
        return Sample(path.name, counters[0], counters[1], cursor, remaining,
                      u64("next_header"),
                      struct.unpack("<I", guest_read(expected["state"], 4))[0],
                      u64("body_len"), u64("remains"), u64("my_inptr"),
                      u64("wfile_pos"), False)


def current_file_write(sample, entry):
    """Return the kernel's accounted file offset only for active CopyFile.

    Linux init/initramfs.c keeps wfile_pos from the *previous* regular file
    while do_name() is in SkipIt, and resets it after filp_open(). Thus a
    matching pathname alone cannot justify treating the value as progress.
    During CopyFile (state 5), do_copy() writes at &wfile_pos and advances
    this_header/body_len only after each xwrite() returns. This offset can
    lag an in-progress write. Validate record identity and arithmetic
    invariants before exposing the offset as percentage progress.
    """
    COPY_FILE = 5
    if entry is None or sample.state != COPY_FILE or entry.size == 0:
        return None
    aligned_end = (entry.end + 3) & ~3
    if not (entry.payload <= sample.cursor <= entry.end
            and sample.next_header == aligned_end
            and sample.cursor + sample.body_len == entry.end
            and sample.cursor - entry.payload <= sample.wfile_pos <= entry.size):
        return None
    return sample.wfile_pos


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--vmlinux", type=pathlib.Path, required=True)
    ap.add_argument("--system-map", type=pathlib.Path, required=True)
    ap.add_argument("snapshots", nargs="+", type=pathlib.Path)
    args = ap.parse_args()
    syms = symbols(args.system_map)
    archive_start = syms["__initramfs_start"]
    archive_end = syms["__initramfs_size"]
    total = archive_end - archive_start
    if not 0 < total < 256 * 1024 * 1024:
        raise ValueError(f"bad __initramfs_start..__initramfs_size range: {total}")

    with args.vmlinux.open("rb") as stream:
        with mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as elf:
            segments = load_segment_prefix(elf)
            head = elf_bytes(elf, segments, KERNEL_VA, BOOT_IMAGE_COMPARE)
            raw_cpio = elf_bytes(elf, segments, archive_start, total)
            if raw_cpio[:6] not in (b"070701", b"070702"):
                raise ValueError("embedded initramfs does not begin with newc magic")
            entries, trailers = parse_cpio(raw_cpio)
            archive_prefix = raw_cpio[:128]
    payload_starts = [entry.payload for entry in entries]
    print(f"G5_INITRAMFS_IDENTITY total_bytes={total} records={len(entries)} "
          f"trailers={trailers} magic=newc kernel_va={KERNEL_VA:#x} "
          f"kernel_phys={KERNEL_IMAGE_PHYS:#x}")

    samples = [snapshot(p, syms, head, archive_prefix, total)
               for p in args.snapshots]
    if any(a.cycles >= b.cycles for a, b in zip(samples, samples[1:])):
        raise ValueError("checkpoints must be in strictly increasing guest cycle order")
    rates = []
    for i, s in enumerate(samples):
        if s.pid1:
            print(f"G5_INITRAMFS_PROGRESS file={s.name} cycles={s.cycles} "
                  f"commits={s.commits} phase=PID1_MARKER_PRESENT")
            continue
        idx = bisect.bisect_right(payload_starts, s.cursor) - 1
        current = entries[idx] if idx >= 0 else None
        completed = max(0, idx)
        current_file = current.name if current else "[before-first-file]"
        boundary = "payload_start" if current and s.cursor == current.payload else "between_boundaries"
        write_bytes = current_file_write(s, current)
        file_write_progress = (
            f"wfile_pos={write_bytes} in_file_percent={100*write_bytes/current.size:.2f}"
            if write_bytes is not None else
            "wfile_pos=NA in_file_percent=NA"
        )
        print(f"G5_INITRAMFS_PROGRESS file={s.name} cycles={s.cycles} "
              f"commits={s.commits} cursor_bytes={s.cursor} total_bytes={total} "
              f"cursor_percent={100*s.cursor/total:.2f} "
              f"remaining_bytes={s.remaining} state={s.state} "
              f"kernel_remains={s.remains} kernel_inptr={s.inptr} "
              f"next_header={s.next_header} body_len={s.body_len} "
              f"{file_write_progress} "
              f"completed_records_approx={completed}/{len(entries)} "
              f"current_record={current_file!r} boundary={boundary}")
        if i and not samples[i - 1].pid1:
            prior = samples[i - 1]
            dc = s.cycles - prior.cycles
            db = s.cursor - prior.cursor
            if db > 0:
                rates.append(db / dc)
                print(f"G5_INITRAMFS_RATE from={prior.cycles} to={s.cycles} "
                      f"delta_bytes={db} delta_cycles={dc} "
                      f"cycles_per_byte={dc/db:.3f}")
            else:
                print(f"G5_INITRAMFS_RATE from={prior.cycles} to={s.cycles} "
                      f"delta_bytes={db} delta_cycles={dc} NOT_EXTRAPOLATED")
    if rates and not samples[-1].pid1:
        current = samples[-1]
        # These are *byte-throughput-only scenarios*. The already observed
        # 820/3264 record skew shows that per-inode work matters; neither
        # value bounds actual unpack completion. Do not present a misleading
        # ``max_finish_cycles`` deadline to CI or human readers.
        fast_bytes_only = current.cycles + int(current.remaining / max(rates))
        slow_bytes_only = current.cycles + int(current.remaining / min(rates))
        print(f"G5_INITRAMFS_ESTIMATE current_cycles={current.cycles} "
              f"fast_bytes_only_scenario={fast_bytes_only} "
              f"slow_bytes_only_scenario={slow_bytes_only} "
              f"observed_windows={len(rates)} confidence=LOW "
              f"ignores_per_record_overhead=1 not_a_finish_bound=1 "
              f"meaning=CPIO_CURSOR_ONLY_NOT_PID1_NOT_FILE_WRITE")
    else:
        print("G5_INITRAMFS_ESTIMATE no_rate_available=1")


if __name__ == "__main__":
    main()
