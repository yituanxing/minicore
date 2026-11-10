#!/usr/bin/env python3
"""Read-only, kernel-pinned SD1 systemd BPF verifier snapshot inspection.

This is a forensic probe for the exact frozen Linux 6.6.143 SD1 Image, not a
general-purpose kernel debugger. It never advances guest cycles or changes
checkpoint bytes. All observations are drawn from SHA-verified full Host/RTL
snapshots and the PID 1 saved kernel stack.
"""

import argparse
import hashlib
import json
from pathlib import Path
import struct


DDR_BASE = 0x80000000
DDR_SIZE = 256 * 1024 * 1024
KERNEL_VIRT = 0xFFFFFFFF80000000
KERNEL_PHYS = 0x80200000
LINEAR_VIRT = 0xFFFFFFD800000000
INIT_TASK = 0xFFFFFFFF8140BAC0
SWAPPER_PGD = 0xFFFFFFFF814FE000
TASKS_OFF = 0x368
PID_OFF = 0x430
MM_OFF = 0x3B8
TASK_STACK_OFF = 0x38
MM_PGD_OFF = 0x70
PT_REGS_OFF = 0x3EE0
ENV_ADDR = 0xFFFFFFD80256C000
IMAGE_SHA = "522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393"
QUALIFICATION = "PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS"


def u32(data, offset=0):
    return struct.unpack_from("<I", data, offset)[0]


def u64(data, offset=0):
    return struct.unpack_from("<Q", data, offset)[0]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(4 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest(path):
    result = {}
    for line in path.read_text().splitlines():
        key, sep, value = line.partition("=")
        if not sep or not key or key in result:
            raise ValueError(f"invalid or duplicate manifest key: {key}")
        result[key] = value
    if result.get("schema") != "AETSDCP1" or result.get("qualification") != QUALIFICATION:
        raise ValueError("not a migrated SD1 checkpoint of the expected format")
    if result.get("linux_image_sha256") != IMAGE_SHA:
        raise ValueError("different Linux Image; forensic offsets must not be reused")
    return result


def ddr_offset(stream):
    if stream.read(8) != b"AETSDCP1" or u64(stream.read(8)) != 1:
        raise ValueError("wrong Host snapshot format")
    for _ in range(8):
        length = u64(stream.read(8))
        if length > 40000000:
            raise ValueError("bad Host identity string")
        stream.seek(length, 1)
    for _ in range(18):
        stream.read(8)
    length = u64(stream.read(8))
    if length > 40000000:
        raise ValueError("bad UART length")
    stream.seek(length, 1)
    for _ in range(36):
        stream.read(8)
    if u64(stream.read(8)) != DDR_SIZE:
        raise ValueError("not a complete 256MiB DDR checkpoint")
    return stream.tell()


class DDR:
    def __init__(self, stream):
        self.stream = stream
        self.base = ddr_offset(stream)

    def phys(self, addr, count):
        if not DDR_BASE <= addr or addr + count > DDR_BASE + DDR_SIZE:
            raise ValueError(f"DDR physical address out of range: {addr:#x}")
        self.stream.seek(self.base + addr - DDR_BASE)
        data = self.stream.read(count)
        if len(data) != count:
            raise ValueError("truncated DDR contents")
        return data

    @staticmethod
    def direct_phys(addr):
        if LINEAR_VIRT <= addr < LINEAR_VIRT + DDR_SIZE:
            return DDR_BASE + addr - LINEAR_VIRT
        if KERNEL_VIRT <= addr < KERNEL_VIRT + DDR_SIZE:
            return KERNEL_PHYS + addr - KERNEL_VIRT
        raise ValueError(f"not a direct kernel pointer: {addr:#x}")

    def direct(self, addr, count):
        return self.phys(self.direct_phys(addr), count)

    def walk(self, root, addr):
        """Translate one Sv39 VA; root is a *kernel virtual* page-table pointer."""
        table = self.direct_phys(root)
        for level in (2, 1, 0):
            slot = (addr >> (12 + 9 * level)) & 511
            pte = u64(self.phys(table + 8 * slot, 8))
            if not (pte & 1):
                raise ValueError(f"Sv39 invalid PTE for VA {addr:#x} at level {level}")
            if pte & 0xE:
                lower = 12 + 9 * level
                return ((pte >> 10) << 12) + (addr & ((1 << lower) - 1))
            table = (pte >> 10) << 12
        raise ValueError(f"Sv39 page walk has no leaf: {addr:#x}")

    def virt(self, root, addr, count):
        result = bytearray()
        while count:
            block = min(count, 4096 - (addr & 4095))
            result.extend(self.phys(self.walk(root, addr), block))
            addr += block
            count -= block
        return bytes(result)

    def qdirect(self, addr):
        return u64(self.direct(addr, 8))

    def pid1(self):
        head = INIT_TASK + TASKS_OFF
        node = self.qdirect(head)
        seen = set()
        while node != head and len(seen) < 512:
            if node in seen:
                raise ValueError("corrupt task list")
            seen.add(node)
            task = node - TASKS_OFF
            if u32(self.direct(task + PID_OFF, 4)) == 1:
                return task
            node = self.qdirect(node)
        raise ValueError("PID1 missing or task list corrupt")


def inspect(path):
    pins = manifest(path / ".migration-ready")
    for kind in ("host", "rtl"):
        if sha256(path / ("persistent.pre." + kind)) != pins.get("snapshot_" + kind + "_sha256"):
            raise ValueError(f"snapshot {kind} SHA mismatch")
    if sha256(path / "card-progress.json") != pins.get("card_progress_sha256"):
        raise ValueError("progress SHA mismatch")
    with (path / "persistent.pre.host").open("rb") as stream:
        mem = DDR(stream)
        task = mem.pid1()
        mm = mem.qdirect(task + MM_OFF)
        pgd = mem.qdirect(mm + MM_PGD_OFF)
        stack = mem.qdirect(task + TASK_STACK_OFF)
        regs = mem.virt(SWAPPER_PGD, stack + PT_REGS_OFF, 0x120)
        sysno = u64(regs, 0x88)
        cmd = u64(regs, 0x118)
        if sysno != 280 or cmd != 5:
            raise ValueError(f"PID1 not on BPF_PROG_LOAD syscall frame: a7={sysno} cmd={cmd}")
        attr_addr = u64(regs, 0x58)
        attr_size = u64(regs, 0x60)
        if not 0x48 <= attr_size <= 4096:
            raise ValueError("invalid BPF syscall attribute length")
        attr = mem.virt(pgd, attr_addr, attr_size)
        name = attr[0x30:0x40].split(b"\0", 1)[0].decode("ascii")
        insn_count = u32(attr, 4)
        env = mem.direct(ENV_ADDR, 0x2E24)
        frames = mem.virt(SWAPPER_PGD, stack, 16384)
        if struct.pack("<Q", ENV_ADDR) not in frames:
            raise ValueError("expected verifier_env not referenced by PID1 kernel stack")
        idx = u32(env)
        if not 0 <= idx < insn_count or not 0 < insn_count <= 1000000:
            raise ValueError("BPF verifier state/program length inconsistent")
        if u64(env, 8) == 0 or u32(env, 0x2E04) > 1000000:
            raise ValueError("invalid verifier_env program pointer or processed count")
        # BPF_PROG_LOAD union bpf_attr has insns at +8: an array of exactly
        # insn_cnt * sizeof(struct bpf_insn) (8) bytes in PID 1's userspace.
        # Sv39 translation and the DDR bound are checked on every page.
        insns_addr = u64(attr, 8)
        if not insns_addr or insns_addr >= (1 << 38):
            raise ValueError("invalid BPF user instruction pointer")
        insns_sha256 = hashlib.sha256(mem.virt(pgd, insns_addr, insn_count * 8)).hexdigest()
        result = {
            "boot_cycles": int(pins["boot_cycles"]),
            "linux_image_sha256": pins["linux_image_sha256"],
            "snapshot_host_sha256": pins["snapshot_host_sha256"],
            "pid1_task": f"0x{task:x}",
            "syscall": "bpf(BPF_PROG_LOAD)",
            "syscall_epc": f"0x{u64(regs):x}",
            "bpf_attr_addr": f"0x{attr_addr:x}",
            "prog_name": name,
            "prog_type": u32(attr),
            "insn_cnt": insn_count,
            "insns_addr": f"0x{insns_addr:x}",
            "insns_sha256": insns_sha256,
            "expected_attach_type": u32(attr, 0x44),
            "env_addr": f"0x{ENV_ADDR:x}",
            "insn_idx": idx,
            "prev_insn_idx": u32(env, 4),
            "stack_size": u32(env, 0x20),
            "pass_cnt": u32(env, 0x2DF8),
            "insn_processed": u32(env, 0x2E04),
            "jmps_processed": u32(env, 0x2E0C),
            "max_states_per_insn": u32(env, 0x2E18),
            "total_states": u32(env, 0x2E1C),
            "peak_states": u32(env, 0x2E20),
            "caveat": "Saved syscall frame and verifier env, not proof of deadlock or login. "
                      "Manifest SHA consistency alone does not establish GitHub artifact provenance.",
        }
    return result


def compare(rows):
    rows = sorted(rows, key=lambda row: row["boot_cycles"])
    if len({row["boot_cycles"] for row in rows}) != len(rows):
        raise ValueError("duplicate cycle checkpoints")
    for previous, current in zip(rows, rows[1:]):
        same = all(previous[key] == current[key] for key in
                   ("linux_image_sha256", "pid1_task", "syscall_epc",
                    "bpf_attr_addr", "prog_name", "prog_type", "insn_cnt",
                    "insns_sha256", "expected_attach_type", "env_addr", "pass_cnt"))
        current["same_candidate_syscall_as_previous"] = same
        if same:
            current["insn_processed_delta"] = current["insn_processed"] - previous["insn_processed"]
            current["total_states_delta"] = current["total_states"] - previous["total_states"]
            current["jmps_processed_delta"] = current["jmps_processed"] - previous["jmps_processed"]
            if min(current["insn_processed_delta"], current["total_states_delta"],
                   current["jmps_processed_delta"]) < 0:
                raise ValueError("verifier counters regressed despite same candidate invocation")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoints", nargs="+", type=Path)
    args = parser.parse_args()
    print(json.dumps(compare([inspect(path) for path in args.checkpoints]),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
