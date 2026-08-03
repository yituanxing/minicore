#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
LOCK_FILE = ROOT / "software" / "freertos" / "FreeRTOS-Kernel.lock"
PORT_FILES = {
    "port.c": Path("portable/GCC/RISC-V/port.c"),
    "portASM.S": Path("portable/GCC/RISC-V/portASM.S"),
    "portmacro.h": Path("portable/GCC/RISC-V/portmacro.h"),
}

REQUIRED_FRAGMENTS = {
    "port.c": (
        "configMTIME_BASE_ADDRESS",
        "configMTIMECMP_BASE_ADDRESS",
        'csrr %0, mhartid',
        'csrs mie, %0',
        "vPortSetupTimerInterrupt",
        "xPortStartScheduler",
        "xPortStartFirstTask",
    ),
    "portASM.S": (
        "portUPDATE_MTIMER_COMPARE_REGISTER",
        "xPortStartFirstTask",
        "freertos_risc_v_trap_handler",
        "freertos_risc_v_exception_handler",
        "freertos_risc_v_interrupt_handler",
        "freertos_risc_v_mtimer_interrupt_handler",
        "mstatus",
        "mepc",
        "mcause",
        "mret",
    ),
    "portmacro.h": (
        "portYIELD()",
        '"ecall"',
        '"csrc mstatus, 8"',
        '"csrs mstatus, 8"',
        "configMTIME_BASE_ADDRESS",
        "configMTIMECMP_BASE_ADDRESS",
        "__builtin_clz",
    ),
}


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def read_lock(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw or raw.startswith("#"):
            continue
        if "=" not in raw:
            fail(f"invalid lock line {number}: {raw!r}")
        key, value = raw.split("=", 1)
        if not key or not value or key in values:
            fail(f"invalid or duplicate lock key on line {number}: {key!r}")
        values[key] = value
    return values


def git_output(source: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(source), *arguments],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        fail(f"git {' '.join(arguments)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def audit(source: Path, lock: dict[str, str]) -> dict[str, object]:
    revision = lock["revision"]
    if len(revision) != 40 or any(character not in "0123456789abcdef" for character in revision):
        fail("locked revision is not a full lowercase SHA")

    if (source / ".git").is_dir():
        actual_revision = git_output(source, "rev-parse", "HEAD")
        if actual_revision != revision:
            fail(f"source revision mismatch: expected={revision} actual={actual_revision}")
        if git_output(source, "status", "--porcelain", "--untracked-files=all"):
            fail("FreeRTOS source tree is not clean")
    else:
        actual_revision = revision

    texts: dict[str, str] = {}
    files: dict[str, dict[str, object]] = {}
    for name, relative in PORT_FILES.items():
        path = source / relative
        if not path.is_file():
            fail(f"missing required RISC-V port file: {relative}")
        payload = path.read_bytes()
        text = payload.decode("utf-8")
        texts[name] = text
        missing = [fragment for fragment in REQUIRED_FRAGMENTS[name] if fragment not in text]
        if missing:
            fail(f"{name} is missing required fragments: {', '.join(missing)}")
        files[name] = {
            "path": relative.as_posix(),
            "bytes": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
            "required_fragments": list(REQUIRED_FRAGMENTS[name]),
        }

    expected_blobs = {
        "port.c": lock["port_c_blob_sha"],
        "portASM.S": lock["port_asm_blob_sha"],
        "portmacro.h": lock["portmacro_blob_sha"],
    }
    if (source / ".git").is_dir():
        for name, relative in PORT_FILES.items():
            actual_blob = git_output(source, "hash-object", relative.as_posix())
            if actual_blob != expected_blobs[name]:
                fail(
                    f"{name} blob mismatch: expected={expected_blobs[name]} actual={actual_blob}"
                )
            files[name]["git_blob_sha"] = actual_blob

    contract = {
        "schema": 1,
        "status": "PASS",
        "upstream": {
            "repository": lock["repository"],
            "release": lock["release"],
            "revision": actual_revision,
            "license": lock["license"],
        },
        "initial_target": {
            "profile": lock["initial_profile"],
            "harts": int(lock["initial_harts"]),
            "mhartid": int(lock["expected_mhartid"], 0),
            "mtime": lock["mtime_address"],
            "mtimecmp": lock["mtimecmp_address"],
            "uart": lock["uart_address"],
            "exit": lock["exit_address"],
            "fpu": False,
            "vpu": False,
            "supervisor_mode": False,
            "virtual_memory": False,
        },
        "required_architecture": {
            "integer_registers": "x1-x31",
            "instructions": ["RV32I", "M", "Zicsr", "ECALL", "MRET", "FENCE"],
            "csrs": [
                "mstatus",
                "misa",
                "mie",
                "mtvec",
                "mscratch",
                "mepc",
                "mcause",
                "mtval",
                "mip",
                "mhartid",
            ],
            "interrupts": ["machine-timer"],
        },
        "port_behavior": {
            "yield": "ecall",
            "critical_sections": "mstatus.MIE clear/set",
            "tick_source": "memory-mapped mtime/mtimecmp",
            "context_switch": "compiler-created task stacks plus assembly trap frame",
            "initial_task_privilege": "M-mode",
        },
        "aethercore_boundary": {
            "already_present": [
                "RV32IM",
                "Zicsr",
                "precise synchronous traps",
                "MRET",
                "machine timer interrupt",
                "mtime/mtimecmp MMIO",
                "single-hart mhartid=0",
            ],
            "platform_glue_pending": [
                "startup and linker script",
                "FreeRTOSConfig.h",
                "trap vector installation",
                "UART output and deterministic exit",
                "application workload and negative probes",
            ],
            "not_required_for_initial_gate": ["A", "C", "F", "V", "S-mode", "MMU"],
        },
        "files": files,
    }
    return contract


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument(
        "output",
        type=Path,
        nargs="?",
        default=ROOT / "build" / "freertos-qualification" / "port-contract.json",
    )
    arguments = parser.parse_args()

    if not LOCK_FILE.is_file():
        fail(f"missing lock file: {LOCK_FILE}")
    lock = read_lock(LOCK_FILE)
    required_lock_keys = {
        "repository",
        "release",
        "revision",
        "license",
        "port_directory",
        "port_c_blob_sha",
        "port_asm_blob_sha",
        "portmacro_blob_sha",
        "initial_profile",
        "initial_harts",
        "expected_mhartid",
        "mtime_address",
        "mtimecmp_address",
        "uart_address",
        "exit_address",
    }
    missing = sorted(required_lock_keys - lock.keys())
    if missing:
        fail(f"lock file is missing keys: {', '.join(missing)}")

    contract = audit(arguments.source.resolve(), lock)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(
        json.dumps(contract, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"PASS: FreeRTOS {contract['upstream']['release']} RISC-V port contract "
        f"revision={contract['upstream']['revision']} output={arguments.output}"
    )


if __name__ == "__main__":
    main()
