#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import sys


@dataclass(frozen=True)
class RuntimeCase:
    level: str
    case_id: str
    marker: str
    command: str
    coverage: str


CASES = [
    RuntimeCase(
        "L1-process",
        "builtin",
        "L32 FORKSERVER BUILTIN OK",
        r"printf 'L32 FORKSERVER BUILTIN %s\n' OK",
        "tty RX/TX, shell builtin, syscall return",
    ),
    RuntimeCase(
        "L1-process",
        "subshell",
        "L32 FORKSERVER SUBSHELL OK",
        r'''/bin/sh -c 'printf "L32 FORKSERVER SUBSHELL %s\n" OK' ''',
        "fork/exec/wait and child userspace return",
    ),
    RuntimeCase(
        "L1-process",
        "pipeline",
        "L32 BUSYBOX PIPE PARENT OK",
        r'''printf 'PIPE_TOKEN\n' | /bin/sh -c 'read x; case "$x" in PIPE_TOKEN) printf "L32 BUSYBOX PIPE CHILD %s\n" OK;; *) exit 1;; esac' && printf 'L32 BUSYBOX PIPE PARENT %s\n' OK''',
        "pipe, fd redirection, fork/exec/wait, parent resume",
    ),
    RuntimeCase(
        "L2-vfs",
        "vfs",
        "L32_PROBE_VFS_PASS",
        r"/bin/l32-runtime-probe vfs",
        "open/create/write/read/lseek/fstat/stat/rename/append/unlink/ENOENT",
    ),
    RuntimeCase(
        "L3-vm",
        "vm",
        "L32_PROBE_VM_PASS",
        r"/bin/l32-runtime-probe vm",
        "anonymous mmap, first-touch zero-fill, multi-page R/W, mprotect, munmap",
    ),
    RuntimeCase(
        "L4-cow",
        "cow",
        "L32_PROBE_COW_PASS",
        r"/bin/l32-runtime-probe cow",
        "fork, private anonymous mapping, write COW, waitpid, parent isolation",
    ),
    RuntimeCase(
        "L5-signal",
        "signal",
        "L32_PROBE_SIGNAL_PASS",
        r"/bin/l32-runtime-probe signal",
        "sigaction, self-signal delivery, handler entry, sigreturn",
    ),
    RuntimeCase(
        "L6-time",
        "time",
        "L32_PROBE_TIME_PASS",
        r"/bin/l32-runtime-probe time",
        "clock_gettime, nanosleep, timer interrupt and scheduler return",
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
    for case in CASES:
        flat = case.case_id + case.marker + case.command
        if any(ch in flat for ch in ("\t", "\r", "\n")):
            raise ValueError(f"runtime case {case.case_id!r} is not TSV-safe")
        if case.marker in case.command:
            # The interactive tty echoes injected input. A literal final marker
            # in the command could satisfy the milestone before execution.
            raise ValueError(f"runtime case {case.case_id!r} embeds its final marker in input")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(f"{case.case_id}\t{case.marker}\t{case.command}" for case in CASES) + "\n"
    )
    print(f"L32_RUNTIME_SUITE_BATCH cases={len(CASES)} path={path}")
    for case in CASES:
        print(
            f"L32_RUNTIME_SUITE_CASE level={case.level} id={case.case_id} "
            f"coverage={case.coverage}"
        )


def verify_text(text: str) -> None:
    text = text.replace("\r", "")

    for marker in BAD_KERNEL_MARKERS:
        if marker in text:
            raise RuntimeError(f"kernel bad marker observed: {marker}")

    if "L32_FORKSERVER_READY " not in text:
        raise RuntimeError("forkserver warm boundary was not reached")

    for case in CASES:
        if f"L32_FORKSERVER_CASE_PASS id={case.case_id} " not in text:
            raise RuntimeError(f"runtime case did not pass: {case.case_id}")
        if not _line_present(text, case.marker):
            raise RuntimeError(f"runtime case marker missing: {case.case_id}: {case.marker}")

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
