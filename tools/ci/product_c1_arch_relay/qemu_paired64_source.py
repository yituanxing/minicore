#!/usr/bin/env python3
"""Verify one sealed QEMU 64-step + RAM pair and generate an RTL-side oracle.

This proves only the integer PC/opcode/XPR and end-of-window RAM subset.
QEMU mcycle/minstret, full CSR/device state, FPR and interrupt timing are not
equivalent to a Product G5 RTL checkpoint. NEVER call this full DiffTest.
"""

import argparse
import hashlib
import json
from pathlib import Path

from qemu_ram_first_retire_source import (INSTRUCTION_PA, PC, PMP_ADDRS,
                                          PTE_ORACLE, ROOT, RAM_SIZE,
                                          SOURCE_REGS)

RAM_SHA = "c7912452a249352d16fdda53a30450f3a457773e9805182011bd51b255b20983"
MANIFEST_SHA = "15f00a71957aeeb329aaa40ffe061529137f00c66cb68bb8d301e13d4d3cdc24"
TRACE_SHA = "f50ae9cf956d40c7dbd9d4cbef7c4b6bd24bae10ffe1c54707b58b6c26265cce"
INSTRUCTION_SHA = "5e22ba2ad0a80e658865160b7aca02ab1758925593c044eaab85d9f4c77ebc9e"
COUNT = 64


def require(ok, why):
    if not ok:
        raise ValueError(why)


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for data in iter(lambda: source.read(1 << 20), b""):
            digest.update(data)
    return digest.hexdigest()


def hx(number):
    return f"0x{number & ((1 << 64) - 1):016x}ULL"


def validate(folder):
    ram_path = folder / "export/ram-0x80000000-0x10000000.bin"
    manifest_path = folder / "export/manifest.json"
    trace_path = folder / "qemu-single-step-oracle.json"
    walk_path = folder / "source-insn.json"
    require(all(p.is_file() for p in (ram_path, manifest_path, trace_path, walk_path)),
            "missing immutable paired QEMU artifact member")
    require(sha(manifest_path) == MANIFEST_SHA and sha(trace_path) == TRACE_SHA and
            sha(walk_path) == INSTRUCTION_SHA and sha(ram_path) == RAM_SHA,
            "QEMU sealed source digest mismatch")
    require(ram_path.stat().st_size == RAM_SIZE, "RAM extent drift")
    manifest = json.loads(manifest_path.read_text())
    trace = json.loads(trace_path.read_text())
    walk = json.loads(walk_path.read_text())
    mem = manifest.get("memory", {})
    require(mem.get("sha256") == RAM_SHA and mem.get("start") == hex(ROOT) and
            mem.get("bytes") == RAM_SIZE and manifest.get("qemu_cpu_count") == 1 and
            manifest.get("arch_restore_ready") is False and
            manifest.get("rtl_cpu_injected") is False,
            "source falsely claims full RTL restoration / changed RAM")
    regs = manifest.get("qemu_gdb_registers", {})
    for key, value in SOURCE_REGS.items():
        require(regs.get(key) == value, f"QEMU architected {key} changed")
    for i, expected in enumerate(PMP_ADDRS):
        require(int(regs.get(f"pmpaddr{i}", "-1"), 0) == expected,
                f"QEMU PMP address {i} differs")
    gprs = []
    for i in range(32):
        value = int(regs.get(f"x{i}", "-1"), 0)
        require(0 <= value < (1 << 64), f"QEMU x{i} missing or invalid")
        gprs.append(value)
    require(gprs[0] == 0 and gprs[2] == int(SOURCE_REGS["x2"], 16),
            "source GPR identity mismatch")
    require(walk.get("source_pc") == hex(PC) and
            walk.get("translated_physical_pc") == hex(INSTRUCTION_PA) and
            walk.get("instruction_bytes_le") == "7971", "first QEMU Sv39 walk drift")
    with ram_path.open("rb") as ram:
        for physical, pte in PTE_ORACLE:
            ram.seek(physical - ROOT)
            require(int.from_bytes(ram.read(8), "little") == pte,
                    f"QEMU real PTE changed at {physical:#x}")
        ram.seek(INSTRUCTION_PA - ROOT)
        require(ram.read(2) == bytes.fromhex("7971"), "first Linux RVC differs")
    ident = trace.get("identity", {})
    require(ident.get("source_ram_sha256") == RAM_SHA and
            ident.get("source_manifest_sha256") == MANIFEST_SHA and
            ident.get("source_first_instruction_walk_sha256") == INSTRUCTION_SHA and
            ident.get("cpu") == "rv64,sv48=off,sv57=off,h=off" and
            ident.get("harts") == 1 and
            trace.get("qualification") ==
                "QEMU_SINGLESTEP_ORACLE_PAIRED_WITH_SAME_CAPTURED_RAM_NOT_RTL_DIFFTEST" and
            trace.get("retired_instruction_count") == COUNT and
            trace.get("real_product_rtl_retire_count") == 0 and
            trace.get("full_device_and_memory_diff_test") is False and
            trace.get("debian_service_login_pass") is False,
            "trace not paired to exact RAM or misclaims RTL")
    initial = trace.get("initial_state", {})
    for key in ("pc", "priv", "satp", "mstatus") + tuple(f"x{i}" for i in range(32)):
        require(int(initial.get(key, "-1"), 0) == int(regs[key], 0),
                f"trace initial {key} not same QEMU snapshot")
    entries = trace.get("entries", [])
    require(len(entries) == COUNT, "QEMU trace not exactly 64 retirements")
    previous = initial
    expected_steps = []
    previous_csr = None
    for index, entry in enumerate(entries):
        inst = entry.get("instruction", {})
        count_bytes = inst.get("instruction_length")
        require(entry.get("step") == index + 1 and count_bytes in (2, 4) and
                int(inst.get("opcode", "-1"), 0) ==
                    int.from_bytes(bytes.fromhex(inst.get("bytes_le", "")), "little") and
                len(inst["bytes_le"]) == count_bytes * 2,
                f"trace instruction {index} malformed")
        require(entry.get("pc_before") == previous["pc"] and
                entry.get("priv_before") == "0x1" and entry.get("priv_after") == "0x1" and
                entry.get("csr_before") == entry.get("csr_after"),
                f"step {index} privilege/CSR discontinuity or PC chain broken")
        csr = entry["csr_before"]
        require(isinstance(csr, dict) and
                all(csr.get(key) == regs[key] for key in ("satp", "mstatus")) and
                (previous_csr is None or csr == previous_csr),
                f"step {index} CSR differs from captured state or prior step")
        previous_csr = csr
        # The current partial RTL seed does not restore FPR/vector/AMO
        # reservations or arbitrary CSR state. A future source trace must
        # fail closed if it exercises any of them in this bounded window.
        if count_bytes == 4:
            opcode_class = int(inst["opcode"], 0) & 0x7f
            require(opcode_class not in (0x07, 0x27, 0x2f, 0x53, 0x57, 0x73),
                    f"step {index} has unqualified FP/vector/AMO/system instruction")
        else:
            raw = int(inst["opcode"], 0)
            quadrant, funct3 = raw & 3, (raw >> 13) & 7
            require(not (quadrant in (0, 2) and funct3 in (1, 5)),
                    f"step {index} has unqualified compressed FPR instruction")
        reg_after = entry.get("gpr_after", [])
        require(len(reg_after) == 32 and all(isinstance(x, str) and
                0 <= int(x, 0) < (1 << 64) for x in reg_after),
                f"step {index} incomplete GPR state")
        changes = {f"x{i}": reg_after[i] for i in range(1, 32)
                   if int(reg_after[i], 0) != int(previous[f"x{i}"], 0)}
        require(changes == entry.get("gpr_changes", {}),
                f"step {index} QEMU register delta mismatch")
        require(reg_after[0] == "0x0", f"step {index} x0 drift")
        expected_steps.append({"pc": int(entry["pc_before"], 0),
                               "after_pc": int(entry["pc_after"], 0),
                               "raw": int(inst["opcode"], 0), "length": count_bytes,
                               "gprs": [int(x, 0) for x in reg_after]})
        previous = {f"x{i}": reg_after[i] for i in range(32)} | {
            "pc": entry["pc_after"]}
    require(expected_steps[0]["pc"] == PC and expected_steps[0]["raw"] == 0x7179 and
            expected_steps[0]["gprs"][2] == 0xFFFFFFC80000BE90,
            "first Linux RVC oracle changed")
    delta = trace.get("end_memory_delta", {})
    require(delta.get("source_ram_sha256") == RAM_SHA and
            delta.get("memory_delta_is_per_instruction") is False and
            delta.get("page_size") == 4096 and
            0 <= delta.get("modified_bytes", -1) <= 512 * 1024,
            "end-memory patch metadata drift")
    patched = {}
    touched_pages = set()
    for page in delta.get("modified_pages", []):
        base = int(page["physical_page"], 0)
        require(base % 4096 == 0 and ROOT <= base < ROOT + RAM_SIZE and
                base not in touched_pages, "bad or duplicate DDR page in delta")
        touched_pages.add(base)
        for run in page.get("ranges", []):
            offset = run["offset"]
            before = bytes.fromhex(run["old_hex"])
            after = bytes.fromhex(run["new_hex"])
            require(0 <= offset < 4096 and len(before) == len(after) and
                    len(before) > 0 and offset + len(before) <= 4096,
                    "invalid QEMU DDR patch range")
            with ram_path.open("rb") as ram:
                ram.seek(base + offset - ROOT)
                require(ram.read(len(before)) == before,
                        "DDR patch old bytes are not in source RAM")
            for pos, b in enumerate(after):
                pa = base + offset + pos
                require(pa not in patched, "overlapping QEMU RAM delta ranges")
                patched[pa] = b
    require(len(patched) == delta.get("modified_bytes"),
            "DDR patch missing bytes")
    require(len(touched_pages) <= 128 and len(patched) <= 512 * 1024,
            "unbounded DDR delta")
    # The QEMU trace records an independently hashed final full DDR image.
    # Verify the aggregate patch is complete before asking RTL to compare it.
    with ram_path.open("rb") as source_ram:
        post_ram = bytearray(source_ram.read())
    require(len(post_ram) == RAM_SIZE, "QEMU snapshot was truncated during patch")
    for pa, value in patched.items():
        post_ram[pa - ROOT] = value
    require(hashlib.sha256(post_ram).hexdigest() == delta.get("post_step_ram_sha256"),
            "QEMU delta cannot reconstruct final 256MiB DDR SHA")
    final = trace.get("final_state", {})
    require(final.get("pc") == previous["pc"] and
            all(final.get(f"x{i}") == previous[f"x{i}"] for i in range(32)) and
            final.get("priv") == "0x1" and
            final.get("satp") == regs["satp"] and
            final.get("mstatus") == regs["mstatus"] and
            all(final.get(k) == v for k, v in previous_csr.items()),
            "QEMU final architectural state does not match 64th step")
    return {"source_ram_sha": RAM_SHA, "trace_sha": TRACE_SHA,
            "gprs": gprs, "steps": expected_steps, "delta": patched,
            "source_mstatus": regs["mstatus"], "source_satp": regs["satp"],
            "first_pc": hex(PC), "number_of_steps": COUNT,
            "post_step_ram_sha": delta["post_step_ram_sha256"]}


def header(path, evidence):
    lines = ["// Exact independent QEMU 64-step subset oracle, SHA-paired to RAM.",
             "#pragma once", "#include <array>", "#include <cstdint>",
             "struct PairedQemuTraceStep {",
             "  std::uint64_t pc, afterPc; std::uint32_t raw; std::uint8_t bytes;",
             "  std::array<std::uint64_t,32> gprs;",
             "};",
             "struct PairedQemuChangedByte { std::uint64_t pa; std::uint8_t value; };",
             "inline constexpr std::array<std::uint64_t,32> kPairedInitialGprs{{"]
    lines += [f"  {hx(x)}{',' if i != 31 else ''} // x{i}"
              for i, x in enumerate(evidence["gprs"])]
    lines += ["}};", f"inline constexpr std::array<PairedQemuTraceStep,{COUNT}> kPairedSteps{{{{"]
    for step in evidence["steps"]:
        gprs = ",".join(hx(x) for x in step["gprs"])
        lines.append("  {" + ", ".join((hx(step["pc"]), hx(step["after_pc"]),
                                       f"0x{step['raw']:08x}U",
                                       str(step["length"]), "{{" + gprs + "}}")) + "},")
    lines += ["}};", f"inline constexpr std::array<PairedQemuChangedByte,{len(evidence['delta'])}> kPairedFinalDdrDelta{{{{"]
    for pa, value in sorted(evidence["delta"].items()):
        lines.append("  {" + hx(pa) + f", 0x{value:02x}U" + "},")
    lines += ["}};"]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n")


def gpr_header(path):
    """Provide a compatible GPR header to the existing first-retire host.

    The included host's old run() is still parsed, even though not called.
    Do not reuse its OLD source-generated table against this new source RAM.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("#pragma once\n#include \"paired_qemu_trace.h\"\n"
                    "inline constexpr auto kCapturedGprs = kPairedInitialGprs;\n")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--header", type=Path, required=True)
    p.add_argument("--gpr-header", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    a = p.parse_args()
    reviewed = validate(a.source)
    header(a.header, reviewed)
    gpr_header(a.gpr_header)
    a.report.parent.mkdir(parents=True, exist_ok=True)
    a.report.write_text(json.dumps({k: v for k, v in reviewed.items()
                                    if k not in ("gprs", "steps", "delta")},
                                   indent=2, sort_keys=True) + "\n")
    print("PRODUCT_G5_QEMU_PAIRED64_SOURCE_VERIFIED_NOT_RTL_PASS")


if __name__ == "__main__":
    raise SystemExit(main())
