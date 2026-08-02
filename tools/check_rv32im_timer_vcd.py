#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Freeze the first precise RV32IM timer interrupt boundary from a VCD."
    )
    parser.add_argument("vcd", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    text = args.vcd.read_text(errors="strict").splitlines()
    names = {
        "valid": "io_commit_valid",
        "interrupt": "io_commit_interrupt",
        "cause": "io_commit_interruptCause",
        "epc": "io_commit_interruptPc",
        "pc": "io_commit_pc",
    }
    ids: dict[str, str] = {}
    clock_id: str | None = None
    reset_id: str | None = None

    for line in text:
        parts = line.split()
        if len(parts) >= 5 and parts[0] == "$var":
            ident, name = parts[3], parts[4]
            if name == "clock" and clock_id is None:
                clock_id = ident
            if name == "reset" and reset_id is None:
                reset_id = ident
            for key, wanted in names.items():
                if name == wanted and key not in ids:
                    ids[key] = ident
        if line == "$enddefinitions $end":
            break

    required = {"valid", "interrupt", "cause", "epc", "pc"}
    if not clock_id or not reset_id or not required <= ids.keys():
        raise RuntimeError(
            f"missing VCD signals: clock={clock_id} reset={reset_id} ids={ids}"
        )

    state = {clock_id: "0", reset_id: "1"}
    state.update({ident: "0" for ident in ids.values()})
    timestamp: int | None = None
    samples: list[tuple[int, dict[str, str]]] = []

    for line in text:
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

    events: list[tuple[int, dict[str, str]]] = []
    for sample_time, values in samples:
        if (
            values[clock_id] == "0"
            and values[reset_id] == "0"
            and values[ids["valid"]] == "1"
        ):
            events.append((sample_time, values))

    matches = [
        (index, sample_time, values)
        for index, (sample_time, values) in enumerate(events)
        if values[ids["interrupt"]] == "1"
    ]
    if len(matches) != 1:
        raise RuntimeError(f"expected one interrupt event, found {len(matches)}: {matches}")

    index, sample_time, values = matches[0]

    def parse(key: str) -> int:
        bits = values[ids[key]].replace("x", "0").replace("z", "0")
        return int(bits, 2)

    result = (
        f"event_index={index}\n"
        f"vcd_timestamp={sample_time}\n"
        f"retiring_pc=0x{parse('pc'):08x}\n"
        f"interrupt_cause=0x{parse('cause'):08x}\n"
        f"interrupt_pc=0x{parse('epc'):08x}\n"
        f"committed_events={len(events)}\n"
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(result)
    print(result, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
