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
        "L1-process", "builtin", "L32 FORKSERVER BUILTIN OK",
        r"printf 'L32 FORKSERVER BUILTIN %s\n' OK",
        "tty RX/TX, shell builtin, syscall return",
    ),
    RuntimeCase(
        "L1-process", "subshell", "L32 FORKSERVER SUBSHELL OK",
        r'''/bin/sh -c 'printf "L32 FORKSERVER SUBSHELL %s\n" OK' ''',
        "fork/exec/wait and child userspace return",
    ),
    RuntimeCase(
        "L1-process", "pipeline", "L32 BUSYBOX PIPE PARENT OK",
        r'''printf 'PIPE_TOKEN\n' | /bin/sh -c 'read x; case "$x" in PIPE_TOKEN) printf "L32 BUSYBOX PIPE CHILD %s\n" OK;; *) exit 1;; esac' && printf 'L32 BUSYBOX PIPE PARENT %s\n' OK''',
        "pipe, fd redirection, fork/exec/wait, parent resume",
    ),
    RuntimeCase(
        "L2-vfs", "vfs", "L32_PROBE_VFS_PASS",
        r"/bin/l32-runtime-probe vfs",
        "open/create/write/read/lseek/fstat/stat/rename/append/unlink/ENOENT",
    ),
    RuntimeCase(
        "L3-vm", "vm", "L32_PROBE_VM_PASS",
        r"/bin/l32-runtime-probe vm",
        "anonymous mmap, first-touch zero-fill, multi-page R/W, mprotect, munmap",
    ),
    RuntimeCase(
        "L4-cow", "cow", "L32_PROBE_COW_PASS",
        r"/bin/l32-runtime-probe cow",
        "fork, private anonymous mapping, write COW, waitpid, parent isolation",
    ),
    RuntimeCase(
        "L5-signal", "signal", "L32_PROBE_SIGNAL_PASS",
        r"/bin/l32-runtime-probe signal",
        "sigaction, self-signal delivery, handler entry, sigreturn",
    ),
    RuntimeCase(
        "L6-time", "time", "L32_PROBE_TIME_PASS",
        r"/bin/l32-runtime-probe time",
        "clock_gettime, nanosleep, timer interrupt and scheduler return",
    ),
    RuntimeCase(
        "L7-real", "lua-real", "L32_LUA_REAL_PASS 6765 21 2870",
        r"/opt/l32/lua /opt/l32/lua-smoke.lua",
        "unchanged Lua interpreter: recursion, integer arithmetic, tables, strings, closures, metatables, pcall, coroutines, stdio/VFS",
    ),
    RuntimeCase(
        "L7-real", "sqlite-real", "L32_SQLITE_REAL_PASS 1000 500500 333833500 ok",
        r"/opt/l32/sqlite-smoke",
        "SQLite amalgamation: file-backed DB, transaction, 1000 inserts, index, reopen, aggregate, integrity_check",
    ),
    RuntimeCase(
        "L7-real", "bash-real", "L32_BASH_REAL_PASS 5050 6765 21",
        r"/opt/l32/bash /opt/l32/bash-smoke.sh",
        "unchanged Bash: parser, functions, arithmetic, arrays, redirection, mapfile, signal/trap, command substitution",
    ),
    RuntimeCase(
        "L8-busybox-real", "busybox-awk", "L32_BB_AWK_PASS",
        r'''printf '1 2\n3 4\n' | /opt/l32/busybox-real awk '{s += $1 + $2} END { if (s == 10) printf "L32_BB_AWK_%s\n", "PASS"; else exit 1 }' ''',
        "unchanged BusyBox awk: parser/interpreter, integer arithmetic, stdio pipeline",
    ),
    RuntimeCase(
        "L8-busybox-real", "busybox-gzip", "L32_BB_GZIP_PASS",
        r'''b=/opt/l32/busybox-real; printf 'alpha\nbeta\ngamma\n' > /tmp/l32-gzip.in; $b gzip -c /tmp/l32-gzip.in > /tmp/l32-gzip.gz; out=$($b gzip -dc /tmp/l32-gzip.gz); case "$out" in "$(printf 'alpha\nbeta\ngamma')") printf 'L32_BB_GZIP_%s\n' PASS;; *) exit 1;; esac''',
        "unchanged BusyBox gzip/deflate/inflate: file I/O, compression, decompression, byte comparison",
    ),
    RuntimeCase(
        "L8-busybox-real", "busybox-tar", "L32_BB_TAR_PASS",
        r'''b=/opt/l32/busybox-real; d=/tmp/l32-tar; $b rm -rf "$d"; $b mkdir -p "$d/in" "$d/out"; printf 'tar-data\n' > "$d/in/a.txt"; $b tar -cf "$d/a.tar" -C "$d/in" a.txt; $b tar -xf "$d/a.tar" -C "$d/out"; out=$($b cat "$d/out/a.txt"); case "$out" in tar-data) printf 'L32_BB_TAR_%s\n' PASS;; *) exit 1;; esac''',
        "unchanged BusyBox tar: directory lookup, metadata, archive write/read, create/extract",
    ),
    RuntimeCase(
        "L8-busybox-real", "busybox-ed", "L32_BB_ED_PASS",
        r'''b=/opt/l32/busybox-real; f=/tmp/l32-ed.txt; printf 'alpha\nbeta\n' > "$f"; printf '2s/beta/BETA/\nw\nq\n' | $b ed -s "$f"; out=$($b cat "$f"); case "$out" in "$(printf 'alpha\nBETA')") printf 'L32_BB_ED_%s\n' PASS;; *) exit 1;; esac''',
        "unchanged BusyBox ed: parser, buffered editing, stdin command stream, VFS rewrite",
    ),
    RuntimeCase(
        "L8-busybox-real", "busybox-vi", "L32_BB_VI_PASS",
        r'''b=/opt/l32/busybox-real; f=/tmp/l32-vi.txt; printf 'alpha\nbeta\n' > "$f"; TERM=xterm $b vi -c 's/alpha/BETA/' -c 'wq' "$f" </dev/null >/dev/null 2>&1; out=$($b cat "$f"); case "$out" in "$(printf 'BETA\nbeta')") printf 'L32_BB_VI_%s\n' PASS;; *) exit 1;; esac''',
        "unchanged BusyBox vi: editor state machine, colon commands, signals/terminal setup, VFS rewrite",
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
            raise ValueError(f"runtime case {case.case_id!r} embeds its final marker in input")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(f"{case.case_id}\t{case.marker}\t{case.command}" for case in CASES) + "\n"
    )
    print(f"L32_RUNTIME_SUITE_BATCH cases={len(CASES)} path={path}")
    for case in CASES:
        print(f"L32_RUNTIME_SUITE_CASE level={case.level} id={case.case_id} coverage={case.coverage}")


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
        print(f"usage: {Path(argv[0]).name} write-batch|verify-log PATH", file=sys.stderr)
        return 2
    path = Path(argv[2])
    if argv[1] == "write-batch":
        write_batch(path)
    else:
        verify_log(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
