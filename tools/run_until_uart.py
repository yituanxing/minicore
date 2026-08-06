#!/usr/bin/env python3
"""Run a simulator until a required UART signature appears.

The child stdout and stderr are merged, mirrored to this process, and retained
in a binary log.  Seeing the success signature is the acceptance boundary; the
child is then terminated so an interactive guest such as NSH does not consume
the entire bounded CI slot.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import time
from typing import Sequence


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--success", required=True, help="required UART text")
    parser.add_argument("--log", required=True, type=Path, help="captured output")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument(
        "command",
        nargs=argparse.REMAINDER,
        help="child command, conventionally after --",
    )
    args = parser.parse_args(argv)
    if args.command and args.command[0] == "--":
        args.command = args.command[1:]
    if not args.command:
        parser.error("a child command is required after --")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    return args


def stop_child(process: subprocess.Popen[bytes], grace_seconds: float = 2.0) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=grace_seconds)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        return
    process.wait(timeout=grace_seconds)


def run_until_signature(
    command: Sequence[str], success: bytes, log_path: Path, timeout: float
) -> int:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    process = subprocess.Popen(
        list(command),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        start_new_session=True,
        bufsize=0,
    )
    assert process.stdout is not None

    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    deadline = time.monotonic() + timeout
    retained = bytearray()
    overlap = max(0, len(success) - 1)

    try:
        with log_path.open("wb") as log:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    stop_child(process)
                    print(
                        f"ERROR: UART signature timeout after {timeout:.1f}s: "
                        f"{success!r}",
                        file=sys.stderr,
                    )
                    return 124

                events = selector.select(timeout=min(remaining, 0.25))
                for key, _ in events:
                    chunk = os.read(key.fd, 65536)
                    if not chunk:
                        selector.unregister(process.stdout)
                        break
                    log.write(chunk)
                    log.flush()
                    sys.stdout.buffer.write(chunk)
                    sys.stdout.buffer.flush()
                    retained.extend(chunk)
                    if success in retained:
                        stop_child(process)
                        print(
                            f"\nPASS: observed UART signature {success.decode(errors='replace')!r}"
                        )
                        return 0
                    if overlap and len(retained) > overlap:
                        del retained[:-overlap]
                    elif not overlap:
                        retained.clear()

                return_code = process.poll()
                if return_code is not None:
                    # Drain bytes that became readable with process exit.
                    tail = process.stdout.read() or b""
                    if tail:
                        log.write(tail)
                        sys.stdout.buffer.write(tail)
                        sys.stdout.buffer.flush()
                        retained.extend(tail)
                    if success in retained:
                        print(
                            f"\nPASS: observed UART signature {success.decode(errors='replace')!r}"
                        )
                        return 0
                    print(
                        f"ERROR: child exited with {return_code} before UART signature "
                        f"{success!r}",
                        file=sys.stderr,
                    )
                    return return_code if return_code != 0 else 125
    finally:
        selector.close()
        stop_child(process)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    return run_until_signature(
        args.command,
        args.success.encode(),
        args.log,
        args.timeout,
    )


if __name__ == "__main__":
    raise SystemExit(main())
