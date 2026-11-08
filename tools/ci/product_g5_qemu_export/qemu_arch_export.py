#!/usr/bin/env python3
"""Diagnostic QEMU RISC-V architectural-state export for Product G5.

This is deliberately NOT a Verilator restore file.  It can optionally drive a
QEMU gdbstub from -S to a precise kernel breakpoint, then freeze the guest and
record GPR/FPR/CSR state, 256 MiB physical RAM and side-effect-free CLINT words.
Missing registers, interrupt-controller/serial state and RTL import support
are explicitly reported as blockers, never silently fabricated.

QEMU should boot the exact Product fw_payload/DTB with one hart, e.g.:
    -bios fw_payload.bin -dtb product.dtb \
    -device loader,file=product.dtb,addr=0x87f00000,force-raw=on \
    -S -chardev socket,path=/path/gdb.sock,server=on,wait=off,id=gdb0 \
    -gdb chardev:gdb0 \
    -qmp unix:/path/qmp.sock,server=on,wait=off
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import signal
import socket
import sys
import time
import xml.etree.ElementTree as ET


RAM_BASE = 0x80000000
RAM_SIZE = 256 * 1024 * 1024
MTIME_ADDR = 0x0200BFF8
MTIMECMP_ADDR = 0x02004000
REQUIRED_CSR = (
    "mstatus", "medeleg", "mideleg", "mie", "mip", "mtvec", "mscratch",
    "mepc", "mcause", "mtval", "mcounteren", "mcountinhibit", "menvcfg",
    "mcycle", "minstret", "stvec", "sscratch", "sepc", "scause",
    "stval", "satp", "scounteren", "senvcfg", "fcsr", "fflags", "frm",
)
DEVICE_BLOCKERS = (
    "plic.priority_enable_threshold_pending_and_claim_state_unrestored",
    "uart.configuration_and_fifo_state_unrestored",
    "qemu_lr_sc_reservation_unavailable",
    "qemu_to_product_clint_timebase_and_pending_interrupt_equivalence_unverified",
    "rtl_warm_restore_port_not_implemented",
    "rtl_cache_tlb_rob_iq_lsq_reset_at_architectural_boundary_unqualified",
)
X_ABI_NAMES = (
    "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1",
    "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "s2", "s3",
    "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4",
    "t5", "t6",
)
F_ABI_NAMES = (
    "ft0", "ft1", "ft2", "ft3", "ft4", "ft5", "ft6", "ft7", "fs0", "fs1",
    "fa0", "fa1", "fa2", "fa3", "fa4", "fa5", "fa6", "fa7", "fs2", "fs3",
    "fs4", "fs5", "fs6", "fs7", "fs8", "fs9", "fs10", "fs11", "ft8", "ft9",
    "ft10", "ft11",
)


class ProtocolError(RuntimeError):
    pass


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for data in iter(lambda: stream.read(1 << 20), b""):
            h.update(data)
    return h.hexdigest()


def decode_riscv_le(raw):
    """RSP p returns target-endian bytes, possibly x for unavailable bits."""
    if not raw or raw[0] in "E" or not re.fullmatch(r"[0-9a-fA-F]+", raw):
        return None
    if len(raw) & 1:
        return None
    return int.from_bytes(bytes.fromhex(raw), "little")


XI_NAMESPACE = "http://www.w3.org/2001/XInclude"
SAFE_XML_ANNEX = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.xml")


def registers_from_annexes(read_annex):
    """Follow QEMU XML feature includes, preserving explicit GDB regnums.

    A QEMU gdbstub may use xi:include in target.xml without declaring xi.
    Accommodate this single known stub defect, but reject external annexes,
    cyclic/nested runaway includes, malformed XML, and duplicate regnums.
    """
    seen = set()
    active = set()
    assigned_numbers = {}
    out = {}
    next_num = 0

    def visit(annex, depth=0):
        nonlocal next_num
        if not SAFE_XML_ANNEX.fullmatch(annex) or ".." in annex:
            raise ProtocolError(f"unsafe GDB XML annex: {annex!r}")
        if annex in active:
            raise ProtocolError(f"cyclic GDB XML include: {annex}")
        if annex in seen:
            return
        if depth > 16 or len(seen) >= 64:
            raise ProtocolError("GDB XML include depth/count exceeded")
        active.add(annex)
        try:
            xml = read_annex(annex)
            if not isinstance(xml, str) or len(xml) > 2_000_000:
                raise ProtocolError(f"invalid GDB XML annex size: {annex}")
            if annex == "target.xml" and "xi:include" in xml:
                # Historical Product QEMU gdbstub omitted xmlns:xi from
                # the opening <target> tag; ET.fromstring otherwise raises
                # 'unbound prefix'. Never rewrite child feature XML.
                opening = re.search(r"<target(?:\s[^<>]*)?>", xml)
                if opening is None:
                    raise ProtocolError("target.xml lacks a target root")
                root_tag = opening.group()
                if "xmlns:xi" not in root_tag:
                    fixed = root_tag[:-1] + f' xmlns:xi="{XI_NAMESPACE}">'
                    xml = xml[:opening.start()] + fixed + xml[opening.end():]
            try:
                root = ET.fromstring(xml)
            except ET.ParseError as exc:
                raise ProtocolError(f"invalid GDB XML annex {annex}: {exc}") from exc
            for node in root.iter():
                kind = node.tag.rsplit("}", 1)[-1]
                if kind == "include":
                    if node.tag != f"{{{XI_NAMESPACE}}}include":
                        raise ProtocolError(f"unsupported GDB XML include namespace in {annex}")
                    href = node.attrib.get("href", "")
                    if not SAFE_XML_ANNEX.fullmatch(href) or ".." in href:
                        raise ProtocolError(f"unsafe GDB XML include href: {href!r}")
                    visit(href, depth + 1)
                elif kind == "reg":
                    if "regnum" in node.attrib:
                        try:
                            next_num = int(node.attrib["regnum"], 0)
                        except ValueError as exc:
                            raise ProtocolError(f"bad GDB register number in {annex}") from exc
                    if next_num < 0 or next_num > 0xFFFF:
                        raise ProtocolError(f"GDB register number out of range: {next_num}")
                    name = node.attrib.get("name")
                    if next_num in assigned_numbers:
                        raise ProtocolError(
                            f"duplicate GDB register number {next_num}: "
                            f"{assigned_numbers[next_num]} and {name}")
                    if name in out:
                        raise ProtocolError(f"duplicate GDB register name: {name}")
                    if name:
                        assigned_numbers[next_num] = name
                        out[name] = next_num
                        if name in X_ABI_NAMES:
                            out[f"x{X_ABI_NAMES.index(name)}"] = next_num
                        if name in F_ABI_NAMES:
                            out[f"f{F_ABI_NAMES.index(name)}"] = next_num
                    next_num += 1
            seen.add(annex)
        finally:
            active.remove(annex)

    visit("target.xml")
    return out


class Rsp:
    def __init__(self, path, timeout):
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.settimeout(timeout)
        self.sock.connect(path)

    def close(self):
        self.sock.close()

    def _byte(self):
        data = self.sock.recv(1)
        if not data:
            raise ProtocolError("QEMU GDB socket closed")
        return data[0]

    def _response(self):
        while True:
            ch = self._byte()
            if ch == ord("$"):
                break
        payload = bytearray()
        encoded_payload = bytearray()
        while True:
            ch = self._byte()
            if ch == ord("#"):
                break
            encoded_payload.append(ch)
            if ch == ord("}"):
                ch = self._byte() ^ 0x20
                encoded_payload.append(ch ^ 0x20)
            payload.append(ch)
        checksum = bytes((self._byte(), self._byte()))
        try:
            expected = int(checksum, 16)
        except ValueError as exc:
            raise ProtocolError("invalid RSP checksum") from exc
        if (sum(encoded_payload) & 0xFF) != expected:
            raise ProtocolError("QEMU GDB RSP packet checksum mismatch")
        self.sock.sendall(b"+")
        return payload.decode("latin1")

    def request(self, payload):
        encoded = payload.encode("ascii")
        check = sum(encoded) & 0xFF
        self.sock.sendall(b"$" + encoded + b"#" + f"{check:02x}".encode())
        return self._response()

    def xml(self, annex):
        offset = 0
        blocks = []
        while True:
            answer = self.request(f"qXfer:features:read:{annex}:{offset:x},800")
            if not answer or answer[0] not in "lm":
                raise ProtocolError(f"QEMU lacks gdb XML annex {annex}: {answer[:100]}")
            block = answer[1:]
            blocks.append(block)
            offset += len(block.encode("latin1"))
            if answer[0] == "l":
                return "".join(blocks)
            if offset > 2_000_000:
                raise ProtocolError("GDB XML features exceed bounds")

    def reg(self, number):
        return decode_riscv_le(self.request(f"p{number:x}"))

    def read_phys64(self, address):
        response = self.request(f"m{address:x},8")
        if not re.fullmatch(r"[0-9a-fA-F]{16}", response):
            return None
        return int.from_bytes(bytes.fromhex(response), "little")


class Qmp:
    def __init__(self, path, timeout):
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.settimeout(timeout)
        self.sock.connect(path)
        self.stream = self.sock.makefile("rwb", buffering=0)
        hello = self._read()
        if "QMP" not in hello:
            raise ProtocolError("missing QMP greeting")
        self.command("qmp_capabilities")

    def close(self):
        self.stream.close()
        self.sock.close()

    def _read(self):
        while True:
            line = self.stream.readline()
            if not line:
                raise ProtocolError("QMP socket closed")
            obj = json.loads(line)
            if "event" not in obj:
                return obj

    def command(self, op, arguments=None):
        obj = {"execute": op}
        if arguments is not None:
            obj["arguments"] = arguments
        self.stream.write((json.dumps(obj) + "\n").encode())
        result = self._read()
        if "error" in result:
            raise ProtocolError(f"QMP {op} failed: {result['error']}")
        return result.get("return")


def system_map_symbol(path, name):
    matches = []
    for line in Path(path).read_text().splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[2] == name:
            matches.append(int(parts[0], 16))
    if len(matches) != 1:
        raise ProtocolError(f"expected unique System.map symbol {name}, got {len(matches)}")
    return matches[0]


def collect(rsp, qmp, out, pc, inputs, drive, wall):
    if drive:
        rsp.sock.settimeout(wall)
        stopped = rsp.request("?")
        if not (stopped.startswith("T") or stopped.startswith("S")):
            raise ProtocolError(f"QEMU is not initially stopped: {stopped[:60]}")
        # Hardware breakpoint avoids mutating guest text before dumping RAM.
        # QEMU TCG advertises hardware breakpoints for system emulation.
        result = rsp.request(f"Z1,{pc:x},4")
        if result != "OK":
            raise ProtocolError(f"QEMU gdbstub refused breakpoint: {result}")
        stop = rsp.request("c")
        if not (stop.startswith("T05") or stop.startswith("S05")):
            raise ProtocolError(f"QEMU did not stop at breakpoint: {stop[:80]}")

    status = qmp.command("query-status")
    if status.get("running") or status.get("status") not in {"paused", "debug", "prelaunch"}:
        raise ProtocolError(f"guest not paused for atomic export: {status}")
    cpus = qmp.command("query-cpus-fast")
    if not isinstance(cpus, list) or len(cpus) != 1 or cpus[0].get("cpu-index") != 0:
        raise ProtocolError(f"checkpoint requires exact one-hart CPU 0: {cpus}")
    identities = {name: {"sha256": sha256(path), "path": str(path)}
                  for name, path in inputs.items()}
    features = registers_from_annexes(rsp.xml)
    if not features:
        raise ProtocolError("QEMU did not advertise any RISC-V registers")
    names = {"pc", "priv"}
    names.update(f"x{idx}" for idx in range(32))
    names.update(f"f{idx}" for idx in range(32))
    names.update(REQUIRED_CSR)
    names.update(f"pmpaddr{idx}" for idx in range(16))
    names.update(f"pmpcfg{idx}" for idx in range(4))
    values = {}
    missing = []
    for name in sorted(names):
        val = rsp.reg(features[name]) if name in features else None
        if val is None:
            missing.append(name)
        else:
            values[name] = f"0x{val:x}"
    if values.get("pc") != f"0x{pc:x}":
        raise ProtocolError(f"PC is {values.get('pc')}, not checkpoint 0x{pc:x}")

    # Monitor switches gdbstub memory operations from translated VA to raw PA.
    # The QEMU-specific extension may not be advertised; report that clearly.
    mmio = {}
    original_mode = rsp.request("qqemu.PhyMemMode")
    if original_mode in {"0", "1"}:
        new_mode = rsp.request("Qqemu.PhyMemMode:1")
        if new_mode == "OK":
            for name, addr in (("mtime", MTIME_ADDR), ("mtimecmp", MTIMECMP_ADDR)):
                value = rsp.read_phys64(addr)
                mmio[name] = None if value is None else f"0x{value:x}"
            if original_mode == "0":
                rsp.request("Qqemu.PhyMemMode:0")
        else:
            mmio = {"mtime": None, "mtimecmp": None}
    else:
        mmio = {"mtime": None, "mtimecmp": None}

    ram = out / "ram-0x80000000-0x10000000.bin"
    # Use QMP's native typed physical-memory dump command, not an HMP text
    # command whose nonempty error message would otherwise be ignored by QMP.
    # This ensures read/write/permission failures fail closed with QMP detail.
    qmp.command("pmemsave", {
        "val": RAM_BASE, "size": RAM_SIZE, "filename": str(ram),
    })
    if not ram.is_file():
        raise ProtocolError(f"QMP pmemsave reported success without output: {ram}")
    actual_bytes = ram.stat().st_size
    if actual_bytes != RAM_SIZE:
        raise ProtocolError(
            f"QMP pmemsave size mismatch: expected {RAM_SIZE}, "
            f"actual {actual_bytes}, path {ram}")

    result = {
        "qualification": "QEMU_ARCH_EXPORT_DIAGNOSTIC_ONLY_NOT_RTL_HANDOFF_PASS",
        "arch_restore_ready": False,
        "rtl_cpu_injected": False,
        "memory": {"start": f"0x{RAM_BASE:x}", "bytes": RAM_SIZE,
                   "sha256": sha256(ram), "path": str(ram)},
        "breakpoint_pc": f"0x{pc:x}",
        "inputs": identities,
        "qemu_cpu_count": len(cpus),
        "qemu_gdb_registers": values,
        "missing_gdb_registers": missing,
        "read_only_clint": mmio,
        "unexported_device_and_rtl_state": list(DEVICE_BLOCKERS),
        "notes": [
            "No QEMU savevm stream can be loaded by Verilator.",
            "Even full register coverage would not restore the UART, PLIC or LR/SC state.",
            "This snapshot was captured at a host-observed architectural instruction boundary.",
            "Warm restore must validate ISA, ABI, timer, trap and memory semantics separately.",
        ],
    }
    manifest = out / "manifest.json"
    manifest.write_text(json.dumps(result, sort_keys=True, indent=2) + "\n")
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--qmp-socket", required=True)
    p.add_argument("--gdb-socket", required=True)
    p.add_argument("--out-dir", required=True)
    p.add_argument("--system-map", required=True)
    p.add_argument("--break-symbol", default="run_init_process")
    p.add_argument("--drive-to-breakpoint", action="store_true",
                   help="requires QEMU launched with -S; set and run to kernel breakpoint")
    p.add_argument("--wall-seconds", type=int, default=180)
    p.add_argument("--firmware", required=True)
    p.add_argument("--dtb", required=True)
    p.add_argument("--linux-image", required=True)
    args = p.parse_args()
    if not 1 <= args.wall_seconds <= 600:
        p.error("--wall-seconds must be in 1..600")

    def expired(_signal, _frame):
        raise ProtocolError(f"checkpoint exceeded {args.wall_seconds}s wall budget")

    signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, args.wall_seconds)
    out = Path(args.out_dir).resolve()
    if " " in str(out):
        p.error("output directory cannot contain whitespace (HMP pmemsave restriction)")
    if out.exists() and any(out.iterdir()):
        p.error("output directory must be empty: refusing to replace prior evidence")
    out.mkdir(parents=True, exist_ok=True)
    inputs = {"firmware": Path(args.firmware).resolve(),
              "dtb": Path(args.dtb).resolve(),
              "linux_image": Path(args.linux_image).resolve(),
              "system_map": Path(args.system_map).resolve()}
    if not all(x.is_file() and x.stat().st_size for x in inputs.values()):
        p.error("all four exact Product inputs must be existing non-empty files")
    pc = system_map_symbol(args.system_map, args.break_symbol)
    rsp = Rsp(args.gdb_socket, args.wall_seconds)
    try:
        qmp = Qmp(args.qmp_socket, args.wall_seconds)
        try:
            result = collect(rsp, qmp, out, pc, inputs, args.drive_to_breakpoint,
                             args.wall_seconds)
        finally:
            qmp.close()
    finally:
        rsp.close()
    signal.setitimer(signal.ITIMER_REAL, 0)
    print("QEMU_PRODUCT_ARCH_EXPORT_CAPTURED_DIAGNOSTIC_ONLY"
          f" pc={result['breakpoint_pc']} ram_sha256={result['memory']['sha256']}"
          f" missing_regs={len(result['missing_gdb_registers'])} rtl_handoff_pass=0")


if __name__ == "__main__":
    try:
        main()
    except (ProtocolError, OSError, ValueError) as exc:
        print(f"QEMU_PRODUCT_ARCH_EXPORT_FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
