#!/usr/bin/env python3
"""Read-only QEMU GDB/QMP probe of the exact sd_bind6 verifier window.

Launch QEMU separately with one RISC-V hart, -S, Unix GDB/QMP sockets and
`-icount shift=N,align=off,sleep=off`.  This program never changes guest
memory, the Debian disk, or Product RTL checkpoints.  GDB hardware breakpoints
do perturb QEMU wall time: only the QMP guest instruction counts are comparable.

Required launcher options (the QEMU-virt Debian kernel/drive arguments are
otherwise unchanged from product_sd1_qemu_watch.py):
  -smp 1 -S -icount shift=6,align=off,sleep=off
  -gdb unix:/path/gdb.sock,server=on,wait=off
  -qmp unix:/path/qmp.sock,server=on,wait=off
The launcher owns process cleanup and should kill QEMU on probe timeout.  The
probe requires the **same build** QEMU-virt Linux Image and System.map.

The observed Linux Image is a QEMU-virt Image, not the signed Product SPI
Image.  Even matching BPF program SHA is not proof that kernel configurations
or verifier implementation are byte-for-byte identical.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import signal
import struct
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent / "product_g5_qemu_export"))
from qemu_arch_export import (ProtocolError, Qmp, Rsp, registers_from_annexes,
                              sha256, system_map_symbol)
from product_sd1_qemu_watch import BootProof


QUALIFICATION = "QEMU_SD1_SAME_BPF_BYTECODE_VERIFIER_ICOUNT_NOT_PRODUCT_RTL"
PROGRAM_NAME = "sd_bind6"
PROGRAM_TYPE = 18  # BPF_PROG_TYPE_CGROUP_SOCK_ADDR
ATTACH_TYPE = 9    # BPF_CGROUP_INET6_BIND
INSTRUCTION_COUNT = 82
VERIFIER_ENV_SIZE = 0x2E24  # Linux 6.6.143, pinned struct bpf_verifier_env
MAX_ENTRY_HITS = 256


def little_u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def little_u64(data, offset):
    return struct.unpack_from("<Q", data, offset)[0]


def signed_rv64(value):
    if value is None:
        raise ProtocolError("unavailable RV64 function return register")
    return value - (1 << 64) if value & (1 << 63) else value


def read_memory(rsp, address, count):
    """Bounded RSP m packets, read-only (no M/X guest write packets)."""
    if not 0 < address < (1 << 64) or not 0 < count <= 16384:
        raise ProtocolError("invalid bounded guest memory read")
    chunks = []
    while count:
        length = min(256, count)
        response = rsp.request(f"m{address:x},{length:x}")
        if not re.fullmatch(rf"[0-9a-fA-F]{{{length * 2}}}", response):
            raise ProtocolError(f"guest memory unreadable at {address:#x}")
        chunks.append(bytes.fromhex(response))
        address += length
        count -= length
    return b"".join(chunks)


def classify_bpf_attr(rsp, attr_address, expected_bytecode_sha):
    raw = read_memory(rsp, attr_address, 0x98)
    name = raw[0x30:0x40].split(b"\0", 1)[0].decode("ascii", "replace")
    program_type = little_u32(raw, 0)
    insn_count = little_u32(raw, 4)
    attach_type = little_u32(raw, 0x44)
    if name != PROGRAM_NAME:
        return None
    if (program_type, insn_count, attach_type) != (
            PROGRAM_TYPE, INSTRUCTION_COUNT, ATTACH_TYPE):
        raise ProtocolError("sd_bind6 identity/type/instruction-count mismatch")
    insn_address = little_u64(raw, 8)
    bytecode = read_memory(rsp, insn_address, INSTRUCTION_COUNT * 8)
    digest = hashlib.sha256(bytecode).hexdigest()
    if digest != expected_bytecode_sha:
        raise ProtocolError("sd_bind6 eBPF bytecode SHA differs from Product RTL: not comparable")
    return {
        "name": name,
        "prog_type": program_type,
        "expected_attach_type": attach_type,
        "insn_count": insn_count,
        "insn_user_address": f"0x{insn_address:x}",
        "bytecode_sha256": digest,
    }


def sample_qemu(rsp, qmp, registers, expected_pc, label):
    pc = rsp.reg(registers["pc"])
    if pc != expected_pc:
        raise ProtocolError(f"{label} stopped at {pc}, expected {expected_pc:#x}")
    status = qmp.command("query-status")
    if status.get("running") or status.get("status") not in {"debug", "paused"}:
        raise ProtocolError(f"{label} QEMU was not stopped: {status}")
    cpus = qmp.command("query-cpus-fast")
    if not isinstance(cpus, list) or len(cpus) != 1 or cpus[0].get("cpu-index") != 0:
        raise ProtocolError(f"{label} requires exactly one QEMU hart")
    replay = qmp.command("query-replay")
    count = replay.get("icount") if isinstance(replay, dict) else None
    if isinstance(count, bool) or not isinstance(count, int) or count <= 0:
        raise ProtocolError(f"{label} QEMU icount missing: {replay}")
    return {"label": label, "pc": f"0x{expected_pc:x}",
            "qemu_guest_instructions": count,
            "qemu_replay_mode": replay.get("mode")}


class Breakpoints:
    def __init__(self, rsp, registers):
        self.rsp = rsp
        self.registers = registers
        self.armed = set()
        self.stopped = True

    def add(self, pc):
        if self.rsp.request(f"Z1,{pc:x},4") != "OK":
            raise ProtocolError(f"QEMU refused hardware breakpoint {pc:#x}")
        self.armed.add(pc)

    def remove(self, pc):
        if pc not in self.armed:
            raise ProtocolError(f"breakpoint not armed: {pc:#x}")
        if self.rsp.request(f"z1,{pc:x},4") != "OK":
            raise ProtocolError(f"QEMU could not remove breakpoint {pc:#x}")
        self.armed.remove(pc)

    def resume(self, mode="c"):
        self.stopped = False
        result = self.rsp.request(mode)
        if not result.startswith(("T05", "S05")):
            raise ProtocolError(f"unexpected QEMU GDB stop: {result[:100]}")
        self.stopped = True
        return self.rsp.reg(self.registers["pc"])

    def close(self):
        # Never send a GDB packet if QEMU may still be running after timeout.
        if self.stopped:
            for pc in tuple(self.armed):
                self.remove(pc)


def probe(rsp, qmp, symbols, expected_bytecode_sha):
    status = qmp.command("query-status")
    if status.get("running") or status.get("status") not in {"prelaunch", "debug", "paused"}:
        raise ProtocolError("QEMU must start paused with -S")
    if not rsp.request("?").startswith(("T", "S")):
        raise ProtocolError("GDB did not report an initial stop")
    registers = registers_from_annexes(rsp.xml)
    if any(name not in registers for name in ("pc", "ra", "a0", "a1")):
        raise ProtocolError("QEMU GDB XML lacks RV64 PC/RA/A0/A1")
    bps = Breakpoints(rsp, registers)
    try:
        entry_pc = symbols["bpf_check"]
        bps.add(entry_pc)
        seen_other = 0
        for _ in range(MAX_ENTRY_HITS):
            stop_pc = bps.resume()
            if stop_pc != entry_pc:
                raise ProtocolError(f"unexpected stop before sd_bind6: {stop_pc:#x}")
            attr_pointer = rsp.reg(registers["a1"])
            if attr_pointer is None:
                raise ProtocolError("unavailable bpf_check attr pointer")
            program = classify_bpf_attr(rsp, attr_pointer, expected_bytecode_sha)
            if program is not None:
                break
            seen_other += 1
            # Step past a non-target breakpoint before re-arming it.  Do not
            # rely on implementation-defined GDB continue-over-breakpoint.
            bps.remove(entry_pc)
            if bps.resume("s") is None:
                raise ProtocolError("QEMU failed to step over bpf_check")
            bps.add(entry_pc)
        else:
            raise ProtocolError(f"sd_bind6 not reached within {MAX_ENTRY_HITS} bpf_check calls")

        entry = sample_qemu(rsp, qmp, registers, entry_pc, "sd_bind6_bpf_check_entry")
        bpf_return_pc = rsp.reg(registers["ra"])
        if bpf_return_pc is None or bpf_return_pc == entry_pc:
            raise ProtocolError("invalid bpf_check return PC")
        bps.remove(entry_pc)
        common_pc = symbols["do_check_common"]
        bps.add(common_pc)
        if bps.resume() != common_pc:
            raise ProtocolError("bpf_check did not enter do_check_common")
        env_pointer = rsp.reg(registers["a0"])
        common_return_pc = rsp.reg(registers["ra"])
        if env_pointer is None or common_return_pc is None:
            raise ProtocolError("missing do_check_common env/return address")
        if not 0xFFFFFF0000000000 <= env_pointer < (1 << 64):
            raise ProtocolError("invalid high-kernel verifier env pointer")
        bps.remove(common_pc)
        bps.add(common_return_pc)
        if bps.resume() != common_return_pc:
            raise ProtocolError("do_check_common did not return to bpf_check")
        env = read_memory(rsp, env_pointer, VERIFIER_ENV_SIZE)
        counters = {
            "insn_idx": little_u32(env, 0),
            "stack_size": little_u32(env, 0x20),
            "pass_cnt": little_u32(env, 0x2DF8),
            "insn_processed": little_u32(env, 0x2E04),
            "jmps_processed": little_u32(env, 0x2E0C),
            "total_states": little_u32(env, 0x2E1C),
            "peak_states": little_u32(env, 0x2E20),
        }
        if not 0 < counters["insn_processed"] <= 1000000:
            raise ProtocolError("implausible QEMU verifier insn_processed")
        if counters["pass_cnt"] != 1 or counters["total_states"] == 0:
            raise ProtocolError("unexpected verifier pass count/state data; not comparable")
        common_return_code = signed_rv64(rsp.reg(registers["a0"]))
        common_exit = sample_qemu(rsp, qmp, registers, common_return_pc,
                                  "sd_bind6_do_check_common_return")
        bps.remove(common_return_pc)
        bps.add(bpf_return_pc)
        if bps.resume() != bpf_return_pc:
            raise ProtocolError("bpf_check did not return to bpf_prog_load")
        bpf_exit = sample_qemu(rsp, qmp, registers, bpf_return_pc,
                               "sd_bind6_bpf_check_return")
        bpf_return_code = signed_rv64(rsp.reg(registers["a0"]))
        bps.remove(bpf_return_pc)
        if bpf_exit["qemu_guest_instructions"] <= entry["qemu_guest_instructions"]:
            raise ProtocolError("QEMU icount did not advance through BPF verifier")
        return {
            "qualification": QUALIFICATION,
            "product_rtl": False,
            "same_qemu_process": True,
            "same_program_bytecode_sha": True,
            "other_bpf_calls_before_target": seen_other,
            "program": program,
            "verifier_env_address": f"0x{env_pointer:x}",
            "verifier_counters_at_common_return": counters,
            "do_check_common_return_code": common_return_code,
            "bpf_check_return_code": bpf_return_code,
            "verifier_accepted_program": (
                common_return_code == 0 and bpf_return_code == 0),
            "phase_samples": [entry, common_exit, bpf_exit],
            "verifier_qemu_guest_instructions": (
                bpf_exit["qemu_guest_instructions"] - entry["qemu_guest_instructions"]),
            "notes": [
                "This is the QEMU-virt kernel, not the Product SPI Linux Image.",
                "Matching BPF program bytes is necessary but not sufficient for verifier equivalence.",
                "QEMU -icount is guest dispatched instructions, never RTL cycles.",
                "A separate login icount sample is required for whole-boot percentage.",
            ],
        }
    finally:
        bps.close()


def observe_login_after_detach(rsp, qmp, serial_log, wall_seconds,
                               bpf_exit_icount, poll_seconds=0.02):
    """Optional, bounded I_login sample from the *same* running QEMU process.

    The UART byte and QMP stop are not atomic.  The resulting icount is a
    conservatively later sample, never claimed to equal the UART instruction.
    """
    log = Path(serial_log)
    if not log.is_file():
        raise ProtocolError("--serial-log must be an existing QEMU serial output file")
    if rsp.request("D") != "OK":
        raise ProtocolError("QEMU GDB detach failed; cannot prove full-boot icount")
    status = qmp.command("query-status")
    if not status.get("running"):
        qmp.command("cont")
    proof = BootProof()
    started = time.monotonic()
    position = 0
    with log.open("rb") as stream:
        while time.monotonic() - started < wall_seconds:
            stream.seek(position)
            chunk = stream.read(65536)
            if chunk:
                position += len(chunk)
                proof.observe(chunk)
                if proof.failure:
                    raise ProtocolError(f"QEMU failed before login: {proof.failure}")
                if proof.login_prompt:
                    qmp.command("stop")
                    for _ in range(50):
                        state = qmp.command("query-status")
                        if not state.get("running"):
                            break
                        time.sleep(0.01)
                    else:
                        raise ProtocolError("QEMU did not stop after observed login")
                    replay = qmp.command("query-replay")
                    count = replay.get("icount") if isinstance(replay, dict) else None
                    if isinstance(count, bool) or not isinstance(count, int) or count <= bpf_exit_icount:
                        raise ProtocolError("QEMU login icount is missing or not after BPF")
                    return {
                        "login_uart_observed": True,
                        "qemu_guest_instructions_after_login_uart": count,
                        "sample_kind": "QMP_STOP_AFTER_UART_OBSERVATION_NONATOMIC",
                        "sampling_host_delay_unbounded_guest_instructions": True,
                        "qemu_host_seconds_after_bpf_probe": round(time.monotonic() - started, 3),
                    }
            else:
                time.sleep(poll_seconds)
    raise ProtocolError("QEMU did not emit qualified root/init/login sequence before timeout")


def run(args, rsp_class=Rsp, qmp_class=Qmp):
    if not re.fullmatch(r"[0-9a-f]{64}", args.expected_bpf_sha):
        raise ProtocolError("--expected-bpf-sha requires exact 64-char SHA256")
    if not 1 <= args.wall_seconds <= 600:
        raise ProtocolError("--wall-seconds must be 1..600")
    if not 0 <= args.icount_shift <= 16:
        raise ProtocolError("--icount-shift must be 0..16")
    system_map = Path(args.system_map)
    kernel = Path(args.kernel)
    if not all(path.is_file() and path.stat().st_size > 0 for path in (system_map, kernel)):
        raise ProtocolError("exact QEMU-virt System.map and kernel Image required")
    symbols = {name: system_map_symbol(system_map, name)
               for name in ("bpf_check", "do_check_common")}
    if symbols["bpf_check"] == symbols["do_check_common"]:
        raise ProtocolError("BPF function symbol collision")
    started = time.monotonic()
    rsp = rsp_class(args.gdb_socket, args.wall_seconds)
    try:
        qmp = qmp_class(args.qmp_socket, args.wall_seconds)
        try:
            evidence = probe(rsp, qmp, symbols, args.expected_bpf_sha)
            if args.serial_log:
                bpf_start = evidence["phase_samples"][0]["qemu_guest_instructions"]
                bpf_end = evidence["phase_samples"][-1]["qemu_guest_instructions"]
                login = observe_login_after_detach(rsp, qmp, args.serial_log,
                                                   args.wall_seconds, bpf_end)
                evidence["login_icount_sample"] = login
                denominator = login["qemu_guest_instructions_after_login_uart"]
                evidence["qemu_bpf_instructions_fraction_at_post_uart_sample"] = (
                    (bpf_end - bpf_start) / denominator)
                evidence["qemu_bpf_exit_fraction_at_post_uart_sample"] = (
                    bpf_end / denominator)
                evidence["notes"].append(
                    "Whole-boot ratios use a non-atomic QMP sample AFTER UART login, "
                    "so they are approximate lower bounds, not exact-at-login fractions.")
        finally:
            qmp.close()
    finally:
        rsp.close()
    evidence["inputs"] = {"kernel_image": sha256(kernel),
                          "system_map": sha256(system_map)}
    evidence["host_probe_wall_seconds"] = round(time.monotonic() - started, 3)
    destination = Path(args.output)
    if destination.exists():
        raise ProtocolError("refusing to overwrite prior evidence")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n")
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qmp-socket", required=True)
    parser.add_argument("--gdb-socket", required=True)
    parser.add_argument("--system-map", required=True)
    parser.add_argument("--kernel", required=True)
    parser.add_argument("--expected-bpf-sha", required=True)
    parser.add_argument("--icount-shift", type=int, default=6)
    parser.add_argument("--wall-seconds", type=int, default=300)
    parser.add_argument("--serial-log", help="optional same-QEMU-process UART output; sample I_login")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    def expired(_signum, _frame):
        raise ProtocolError("QEMU BPF probe exceeded wall-time budget")

    signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, args.wall_seconds)
    try:
        result = run(args)
    except (OSError, ValueError, ProtocolError) as error:
        print(f"PRODUCT_SD1_QEMU_BPF_REJECTED: {error}", file=sys.stderr)
        return 3
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
    print("PRODUCT_SD1_QEMU_BPF_MEASURED_ONLY_NOT_PRODUCT_RTL: " +
          json.dumps({"program": result["program"]["name"],
                      "sha256": result["program"]["bytecode_sha256"],
                      "verifier_insn_processed": (
                          result["verifier_counters_at_common_return"]["insn_processed"]),
                      "qemu_guest_instructions": result["verifier_qemu_guest_instructions"]},
                     sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
