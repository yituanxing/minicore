#!/usr/bin/env python3
"""Read-only, Image-pinned AETSDCP1 DDR task and stack inspection for SD1.

This is not a runtime success detector. Reports anchored kernel task_struct
fields and cautiously labels likely stack return addresses from exact symbols.
The snapshot is NEVER written or advanced.
"""

import argparse
import bisect
import hashlib
import json
from pathlib import Path
import re
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
MM_PGD_OFF = 0x70
STACK_OFF = 0x38
REAL_PARENT_OFF = 0x448
THREAD_RA_OFF = 0x970
THREAD_SP_OFF = 0x978
PT_REGS_OFF = 0x3EE0
IMAGE_SHA = "522e2d5847534962eefc0b44561c03e8aa79b0e94acb7e75e4a985a3aa6b6393"
MAP_SHA = "117c099b65e37c891e5f3383ae36686967577dcb7be862a7f6066d9bf69061da"
QUALIFICATION = "PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS"


def u32(data, offset=0):
    return struct.unpack_from("<I", data, offset)[0]


def u64(data, offset=0):
    return struct.unpack_from("<Q", data, offset)[0]


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(4 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_manifest(path):
    result = {}
    for line in path.read_text().splitlines():
        key, sep, value = line.partition("=")
        if not sep or not key or key in result:
            raise ValueError("invalid manifest key " + key)
        result[key] = value
    if (result.get("schema") != "AETSDCP1" or
        result.get("qualification") != QUALIFICATION or
        result.get("linux_image_sha256") != IMAGE_SHA):
        raise ValueError("checkpoint schema/qualification/Image mismatch")
    return result


def ddr_offset(stream):
    if stream.read(8) != b"AETSDCP1" or u64(stream.read(8)) != 1:
        raise ValueError("wrong host format")
    for _ in range(8):
        length = u64(stream.read(8))
        if length > 40_000_000:
            raise ValueError("unreasonable identity length")
        stream.seek(length, 1)
    stream.read(18 * 8)
    length = u64(stream.read(8))
    if length > 40_000_000:
        raise ValueError("bad UART length")
    stream.seek(length + 36 * 8, 1)
    if u64(stream.read(8)) != DDR_SIZE:
        raise ValueError("not a complete DDR snapshot")
    return stream.tell()


class DDR:
    def __init__(self, stream):
        self.stream = stream
        self.base = ddr_offset(stream)

    def phys(self, addr, count):
        if addr < DDR_BASE or addr + count > DDR_BASE + DDR_SIZE:
            raise ValueError(f"phys out of range {addr:#x} {count}")
        self.stream.seek(self.base + addr - DDR_BASE)
        data = self.stream.read(count)
        if len(data) != count:
            raise ValueError("truncated physical read")
        return data

    @staticmethod
    def direct_phys(addr):
        if LINEAR_VIRT <= addr < LINEAR_VIRT + DDR_SIZE:
            return DDR_BASE + addr - LINEAR_VIRT
        if KERNEL_VIRT <= addr < KERNEL_VIRT + DDR_SIZE:
            return KERNEL_PHYS + addr - KERNEL_VIRT
        raise ValueError(f"not kernel direct map: {addr:#x}")

    def direct(self, addr, count):
        return self.phys(self.direct_phys(addr), count)

    def qdirect(self, addr):
        return u64(self.direct(addr, 8))

    def walk(self, root, addr):
        table = self.direct_phys(root)
        for level in (2, 1, 0):
            slot = (addr >> (12 + level * 9)) & 511
            pte = u64(self.phys(table + slot * 8, 8))
            if not (pte & 1):
                raise ValueError(f"invalid Sv39 PTE: {addr:#x} L{level}")
            if pte & 0xE:
                lower = 12 + level * 9
                return ((pte >> 10) << 12) + (addr & ((1 << lower) - 1))
            table = (pte >> 10) << 12
        raise ValueError("no Sv39 leaf")

    def virt(self, root, addr, count):
        result = bytearray()
        while count:
            size = min(count, 4096 - (addr & 4095))
            result.extend(self.phys(self.walk(root, addr), size))
            addr += size
            count -= size
        return bytes(result)

    def tasks(self):
        head = INIT_TASK + TASKS_OFF
        node = self.qdirect(head)
        seen = set()
        tasks = []
        while node != head and len(seen) < 1024:
            if node in seen:
                raise ValueError("loop in task list")
            seen.add(node)
            task = node - TASKS_OFF
            if not (LINEAR_VIRT <= task < LINEAR_VIRT + DDR_SIZE):
                raise ValueError(f"unexpected task pointer {task:#x}")
            task_raw = self.direct(task, 0xA00)
            pid = u32(task_raw, PID_OFF)
            if pid >= 1_000_000:
                raise ValueError("bad PID in task list")
            tasks.append((pid, task, task_raw))
            node = u64(task_raw, TASKS_OFF)
        if len(seen) >= 1024:
            raise ValueError("too many tasks")
        return sorted(tasks)


def symbols(path):
    if sha256(path) != MAP_SHA:
        raise ValueError("System.map SHA mismatch")
    found = []
    for line in path.read_text().splitlines():
        m = re.fullmatch(r"([a-f0-9]{16})\s+([TtWw])\s+(\S+)", line)
        if m:
            found.append((int(m.group(1), 16), m.group(3)))
    found.sort()
    return found


def likely_names(raw):
    candidates = []
    for match in re.finditer(rb"[a-zA-Z(][a-zA-Z0-9_./+()-]{2,15}\x00", raw):
        off = match.start()
        if not off % 8:
            candidates.append((off, match.group()[:-1].decode("ascii")))
    return candidates


def retaddr_symbols(mem, task_raw, entries, cap=24):
    stack = u64(task_raw, STACK_OFF)
    if not stack:
        return {"stack": None}
    result = {"stack": f"0x{stack:x}", "saved_ra": f"0x{u64(task_raw, THREAD_RA_OFF):x}",
              "saved_sp": f"0x{u64(task_raw, THREAD_SP_OFF):x}"}
    try:
        data = mem.virt(SWAPPER_PGD, stack, 16 * 1024)
    except ValueError as e:
        result["stack_error"] = str(e)
        return result
    regs = data[PT_REGS_OFF:PT_REGS_OFF + 0x120]
    result["saved_trap_frame"] = {
        "epc": hex(u64(regs, 0)),
        "a0": hex(u64(regs, 0x50)),
        "a7": u64(regs, 0x88),
        "cause": u64(regs, 0x110),
        "orig_a0": hex(u64(regs, 0x118)),
    }
    addresses = [a for a, _ in entries]
    hits = []
    for off in range(0, len(data), 8):
        val = u64(data, off)
        if not KERNEL_VIRT <= val < KERNEL_VIRT + 0x1000000:
            continue
        pos = bisect.bisect_right(addresses, val) - 1
        if pos < 0:
            continue
        a, name = entries[pos]
        delta = val - a
        if 0 < delta < 0x400:
            hits.append({"stack_off":hex(off), "return_addr":hex(val), "symbol":name, "delta":hex(delta)})
    result["return_addresses"] = hits[-cap:]
    result["return_address_count"] = len(hits)
    return result


def inspect(checkpoint, system_map, include_stacks, include_user_code):
    pins = read_manifest(checkpoint / ".migration-ready")
    for kind in ("host", "rtl"):
        path = checkpoint / ("persistent.pre." + kind)
        if sha256(path) != pins.get("snapshot_" + kind + "_sha256"):
            raise ValueError(f"{kind} SHA mismatch")
    if sha256(checkpoint / "card-progress.json") != pins["card_progress_sha256"]:
        raise ValueError("progress SHA mismatch")
    sym = symbols(system_map)
    with (checkpoint / "persistent.pre.host").open("rb") as f:
        mem = DDR(f)
        tasks = mem.tasks()
        pid_by_addr = {addr: pid for pid, addr, _ in tasks}
        rows = []
        for pid, addr, raw in tasks:
            parent = u64(raw, REAL_PARENT_OFF)
            row = {
                "pid": pid, "task": f"0x{addr:x}",
                "parent_pid": pid_by_addr.get(parent),
                "mm": f"0x{u64(raw, MM_OFF):x}",
                "task_state": u32(raw, 0x30),
                "task_state_name": {0: "TASK_RUNNING", 1: "TASK_INTERRUPTIBLE",
                                    2: "TASK_UNINTERRUPTIBLE", 128: "TASK_DEAD",
                                    1026: "TASK_IDLE"}.get(u32(raw, 0x30), "other"),
                "thread_state_candidates": {"off_0x18": u64(raw, 0x18), "off_0x20": u64(raw, 0x20),
                                            "off_0x28": u64(raw, 0x28),
                                            "off_0x30": u64(raw, 0x30),
                                            "off_0x40": u64(raw, 0x40),
                                            "off_0x48": u64(raw, 0x48),
                                            "off_0x50": u64(raw, 0x50),
                                            "off_0x58": u64(raw, 0x58),
                                            "off_0x60": u64(raw, 0x60),
                                            "off_0x68": u64(raw, 0x68),
                                            "off_0x70": u64(raw, 0x70)},
                "name_candidates": likely_names(raw),
            }
            if include_stacks and (pid == 1 or pid >= 40):
                row.update(retaddr_symbols(mem, raw, sym))
            rows.append(row)
        result = {"boot_cycles": int(pins["boot_cycles"]), "verified_host_sha": pins["snapshot_host_sha256"],
                "verified_system_map_sha": MAP_SHA, "task_count": len(tasks), "tasks": rows,
                "caveat": "Read-only task-list snapshot. Raw state candidates/stack symbol hits do NOT prove process exit; mm=NULL plus disappearance in later checkpoint is stronger."}
        if include_user_code:
            progress = json.loads((checkpoint / "card-progress.json").read_text())
            observed = progress["cpu_observer"]["last_16_retired_pc"]
            pid1 = next((raw for pid, _, raw in tasks if pid == 1), None)
            if pid1 is None:
                raise ValueError("PID1 absent")
            mm = u64(pid1, MM_OFF)
            pgd = mem.qdirect(mm + MM_PGD_OFF)
            samples = []
            for v in observed:
                pc = int(v, 16)
                if 0 <= pc < (1 << 38):
                    try:
                        data = mem.virt(pgd, pc, 4)
                        samples.append({"pc": v, "bytes": data.hex(),
                                        "size": 4 if data[0] & 3 == 3 else 2})
                    except ValueError as e:
                        samples.append({"pc": v, "error": str(e)})
            result["user_code_samples"] = samples
        return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("system_map", type=Path)
    parser.add_argument("--stacks", action="store_true")
    parser.add_argument("--user-code", action="store_true")
    args = parser.parse_args()
    print(json.dumps(inspect(args.checkpoint, args.system_map, args.stacks,
                             args.user_code), ensure_ascii=False, indent=2))
