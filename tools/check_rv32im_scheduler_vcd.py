#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

EXPECTED_EVENTS = 1278
EXPECTED_INTERRUPTS = (
    (152, 358, 0x80000140, 0x800022B7, 0x80000007, 0x80000144),
    (288, 696, 0x80000250, 0xF81FF06F, 0x80000007, 0x800001D0),
    (426, 1038, 0x800001C8, 0x24737663, 0x80000007, 0x800001CC),
    (562, 1376, 0x80000250, 0xF81FF06F, 0x80000007, 0x800001D0),
    (698, 1718, 0x800001B8, 0x00130313, 0x80000007, 0x800001BC),
    (834, 2056, 0x80000250, 0xF81FF06F, 0x80000007, 0x800001D0),
    (972, 2398, 0x800001B0, 0x00028293, 0x80000007, 0x800001B4),
    (1108, 2736, 0x80000250, 0xF81FF06F, 0x80000007, 0x800001D0),
)


def parse_value(bits: str) -> int:
    return int(bits.replace("x", "0").replace("z", "0"), 2)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: check_rv32im_scheduler_vcd.py INPUT.vcd OUTPUT.txt")

    lines = Path(sys.argv[1]).read_text(errors="strict").splitlines()
    wanted = {
        "valid": "io_commit_valid",
        "interrupt": "io_commit_interrupt",
        "cause": "io_commit_interruptCause",
        "epc": "io_commit_interruptPc",
        "pc": "io_commit_pc",
        "inst": "io_commit_inst",
    }
    ids: dict[str, str] = {}
    clock_id = None
    reset_id = None
    for line in lines:
        parts = line.split()
        if len(parts) >= 5 and parts[0] == "$var":
            ident, name = parts[3], parts[4]
            if name == "clock" and clock_id is None:
                clock_id = ident
            if name == "reset" and reset_id is None:
                reset_id = ident
            for key, signal in wanted.items():
                if name == signal and key not in ids:
                    ids[key] = ident
        if line == "$enddefinitions $end":
            break

    if not clock_id or not reset_id or not set(wanted) <= ids.keys():
        raise SystemExit(f"missing VCD signals: clock={clock_id} reset={reset_id} ids={ids}")

    state = {clock_id: "0", reset_id: "1"}
    state.update({ident: "0" for ident in ids.values()})
    timestamp = None
    samples: list[tuple[int, dict[str, str]]] = []
    for line in lines:
        if line.startswith("#"):
            if timestamp is not None:
                samples.append((timestamp, dict(state)))
            timestamp = int(line[1:])
        elif line and line[0] in "01xz":
            ident = line[1:]
            if ident in state:
                state[ident] = line[0]
        elif line.startswith("b"):
            bits, ident = line[1:].split(" ", 1)
            if ident in state:
                state[ident] = bits
    if timestamp is not None:
        samples.append((timestamp, dict(state)))

    events = [
        (sample_time, values)
        for sample_time, values in samples
        if values[clock_id] == "0"
        and values[reset_id] == "0"
        and values[ids["valid"]] == "1"
    ]
    observed = []
    for index, (sample_time, values) in enumerate(events):
        if values[ids["interrupt"]] != "1":
            continue
        observed.append(
            (
                index,
                sample_time,
                parse_value(values[ids["pc"]]),
                parse_value(values[ids["inst"]]),
                parse_value(values[ids["cause"]]),
                parse_value(values[ids["epc"]]),
            )
        )

    if len(events) != EXPECTED_EVENTS:
        raise SystemExit(f"retirement count changed: {len(events)} != {EXPECTED_EVENTS}")
    if tuple(observed) != EXPECTED_INTERRUPTS:
        raise SystemExit(
            "scheduler interrupt boundaries changed:\n"
            f"observed={observed}\nexpected={list(EXPECTED_INTERRUPTS)}"
        )

    output = [
        f"committed_events={len(events)}",
        f"interrupt_events={len(observed)}",
    ]
    for number, (index, sample_time, pc, instruction, cause, resume_pc) in enumerate(observed):
        output.extend(
            [
                f"irq{number}_event_index={index}",
                f"irq{number}_vcd_timestamp={sample_time}",
                f"irq{number}_retiring_pc=0x{pc:08x}",
                f"irq{number}_instruction=0x{instruction:08x}",
                f"irq{number}_cause=0x{cause:08x}",
                f"irq{number}_resume_pc=0x{resume_pc:08x}",
            ]
        )
    result = "\n".join(output) + "\n"
    destination = Path(sys.argv[2])
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(result, encoding="utf-8")
    print(result, end="")


if __name__ == "__main__":
    main()
