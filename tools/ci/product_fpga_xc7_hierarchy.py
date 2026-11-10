#!/usr/bin/env python3
"""Parse Yosys's actual design-wide XC7 mapping statistics, including hierarchy.

This does *not* certify Yosys exit status, device fit, timing, or an FPGA
board. It can salvage module-attribution data even when a later report-export
step failed, while verifying the extracted full-design primitive totals.
"""

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re
import sys


TITLE = re.compile(r"^=== (.+) ===$", re.MULTILINE)
CELL_COUNT = re.compile(r"^[ \t]+Number of cells:[ \t]+(\d+)[ \t]*$", re.MULTILINE)
ESTIMATED_LC = re.compile(r"^[ \t]+Estimated number of LCs:[ \t]+(\d+)[ \t]*$", re.MULTILINE)
CELL_LINE = re.compile(r"^\s+([^\s:]+)\s+(\d+)\s*$")
HIERARCHY_LINE = re.compile(r"^( +)(\S+)\s+(\d+)\s*$")


class MappingReportError(ValueError):
    """The log does not establish a self-consistent completed XC7 mapping."""


def _cells(block):
    match = CELL_COUNT.search(block)
    if match is None:
        raise MappingReportError("missing Number of cells in a Yosys stat block")
    entries = {}
    for line in block[match.end():].lstrip("\r\n").splitlines():
        if not line.strip():
            break
        cell = CELL_LINE.match(line)
        if cell is None:
            raise MappingReportError(f"unrecognized primitive/module row: {line!r}")
        kind, value = cell.group(1), int(cell.group(2))
        if kind in entries:
            raise MappingReportError(f"duplicate cell type {kind}")
        entries[kind] = value
    total = int(match.group(1))
    if sum(entries.values()) != total:
        raise MappingReportError(f"cell-count mismatch: {sum(entries.values())} != {total}")
    lc = ESTIMATED_LC.search(block)
    return total, entries, int(lc.group(1)) if lc else None


def _add(a, b):
    for name, count in b.items():
        a[name] += count


def _mul(values, weight):
    return Counter({name: count * weight for name, count in values.items()})


def _lut_count(cells):
    return sum(cells.get(f"LUT{i}", 0) for i in range(1, 7))


def _ff_count(cells):
    return sum(count for name, count in cells.items() if name.startswith("FD"))


def parse_yosys_hierarchy(log, expected_top="AetherCoreProductFpgaUartDdrBoard"):
    marker = log.rfind("Printing statistics.")
    if marker < 0:
        raise MappingReportError("missing finished synth_xilinx statistics section")
    report = log[marker:]
    titles = list(TITLE.finditer(report))
    if not titles:
        raise MappingReportError("missing Yosys module summaries")

    blocks = {
        title.group(1): report[title.end():titles[i + 1].start() if i + 1 < len(titles) else len(report)]
        for i, title in enumerate(titles)
    }
    hierarchy = blocks.pop("design hierarchy", None)
    if hierarchy is None:
        raise MappingReportError("missing design-wide hierarchy summary")
    local = {name: _cells(block) for name, block in blocks.items()}
    overall_count, expected_primitives, total_lcs = _cells(hierarchy)
    if total_lcs is None:
        raise MappingReportError("missing complete-hierarchy estimated LC count")

    hier_end = re.search(r"^\s+Number of wires:", hierarchy, re.MULTILINE)
    if hier_end is None:
        raise MappingReportError("missing boundary after hierarchical instance list")
    nodes = []
    stack = []
    instance_counts = Counter()
    for line in hierarchy[:hier_end.start()].splitlines():
        match = HIERARCHY_LINE.match(line)
        if match is None:
            if line.strip():
                raise MappingReportError(f"unknown hierarchy row {line!r}")
            continue
        indent, module, multiplicity = len(match.group(1)), match.group(2), int(match.group(3))
        if module not in local:
            raise MappingReportError(f"missing module-local statistics for {module}")
        while stack and stack[-1][0] >= indent:
            stack.pop()
        if nodes and not stack:
            raise MappingReportError("more than one hierarchy root")
        parent = stack[-1][1] if stack else None
        effective = multiplicity * (nodes[parent]["effective_instances"] if parent is not None else 1)
        node = {
            "module": module,
            "effective_instances": effective,
            "parent": parent,
            "depth": len(stack),
            "own": _mul({kind: count for kind, count in local[module][1].items() if kind not in local}, effective),
            "subtree": Counter(),
        }
        index = len(nodes)
        nodes.append(node)
        if parent is not None:
            nodes[parent].setdefault("children", []).append(index)
        stack.append((indent, index))
        instance_counts[module] += effective

    if not nodes or nodes[0]["module"] != expected_top or nodes[0]["effective_instances"] != 1:
        raise MappingReportError("wrong or missing exact Product hierarchy root")
    mapped = Counter()
    for node in nodes:
        _add(mapped, node["own"])
    if dict(mapped) != expected_primitives or sum(mapped.values()) != overall_count:
        raise MappingReportError("weighted hierarchy cells differ from Yosys design totals")

    for node in reversed(nodes):
        node["subtree"] = node["own"].copy()
        for child in node.get("children", []):
            _add(node["subtree"], nodes[child]["subtree"])
    if dict(nodes[0]["subtree"]) != expected_primitives:
        raise MappingReportError("root hierarchy does not sum to mapped resources")

    own_by_module = defaultdict(Counter)
    for node in nodes:
        _add(own_by_module[node["module"]], node["own"])
    module_rows = [
        {
            "module": module,
            "instances": instance_counts[module],
            "own_luts": _lut_count(cells),
            "own_ffs": _ff_count(cells),
            "own_dsp48e1": cells.get("DSP48E1", 0),
            "own_primitives": sum(cells.values()),
        }
        for module, cells in own_by_module.items()
    ]
    module_rows.sort(key=lambda row: (-row["own_luts"], -row["own_primitives"], row["module"]))
    subtree_rows = [
        {
            "module": node["module"],
            "depth": node["depth"],
            "instances": node["effective_instances"],
            "subtree_luts": _lut_count(node["subtree"]),
            "subtree_ffs": _ff_count(node["subtree"]),
            "subtree_dsp48e1": node["subtree"].get("DSP48E1", 0),
        }
        for node in nodes
    ]
    subtree_rows.sort(key=lambda row: (-row["subtree_luts"], row["module"], row["depth"]))
    return {
        "status": "XC7_MAPPED_HIERARCHY_PARSED",
        "qualification": "Observed Yosys mapped hierarchy; no CI success, part fit or physical timing implied",
        "top": expected_top,
        "total_cells": overall_count,
        "total_luts": _lut_count(mapped),
        "total_ffs": _ff_count(mapped),
        "total_dsp48e1": mapped.get("DSP48E1", 0),
        "estimated_lcs": total_lcs,
        "primitive_counts": dict(sorted(mapped.items())),
        "module_own_hotspots": module_rows,
        "module_subtrees": subtree_rows,
        "hierarchy_nodes": len(nodes),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", help="Yosys log path, or - for stdin")
    parser.add_argument("output", help="Machine-readable hierarchy report JSON")
    parser.add_argument("--top", default="AetherCoreProductFpgaUartDdrBoard")
    args = parser.parse_args()
    log = sys.stdin.read() if args.log == "-" else Path(args.log).read_text(errors="replace")
    try:
        result = parse_yosys_hierarchy(log, args.top)
    except MappingReportError as exc:
        print(f"XC7_HIERARCHY_UNAVAILABLE: {exc}", file=sys.stderr)
        return 1
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(
        "XC7_HIERARCHY_OBSERVED:"
        f" lut={result['total_luts']} ff={result['total_ffs']}"
        f" dsp48e1={result['total_dsp48e1']} total_cells={result['total_cells']}"
        " (mapped hierarchy only; CI qualification separate)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
