#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys


CASES = [
    (
        "builtin",
        "L32 FORKSERVER BUILTIN OK",
        r"printf 'L32 FORKSERVER BUILTIN %s\n' OK",
    ),
    (
        "subshell",
        "L32 FORKSERVER SUBSHELL OK",
        r'''/bin/sh -c 'printf "L32 FORKSERVER SUBSHELL %s\n" OK' ''',
    ),
    (
        "pipeline",
        "L32 BUSYBOX PIPE PARENT OK",
        r'''printf 'PIPE_TOKEN\n' | /bin/sh -c 'read x; case "$x" in PIPE_TOKEN) printf "L32 BUSYBOX PIPE CHILD %s\n" OK;; *) exit 1;; esac' && printf 'L32 BUSYBOX PIPE PARENT %s\n' OK''',
    ),
    (
        "vfs",
        "L32 BUSYBOX VFS OK",
        r'''set -eu; f=/tmp/l32-vfs-runtime-file; printf 'alpha\nbeta\n' > "$f"; { IFS= read -r a; IFS= read -r b; } < "$f"; [ "$a" = alpha ]; [ "$b" = beta ]; [ -f "$f" ]; [ ! -e /tmp/l32-vfs-runtime-missing ]; printf 'gamma\n' >> "$f"; n=0; last=; while IFS= read -r line; do n=$((n + 1)); last=$line; done < "$f"; [ "$n" -eq 3 ]; [ "$last" = gamma ]; printf 'L32 BUSYBOX VFS %s\n' OK''',
    ),
]

BAD_KERNEL_MARKERS = (
    "Oops:",
    "BUG:",
    "Kernel panic",
    "Unable to handle kernel",
    "soft lockup",
    "workqueue lockup",
    "Attempted to kill init",
)


def _line_present(text: str, marker: str) -> bool:
    lines = text.splitlines()
    return marker in lines or f"# {marker}" in lines


def write_batch(path: Path) -> None:
    for case_id, marker, command in CASES:
        if any(ch in case_id + marker + command for ch in ("\t", "\r", "\n")):
            # Commands carry guest newlines as the two characters "\\n"; literal
            # TSV newlines/tabs would corrupt the forkserver batch format.
            raise ValueError(f"runtime case {case_id!r} is not TSV-safe")
        if marker in command:
            # The interactive tty echoes injected input. A literal final marker
            # in the command could therefore satisfy the simulator milestone
            # before the command actually executes. Require every workload to
            # construct its marker only after its semantic checks succeed.
            raise ValueError(f"runtime case {case_id!r} embeds its final marker in input")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join("\t".join(row) for row in CASES) + "\n")
    print(f"L32_RUNTIME_SUITE_BATCH cases={len(CASES)} path={path}")


def verify_text(text: str) -> None:
    text = text.replace("\r", "")

    for marker in BAD_KERNEL_MARKERS:
        if marker in text:
            raise RuntimeError(f"kernel bad marker observed: {marker}")

    if "L32_FORKSERVER_READY " not in text:
        raise RuntimeError("forkserver warm boundary was not reached")

    for case_id, marker, _ in CASES:
        if f"L32_FORKSERVER_CASE_PASS id={case_id} " not in text:
            raise RuntimeError(f"runtime case did not pass: {case_id}")
        if not _line_present(text, marker):
            raise RuntimeError(f"runtime case marker missing: {case_id}: {marker}")

    # The pipeline case intentionally proves that data crossed a real pipe into
    # a child shell before the parent resumed after wait/exec completion.
    if not _line_present(text, "L32 BUSYBOX PIPE CHILD OK"):
        raise RuntimeError("pipeline child marker missing")
    if not _line_present(text, "L32 BUSYBOX PIPE PARENT OK"):
        raise RuntimeError("pipeline parent marker missing")

    expected = f"L32_FORKSERVER_PASS cases={len(CASES)} "
    if expected not in text:
        raise RuntimeError(f"forkserver suite endpoint missing: {expected.strip()}")


def verify_log(path: Path) -> None:
    verify_text(path.read_text(errors="replace"))
    print(f"L32_RUNTIME_SUITE_PASS cases={len(CASES)} log={path}")


def main(argv: list[str]) -> int:
    if len(argv) != 3 or argv[1] not in {"write-batch", "verify-log"}:
        print(
            f"usage: {Path(argv[0]).name} write-batch|verify-log PATH",
            file=sys.stderr,
        )
        return 2

    path = Path(argv[2])
    if argv[1] == "write-batch":
        write_batch(path)
    else:
        verify_log(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
