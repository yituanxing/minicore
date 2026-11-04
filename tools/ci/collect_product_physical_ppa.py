#!/usr/bin/env python3
"""Collect the latest artifact for each Product FPGA matrix lane.

GitHub's "re-run failed jobs" does not re-run successful matrix lanes. Their
artifacts therefore belong to an earlier run_attempt. Keep attempts isolated
during download, then select the highest attempt per lane; never silently use
an older successful netlist after a newer attempt failed.
"""

import argparse
from pathlib import Path
import shutil


ISAS = ("integer", "floating")
BOUNDARIES = ("probe", "pins")
EVIDENCE = ("metrics.json", "yosys.log", "commit.sha")


def collect(capacity: str, run_id: str, source: Path, output: Path) -> None:
    if capacity not in ("small", "expanded") or not run_id.isdecimal():
        raise ValueError("invalid capacity or run ID")
    if not source.is_dir():
        raise ValueError("artifact download directory is missing")

    missing = []
    for isa in ISAS:
        for boundary in BOUNDARIES:
            prefix = f"product-physical-fpga-ppa-{capacity}-{isa}-{boundary}-{run_id}-"
            choices = []
            for candidate in source.iterdir():
                if not candidate.is_dir() or not candidate.name.startswith(prefix):
                    continue
                attempt = candidate.name.removeprefix(prefix)
                if attempt.isdecimal() and int(attempt) > 0:
                    choices.append((int(attempt), candidate))
            if not choices:
                missing.append(f"{isa}/{boundary}: no synthesis artifact")
                continue

            attempt, artifact = max(choices)
            lane = artifact / capacity / isa / boundary
            target = output / capacity / isa / boundary
            target.mkdir(parents=True, exist_ok=True)
            for name in EVIDENCE:
                src = lane / name
                if src.is_file():
                    shutil.copyfile(src, target / name)
                else:
                    missing.append(f"{isa}/{boundary} attempt={attempt}: missing {name}")
            (target / "attempt.txt").write_text(f"{attempt}\n")

    if missing:
        raise ValueError("incomplete FPGA PPA: " + "; ".join(missing))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capacity", choices=("small", "expanded"))
    parser.add_argument("run_id")
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        collect(args.capacity, args.run_id, args.source, args.output)
    except ValueError as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
