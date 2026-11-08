#!/usr/bin/env python3
"""Bounded live QEMU GDB single-step oracle paired to an atomic RAM export.

This is QEMU-only evidence, NEVER a Product G5 RTL retirement or full DiffTest.
The already-paused guest must be the same live process that produced the
manifest and RAM; this code single-steps AFTER the immutable RAM export.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import signal
import subprocess
import sys

from qemu_arch_export import (Qmp, ProtocolError, RAM_BASE, RAM_SIZE, Rsp,
                              registers_from_annexes)


KEY_CSRS = ("mstatus", "satp", "mip", "mie", "mepc", "mcause", "mtval",
            "sepc", "scause", "stval", "sstatus", "stvec", "mtvec")
SOURCE_PC = 0xFFFFFFFF808C3490
SOURCE_RVC = 0x7179
SOURCE_MISA = 0x800000000014112D
EXPECTED_SP_AFTER = 0xFFFFFFC80000BE90
QUALIFICATION = "QEMU_SINGLESTEP_ORACLE_PAIRED_WITH_SAME_CAPTURED_RAM_NOT_RTL_DIFFTEST"
PAGE_SIZE = 4096


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def require(value, message):
    if not value:
        raise ProtocolError(message)


def read_state(rsp, features):
    values = {}
    for name in ["pc", "priv"] + [f"x{i}" for i in range(32)] + list(KEY_CSRS):
        if name not in features:
            if name in ("pc", "priv") or name.startswith("x"):
                raise ProtocolError(f"QEMU GDB missing mandatory register {name}")
            continue
        value = rsp.reg(features[name])
        if value is None:
            if name in ("pc", "priv") or name.startswith("x"):
                raise ProtocolError(f"QEMU GDB returned unavailable {name}")
            continue
        values[name] = f"0x{value:x}"
    require(values["x0"] == "0x0", "QEMU x0 is not hardwired zero")
    return values


def read_instruction(rsp, pc):
    # QEMU's GDB virtual-memory mode is explicitly checked by the caller.
    # Hardware Z1 breakpoint does not replace these instruction bytes.
    raw_half = rsp.request(f"m{pc:x},2")
    if not re.fullmatch(r"[a-fA-F0-9]{4}", raw_half):
        raise ProtocolError(f"QEMU GDB cannot read RISC-V instruction halfword at {pc:#x}: {raw_half}")
    half = int.from_bytes(bytes.fromhex(raw_half), "little")
    length = 2 if half & 3 != 3 else 4
    raw = raw_half if length == 2 else rsp.request(f"m{pc:x},4")
    if not re.fullmatch(rf"[a-fA-F0-9]{{{length*2}}}", raw):
        raise ProtocolError(f"QEMU GDB cannot read {length} instruction bytes at {pc:#x}: {raw}")
    data = bytes.fromhex(raw)
    opcode = int.from_bytes(data[:length], "little")
    return {"opcode": f"0x{opcode:0{length*2}x}",
            "instruction_length": length,
            "bytes_le": data[:length].hex()}


def record(rsp, qmp, source, count):
    require(1 <= count <= 256, "single-step count must be 1..256")
    status = qmp.command("query-status")
    require(not status.get("running") and
            status.get("status") in ("paused", "debug", "prelaunch"),
            f"QEMU not paused at exact source snapshot: {status}")
    cpus = qmp.command("query-cpus-fast")
    require(isinstance(cpus, list) and len(cpus) == 1 and
            cpus[0].get("cpu-index") == 0,
            "QEMU multiretire requires exactly one hart")
    features = registers_from_annexes(rsp.xml)
    initial = read_state(rsp, features)
    source_reg = source["qemu_gdb_registers"]
    for name in ("pc", "priv", "mstatus", "satp") + tuple(f"x{i}" for i in range(32)):
        require(name in source_reg and name in initial and
                int(source_reg[name], 0) == int(initial[name], 0),
                f"QEMU live state differs from the paired RAM export: {name}")
    require(int(initial["pc"], 16) == SOURCE_PC and int(initial["priv"], 16) == 1,
            "wrong QEMU kernel handoff boundary or privilege")
    # Ensure the old kernel HW breakpoint is removed before stepping; a
    # still-installed Z1 may otherwise re-stop at the same PC indefinitely.
    require(rsp.request(f"z1,{SOURCE_PC:x},4") == "OK",
            "could not remove initial QEMU GDB hardware breakpoint")
    mode = rsp.request("qqemu.PhyMemMode")
    require(mode in ("0", "1"),
            f"QEMU does not support unambiguous debugger memory mode: {mode}")
    if mode == "1":
        require(rsp.request("Qqemu.PhyMemMode:0") == "OK",
                "QEMU could not switch GDB to translated virtual address reads")
    require(rsp.request("qqemu.PhyMemMode") == "0",
            "QEMU virtual read mode not confirmed before opcode sampling")
    trace = []
    previous = initial
    for i in range(count):
        pc = int(previous["pc"], 16)
        instruction = read_instruction(rsp, pc)
        stop = rsp.request("s")
        require(stop.startswith(("S05", "T05")),
                f"unexpected GDB stop after step {i}: {stop[:100]}")
        current = read_state(rsp, features)
        if i == 0:
            require(instruction["opcode"] == f"0x{SOURCE_RVC:04x}" and
                    instruction["instruction_length"] == 2 and
                    int(current["pc"], 16) == SOURCE_PC + 2 and
                    int(current["x2"], 16) == EXPECTED_SP_AFTER,
                    "first real Linux RVC step differs from independent oracle")
        # QEMU's GDB single stepping may trigger a pending timer interrupt,
        # trap entry, or MMIO. Stop at the first explicit privilege/trap
        # discontinuity instead of claiming that this is a clean DiffTest.
        require(int(current["priv"], 16) == 1,
                f"privilege/trap boundary reached at step {i}; not a clean S-mode trace")
        if "mip" in current and "mip" in previous:
            require(current["mip"] == previous["mip"],
                    f"pending interrupt state changed at step {i}")
        changed = {f"x{r}": current[f"x{r}"] for r in range(1, 32)
                   if current[f"x{r}"] != previous[f"x{r}"]}
        trace.append({"step": i + 1, "pc_before": previous["pc"],
                      "pc_after": current["pc"], "priv_before": previous["priv"],
                      "priv_after": current["priv"], "instruction": instruction,
                      "gpr_changes": changed, "gpr_after":
                      [current[f"x{r}"] for r in range(32)],
                      "csr_before": {k: previous[k] for k in KEY_CSRS if k in previous},
                      "csr_after": {k: current[k] for k in KEY_CSRS if k in current},
                      "gdb_stop": stop[:120]})
        previous = current
    return {"entries": trace, "initial_state": initial, "final_state": previous}


def memory_delta(qmp, original_ram, stage_path):
    """Compare final DDR once (not per step); never overwrite the source RAM."""
    require(not stage_path.exists(), "post-step scratch DDR file already exists")
    try:
        qmp.command("pmemsave", {"val": RAM_BASE, "size": RAM_SIZE,
                                  "filename": str(stage_path.resolve())})
        require(stage_path.is_file() and stage_path.stat().st_size == RAM_SIZE,
                "QEMU post-step physical RAM dump missing or truncated")
        result = {"post_step_ram_sha256": sha(stage_path),
                  "source_ram_sha256": sha(original_ram), "page_size": PAGE_SIZE,
                  "modified_pages": [], "modified_bytes": 0,
                  "memory_delta_is_per_instruction": False}
        with original_ram.open("rb") as old, stage_path.open("rb") as new:
            for index in range(RAM_SIZE // PAGE_SIZE):
                before, after = old.read(PAGE_SIZE), new.read(PAGE_SIZE)
                require(len(before) == len(after) == PAGE_SIZE,
                        "short read during QEMU full DDR delta")
                if before == after:
                    continue
                require(len(result["modified_pages"]) < 128,
                        "QEMU trace touched too many pages for bounded patch")
                ranges = []
                pos = 0
                while pos < PAGE_SIZE:
                    if before[pos] == after[pos]:
                        pos += 1
                        continue
                    end = pos + 1
                    while end < PAGE_SIZE and before[end] != after[end]:
                        end += 1
                    result["modified_bytes"] += end - pos
                    require(result["modified_bytes"] <= 512 * 1024,
                            "QEMU trace changed more than 512KiB; diff not bounded")
                    ranges.append({"offset": pos, "old_hex": before[pos:end].hex(),
                                   "new_hex": after[pos:end].hex()})
                    pos = end
                result["modified_pages"].append({
                    "physical_page": hex(RAM_BASE + index * PAGE_SIZE),
                    "old_sha256": hashlib.sha256(before).hexdigest(),
                    "new_sha256": hashlib.sha256(after).hexdigest(),
                    "ranges": ranges,
                })
        return result
    finally:
        # The oracle archives only hashes and bounded patches; the giant
        # transient second RAM dump is not a reusable checkpoint artifact.
        if stage_path.exists():
            stage_path.unlink()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--gdb-socket", required=True)
    p.add_argument("--qmp-socket", required=True)
    p.add_argument("--source-manifest", type=Path, required=True)
    p.add_argument("--source-ram", type=Path, required=True)
    p.add_argument("--source-walk", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("--qemu-bin", type=Path, required=True)
    p.add_argument("--cpu-profile", required=True)
    p.add_argument("--count", type=int, default=64)
    p.add_argument("--wall-seconds", type=int, default=90)
    a = p.parse_args()
    require(1 <= a.count <= 256 and 1 <= a.wall_seconds <= 180,
            "invalid bounded QEMU trace budget")
    require(a.cpu_profile == "rv64,sv48=off,sv57=off,h=off",
            "only matching Sv39 no-H source profile is supported")
    require(a.report.parent.is_dir() and not a.report.exists(),
            "retirement oracle destination must be a fresh file")
    source = json.loads(a.source_manifest.read_text())
    require(source.get("qualification") ==
            "QEMU_ARCH_EXPORT_DIAGNOSTIC_ONLY_NOT_RTL_HANDOFF_PASS" and
            source.get("arch_restore_ready") is False and
            source.get("rtl_cpu_injected") is False,
            "source manifest misrepresents the QEMU snapshot")
    require(source.get("qemu_cpu_count") == 1 and
            source.get("memory", {}).get("start") == hex(RAM_BASE) and
            source.get("memory", {}).get("bytes") == RAM_SIZE and
            a.source_ram.stat().st_size == RAM_SIZE,
            "the paired snapshot does not describe exact 256MiB RAM")
    require(int(source["qemu_gdb_registers"].get("misa", "-1"), 0) == SOURCE_MISA,
            "QEMU Sv39 no-H ISA is not the one compared with Product G5")
    source_ram_sha = sha(a.source_ram)
    require(source_ram_sha == source["memory"]["sha256"],
            "paired RAM SHA differs from the live-export manifest")
    walk = json.loads(a.source_walk.read_text())
    require(walk.get("source_pc") == hex(SOURCE_PC) and
            walk.get("satp_mode") == 8 and
            walk.get("instruction_bytes_le") == "7971" and
            walk.get("rtl_handoff_pass") is False,
            "source RAM's independently walked first Linux RVC changed")
    version = subprocess.check_output([str(a.qemu_bin), "--version"],
                                      text=True, timeout=10).splitlines()[0]
    identity = {"qemu_binary_sha256": sha(a.qemu_bin),
                "qemu_version": version,
                "machine": "virt", "cpu": a.cpu_profile,
                "harts": 1, "ram_bytes": RAM_SIZE,
                "qemu_guest_boot_source_input_sha256": source.get("inputs", {}),
                "source_ram_sha256": source_ram_sha,
                "source_manifest_sha256": sha(a.source_manifest),
                "source_first_instruction_walk_sha256": sha(a.source_walk)}
    def deadline(_signum, _frame):
        raise ProtocolError("QEMU multiretire oracle exceeded wall budget")
    signal.signal(signal.SIGALRM, deadline)
    signal.setitimer(signal.ITIMER_REAL, a.wall_seconds)
    rsp = Rsp(a.gdb_socket, a.wall_seconds)
    try:
        qmp = Qmp(a.qmp_socket, a.wall_seconds)
        try:
            result = record(rsp, qmp, source, a.count)
            result["end_memory_delta"] = memory_delta(
                qmp, a.source_ram, a.report.with_suffix(".post-step-ram.tmp"))
        finally:
            qmp.close()
    finally:
        rsp.close()
        signal.setitimer(signal.ITIMER_REAL, 0)
    result.update({"qualification": QUALIFICATION, "identity": identity,
                   "retired_instruction_count": len(result["entries"]),
                   "first_retire_oracle_verified": True,
                   "real_product_rtl_retire_count": 0,
                   "full_device_and_memory_diff_test": False,
                   "debian_service_login_pass": False,
                   "warnings": [
                       "QEMU GDB single-step changes execution timing and may perturb interrupts",
                       "QEMU does not expose exact Product G5 cache/ROB/PLIC/UART/LRSC state",
                       "GPR and selected CSR/PC trace is not a memory/device DiffTest",
                       "DDR patch is end-of-window only, not per retired instruction",
                       "The paired source RAM may differ from other QEMU runs; match by SHA",
                   ]})
    a.report.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(f"PRODUCT_C1_QEMU_SINGLESTEP_ORACLE_CAPTURED_NOT_RTL_DIFFTEST"
          f" steps={len(result['entries'])} ram_sha256={source_ram_sha}"
          f" first_pc={result['entries'][0]['pc_before']}")


if __name__ == "__main__":
    try:
        main()
    except (ProtocolError, OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        print("PRODUCT_C1_QEMU_SINGLESTEP_ORACLE_REJECTED: " + str(exc), file=sys.stderr)
        sys.exit(16)
