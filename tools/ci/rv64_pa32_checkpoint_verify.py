#!/usr/bin/env python3
"""Reject nondeterministic fork/COW Linux child replays at a frozen UART boundary."""
import pathlib
import re
import sys


def parse(line):
    return dict(re.findall(r"([a-zA-Z][a-zA-Z0-9-]*)=([^ ]+)", line))


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: rv64_pa32_checkpoint_verify.py CHECKPOINT_LOG")
    data = pathlib.Path(sys.argv[1]).read_text(errors="replace")
    def rows(marker):
        return [parse(m.group(1)) for m in re.finditer(r"^" + marker + r" (.+)$", data, re.M)]
    ready, passes, passed = rows("V2_CHECKPOINT_READY"), rows("V2_CHECKPOINT_CHILD_PASS"), rows("V2_CHECKPOINT_PASS")
    if len(ready) != 1 or len(passes) != 2 or len(passed) != 1:
        raise RuntimeError("checkpoint needs one parent and two independently successful children")
    if [p.get("index") for p in passes] != ["0", "1"]:
        raise RuntimeError("child indices must be 0 and 1")
    fields = ("delta-cycles", "delta-commits", "interrupts-delta", "stip-delta", "seip-delta")
    first, second = [{k: int(p[k]) for k in fields} for p in passes]
    if first != second:
        raise RuntimeError(f"fork replay diverged: {first} versus {second}")
    if first["delta-cycles"] <= 0 or first["delta-commits"] <= 0 or first["seip-delta"] < 1:
        raise RuntimeError("missing execution or real post-checkpoint SEIP")
    if int(ready[0]["stip"]) < 1:
        raise RuntimeError("checkpoint parent did not observe STIP")
    if int(ready[0]["cycles"]) != int(passed[0]["checkpoint-cycles"]):
        raise RuntimeError("parent advanced after forking")
    if (int(passed[0]["passed"]), int(passed[0]["failed"])) != (2, 0):
        raise RuntimeError("incorrect child result")
    print("PA32_CHECKPOINT_DETERMINISTIC_REPLAY_PASS", first)


if __name__ == "__main__":
    main()
