#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import re
import signal
import subprocess
import sys
from typing import Sequence


PROGRESS_RE = re.compile(r"L32_SIM_PROGRESS cycles=(\d+) commits=(\d+)")
RUNTIME_ACTIVITY_PREFIXES = (
    "L32_FIRST_INTERRUPT",
    "L32_FIRST_SUPERVISOR_EXTERNAL_INTERRUPT",
    "L32_UART_RX_INTERRUPT",
    "L32_UART_INPUT_",
    "L32_UART_MILESTONE",
    "L32_OPENSBI_BANNER",
    "L32_FIRST_EXCEPTION",
    "L32_FIRST_OPENSBI_COMPRESSED",
    "L32_FIRST_LINUX_COMPRESSED",
)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run a Linux frontier command and fail early when guest cycles continue "
            "but commits/UART/visible interrupt activity stop progressing."
        )
    )
    parser.add_argument(
        "--stall-cycles",
        type=int,
        required=True,
        help="Maximum guest cycles without observable progress; 0 disables the watchdog.",
    )
    parser.add_argument(
        "command",
        nargs=argparse.REMAINDER,
        help="Command after --, for example: -- make -f Makefile.l32-linux-boot ...",
    )
    args = parser.parse_args(argv)
    if args.stall_cycles < 0:
        parser.error("--stall-cycles must be >= 0")
    if args.command and args.command[0] == "--":
        args.command = args.command[1:]
    if not args.command:
        parser.error("missing command after --")
    return args


def terminate_group(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
    except ProcessLookupError:
        pass


def run(command: Sequence[str], stall_cycles: int) -> int:
    process = subprocess.Popen(
        list(command),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        start_new_session=True,
    )
    assert process.stdout is not None

    last_progress_cycle: int | None = None
    last_commits: int | None = None
    activity_since_progress = False

    try:
        for line in process.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()

            match = PROGRESS_RE.search(line)
            if match:
                cycles = int(match.group(1))
                commits = int(match.group(2))
                progressed = (
                    last_progress_cycle is None
                    or last_commits is None
                    or commits > last_commits
                    or activity_since_progress
                )
                if progressed:
                    last_progress_cycle = cycles
                elif (
                    stall_cycles > 0
                    and last_progress_cycle is not None
                    and cycles - last_progress_cycle >= stall_cycles
                ):
                    print(
                        "L32_FRONTIER_STALL "
                        f"cycles={cycles} commits={commits} "
                        f"last-progress-cycle={last_progress_cycle} "
                        f"stall-cycles={cycles - last_progress_cycle} "
                        f"budget={stall_cycles}",
                        file=sys.stderr,
                        flush=True,
                    )
                    terminate_group(process)
                    return 86
                last_commits = commits
                activity_since_progress = False
                continue

            stripped = line.strip()
            if stripped and (
                stripped.startswith(RUNTIME_ACTIVITY_PREFIXES)
                or not stripped.startswith(("make", "g++", "verilator", "%Warning"))
            ):
                # During simulation, normal non-progress output is primarily UART.
                # Before the first guest progress sample this flag is harmless.
                activity_since_progress = True
    finally:
        if process.poll() is None:
            terminate_group(process)

    return process.wait()


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    return run(args.command, args.stall_cycles)


if __name__ == "__main__":
    raise SystemExit(main())
