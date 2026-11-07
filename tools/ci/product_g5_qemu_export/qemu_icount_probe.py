#!/usr/bin/env python3
"""Measure Product Debian D2 *QEMU instructions*, never Product RTL cycles.

QEMU must run the exact Product OpenSBI fw_payload, DTB and Linux Image,
with -S, one hart, GDB and QMP sockets, and `-icount shift=N,align=off,sleep=off`.
This probe uses the existing architecture-export RSP/QMP transport, stops the
*same QEMU process* at the entry of populate_rootfs and run_init_process,
and queries QMP query-replay.icount at both stops.

This is a functional-workload oracle only. It is not a cycle-accurate model,
an RTL handoff, or evidence that the Debian PID1 marker executed.
"""

import argparse
import json
from pathlib import Path
import signal
import sys
import time

from qemu_arch_export import (ProtocolError, Qmp, Rsp,
                              registers_from_annexes, sha256,
                              system_map_symbol)


PHASES = ("populate_rootfs", "run_init_process")
QUALIFICATION = "QEMU_G5_D2_INSN_COUNT_NOT_RTL_CYCLES_NOT_PID1_PASS"


def at_breakpoint(rsp, qmp, symbol, pc, pc_register_number):
    """One GDB hardware breakpoint, one stop, one QMP instruction sample."""
    answer = rsp.request(f"Z1,{pc:x},4")
    if answer != "OK":
        raise ProtocolError(f"QEMU refused {symbol} hardware breakpoint: {answer}")
    stopped_at_break = False
    try:
        stop = rsp.request("c")
        if not stop.startswith(("T05", "S05")):
            raise ProtocolError(f"{symbol}: unexpected GDB stop: {stop[:100]}")
        stopped_at_break = True
        actual_pc = rsp.reg(pc_register_number)
        if actual_pc != pc:
            raise ProtocolError(f"{symbol}: stopped at {actual_pc!r}, not {pc:#x}")
        status = qmp.command("query-status")
        if status.get("running") or status.get("status") not in {"paused", "debug"}:
            raise ProtocolError(f"{symbol}: QEMU not stopped: {status}")
        cpus = qmp.command("query-cpus-fast")
        if not isinstance(cpus, list) or len(cpus) != 1 or cpus[0].get("cpu-index") != 0:
            raise ProtocolError(f"{symbol}: exactly one hart required: {cpus}")
        replay = qmp.command("query-replay")
        count = replay.get("icount") if isinstance(replay, dict) else None
        if isinstance(count, bool) or not isinstance(count, int) or count <= 0:
            raise ProtocolError(f"{symbol}: QEMU icount missing/zero: {replay}")
        return {"symbol": symbol, "breakpoint_pc": f"0x{pc:x}",
                "qemu_guest_instructions": count,
                "qemu_mode": replay.get("mode"),
                "qemu_status": status.get("status")}
    finally:
        # Never attempt another GDB packet if a timeout left QEMU running.
        if stopped_at_break:
            removed = rsp.request(f"z1,{pc:x},4")
            if removed != "OK":
                raise ProtocolError(f"{symbol}: could not remove breakpoint: {removed}")


def collect(rsp, qmp, symbols):
    status = qmp.command("query-status")
    if status.get("running") or status.get("status") not in {"prelaunch", "paused", "debug"}:
        raise ProtocolError(f"QEMU must start with -S: {status}")
    stop = rsp.request("?")
    if not stop.startswith(("T", "S")):
        raise ProtocolError(f"GDB did not report initial stop: {stop[:100]}")
    features = registers_from_annexes(rsp.xml)
    if "pc" not in features:
        raise ProtocolError("QEMU GDB XML has no PC register")
    samples = [at_breakpoint(rsp, qmp, name, symbols[name], features["pc"])
               for name in PHASES]
    first, second = (sample["qemu_guest_instructions"] for sample in samples)
    if second <= first:
        raise ProtocolError(f"QEMU icount not monotonic: {first} -> {second}")
    return samples, second - first


def run(args, rsp_class=Rsp, qmp_class=Qmp):
    if not 1 <= args.wall_seconds <= 600:
        raise ProtocolError("--wall-seconds must be in 1..600")
    if not 0 <= args.icount_shift <= 16:
        raise ProtocolError("--icount-shift must be in 0..16")
    out = Path(args.out_dir).resolve()
    if out.exists() and any(out.iterdir()):
        # QEMU already created its sockets and serial log in this directory.
        allowed = {"gdb.sock", "qmp.sock", "serial.log", "qemu.log", "probe.log"}
        existing = {path.name for path in out.iterdir()}
        if not existing <= allowed:
            raise ProtocolError(f"output dir contains prior evidence: {existing - allowed}")
    out.mkdir(parents=True, exist_ok=True)
    inputs = {"firmware": Path(args.firmware).resolve(),
              "dtb": Path(args.dtb).resolve(),
              "linux_image": Path(args.linux_image).resolve(),
              "system_map": Path(args.system_map).resolve()}
    if not all(path.is_file() and path.stat().st_size > 0 for path in inputs.values()):
        raise ProtocolError("exact Product FW/DTB/Linux Image/System.map inputs required")
    symbols = {name: system_map_symbol(inputs["system_map"], name) for name in PHASES}
    if symbols["populate_rootfs"] == symbols["run_init_process"]:
        raise ProtocolError("kernel phase breakpoint addresses collide")

    timer = time.monotonic()
    rsp = rsp_class(args.gdb_socket, args.wall_seconds)
    try:
        qmp = qmp_class(args.qmp_socket, args.wall_seconds)
        try:
            samples, delta = collect(rsp, qmp, symbols)
        finally:
            qmp.close()
    finally:
        rsp.close()

    manifest = {
        "qualification": QUALIFICATION,
        "qemu_cpu_count": 1,
        "product_rtl": False,
        "cycle_accurate": False,
        "same_qemu_process": True,
        "icount_shift_configured_by_launcher": args.icount_shift,
        "qemu_command_icount": f"shift={args.icount_shift},align=off,sleep=off",
        "inputs": {name: {"path": str(path), "sha256": sha256(path)}
                   for name, path in inputs.items()},
        "phase_samples": samples,
        "populate_to_run_init_process_qemu_instructions": delta,
        "host_probe_wall_seconds": round(time.monotonic() - timer, 3),
        "notes": [
            "icount counts QEMU-dispatched guest instructions; it is not Product RTL cycles.",
            "The selected QEMU virtual-time shift may change interrupt timing versus RTL.",
            "run_init_process is a kernel pre-PID1 breakpoint, not a Debian PID1 PASS.",
            "No RAM, CPU, device or RTL architectural state transfer is claimed.",
        ],
    }
    (out / "icount-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(f"G5_QEMU_ICOUNT_PHASE phase=populate_rootfs pc={samples[0]['breakpoint_pc']} "
          f"instructions={samples[0]['qemu_guest_instructions']}", flush=True)
    print(f"G5_QEMU_ICOUNT_PHASE phase=run_init_process pc={samples[1]['breakpoint_pc']} "
          f"instructions={samples[1]['qemu_guest_instructions']}", flush=True)
    print(f"G5_QEMU_ICOUNT_DIFFERENCE instructions={delta} "
          f"icount_shift={args.icount_shift} qualification={QUALIFICATION}", flush=True)
    return manifest


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--qmp-socket", required=True)
    ap.add_argument("--gdb-socket", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--firmware", required=True)
    ap.add_argument("--dtb", required=True)
    ap.add_argument("--linux-image", required=True)
    ap.add_argument("--system-map", required=True)
    ap.add_argument("--icount-shift", type=int, default=6)
    ap.add_argument("--wall-seconds", type=int, default=300)
    return ap.parse_args(argv)


def main():
    args = parse_args()

    def expired(_signum, _frame):
        raise ProtocolError(f"QEMU icount probe exceeded {args.wall_seconds}s wall budget")

    signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, args.wall_seconds)
    try:
        run(args)
    except (ProtocolError, OSError, ValueError) as exc:
        print(f"G5_QEMU_ICOUNT_REJECTED reason={exc}", file=sys.stderr)
        try:
            out = Path(args.out_dir)
            out.mkdir(parents=True, exist_ok=True)
            (out / "icount-failure.json").write_text(
                json.dumps({"qualification": "NO_QEMU_ICOUNT_PASS",
                            "error": str(exc)}, indent=2) + "\n")
        except OSError:
            pass
        return 3
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
