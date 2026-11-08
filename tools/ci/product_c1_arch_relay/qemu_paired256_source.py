#!/usr/bin/env python3
"""Fail-closed *same-capture* QEMU 256-retirement source gate for G5.

This extends the independently proven 64-step verifier without modifying its
pinned oracle.  The four SHA values are learned only from *this* QEMU capture,
after the CI source stage has authenticated the original D2 firmware bundle.
The verifier then enforces the cross-file SHA lineage, complete 256 MiB DDR
post-state, all 32 XPRs after every instruction, and the integer-only scope.

It is source preparation, not a Product RTL pass or a full DiffTest.
"""

import argparse
import json
from pathlib import Path
import sys

import qemu_paired64_source as strict

COUNT = 256


def validate(source: Path) -> dict:
    paths = {
        "ram": source / "export/ram-0x80000000-0x10000000.bin",
        "manifest": source / "export/manifest.json",
        "trace": source / "qemu-single-step-oracle.json",
        "walk": source / "source-insn.json",
    }
    if not all(path.is_file() and not path.is_symlink()
               for path in paths.values()):
        raise ValueError("paired256 source members missing or symbolic")
    if paths["ram"].stat().st_size != strict.RAM_SIZE:
        raise ValueError("paired256 QEMU DDR not exactly 256 MiB")

    # Deliberately mutate only process-local verifier constants.  The old
    # paired64 source file and its 64-instruction SHA pins remain immutable.
    strict.COUNT = COUNT
    strict.RAM_SHA = strict.sha(paths["ram"])
    strict.MANIFEST_SHA = strict.sha(paths["manifest"])
    strict.TRACE_SHA = strict.sha(paths["trace"])
    strict.INSTRUCTION_SHA = strict.sha(paths["walk"])

    trace = json.loads(paths["trace"].read_text())
    if trace.get("retired_instruction_count") != COUNT:
        raise ValueError("QEMU trace does not contain exact 256 retirements")
    evidence = strict.validate(source)
    if len(evidence["steps"]) != COUNT:
        raise ValueError("strict QEMU architectural oracle is not 256 entries")
    return evidence


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--source", type=Path, required=True)
    ap.add_argument("--header", type=Path, required=True)
    ap.add_argument("--gpr-header", type=Path, required=True)
    ap.add_argument("--report", type=Path, required=True)
    args = ap.parse_args()
    evidence = validate(args.source)
    strict.header(args.header, evidence)
    strict.gpr_header(args.gpr_header)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    report = {k: v for k, v in evidence.items()
              if k not in ("gprs", "steps", "delta")}
    report["qualification"] = "QEMU_PAIRED256_INTEGER_ONLY_SOURCE_NOT_RTL_DIFFTEST"
    report["source_same_capture_sha_chain_verified"] = True
    report["real_product_rtl_retirements"] = 0
    report["debian_service_login_pass"] = False
    args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print("PRODUCT_G5_QEMU_PAIRED256_SOURCE_VERIFIED_NOT_RTL_PASS"
          f" count={COUNT} ram_sha256={evidence['source_ram_sha']}"
          f" final_ram_sha256={evidence['post_step_ram_sha']}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print("PRODUCT_G5_QEMU_PAIRED256_SOURCE_REJECTED: " + str(exc),
              file=sys.stderr)
        raise SystemExit(16)
