#!/usr/bin/env python3
"""Compare successful same-host RV64 Linux cold boots by retired coverage, not drivers."""
import json
import pathlib
import re
import sys

def parse(path):
    content = pathlib.Path(path).read_text(errors="replace")
    def fields(tag):
        matches = re.findall(r"^" + re.escape(tag) + r" (.+)$", content, re.M)
        if len(matches) != 1:
            raise RuntimeError(f"{path}: expected one {tag}, got {len(matches)}")
        return dict(re.findall(r"([a-zA-Z0-9_-]+)=([^ ]+)", matches[0]))
    result = fields("L32_RUNTIME_MILESTONE_PASS")
    isa = fields("L32_ISA_COVERAGE")
    for key in ("cycles", "commits", "stip", "seip"):
        if int(result[key]) <= 0:
            raise RuntimeError(f"{path}: missing {key}")
    for key in ("commits", "stip", "seip"):
        if int(isa[key]) != int(result[key]) if key != "commits" else int(isa[key]) != int(result[key]):
            raise RuntimeError(f"{path}: inconsistent coverage field {key}")
    return result, isa

def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: rv64_isa_fast_compare.py BASELINE FAST OUTPUT_JSON")
    (base, b), (fast, f) = parse(sys.argv[1]), parse(sys.argv[2])
    missing = {}
    for key, value in b.items():
        if key.startswith(("op_", "ex_")) and int(value) > 0 and int(f.get(key, 0)) == 0:
            missing[key] = int(value)
    baseline_cycles, fast_cycles = int(base["cycles"]), int(fast["cycles"])
    report = {
        "baseline": {"cycles": baseline_cycles, "commits": int(base["commits"])},
        "fast": {"cycles": fast_cycles, "commits": int(fast["commits"])},
        "cycle_reduction_percent": round((baseline_cycles-fast_cycles)*100/baseline_cycles, 2),
        "lost_coverage_classes": missing,
        "baseline_isa": b, "fast_isa": f,
        "note": "Opcode-class coverage is not proof of instruction correctness; retain directed tests and full cold boot."
    }
    pathlib.Path(sys.argv[3]).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(sys.argv[3]).write_text(json.dumps(report, indent=2, sort_keys=True)+"\n")
    print("RV64_ISA_FAST_AB_RESULT", json.dumps({k:v for k,v in report.items() if k not in ("baseline_isa","fast_isa")},sort_keys=True))
    return 0

if __name__ == "__main__":
    sys.exit(main())
