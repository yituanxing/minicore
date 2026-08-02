#!/usr/bin/env python3
"""Generate the scheduler NEMU adapter from the shared RV32 timer adapter.

The scheduler reads the free-running mtime MMIO register. That value is an
asynchronous platform input, not an architectural result that the reference
CPU can independently predict. Before NEMU executes such a load, copy the
bytes observed by the DUT into NEMU's passive mtime mapping. NEMU still
executes the load and remains authoritative for the destination register and
all following ordinary instructions.
"""

from __future__ import annotations

import argparse
from pathlib import Path


EXEC_NEEDLE = """    } else {
      exec_(1);
      regcpy_(&after, kToDut);
    }
"""

EXEC_REPLACEMENT = """    } else {
      synchronizeTimerLoad(commit);
      exec_(1);
      regcpy_(&after, kToDut);
    }
"""

METHOD_NEEDLE = """  void observeTimerStore(const DifftestCommit& commit) {
"""

METHOD_REPLACEMENT = """  void synchronizeTimerLoad(const DifftestCommit& commit) {
    if (!commit.memValid || commit.memWrite) return;

    const auto address = checkedAddress(commit.memAddr, "timer Load address");
    const std::uint32_t funct3 = (commit.inst >> 12) & 0x7U;
    std::size_t size = 0;
    switch (funct3) {
      case 0:  // LB
      case 4:  // LBU
        size = 1;
        break;
      case 1:  // LH
      case 5:  // LHU
        size = 2;
        break;
      case 2:  // LW
        size = 4;
        break;
      default:
        return;
    }

    if (address < kMtimeAddress ||
        static_cast<std::uint64_t>(address - kMtimeAddress) + size > 8U) {
      return;
    }

    auto* mapped = mappedPointer(address, size);
    if (mapped == nullptr) {
      fail("timer Load did not resolve to the passive mtime mapping");
    }

    const auto value = static_cast<std::uint32_t>(commit.rdData);
    for (std::size_t byte = 0; byte < size; ++byte) {
      mapped[byte] = static_cast<std::uint8_t>(value >> (byte * 8));
    }
  }

  void observeTimerStore(const DifftestCommit& commit) {
"""


def replace_exactly_once(text: str, needle: str, replacement: str, label: str) -> str:
    count = text.count(needle)
    if count != 1:
        raise SystemExit(f"expected exactly one {label} insertion point, found {count}")
    return text.replace(needle, replacement, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    text = args.source.read_text(encoding="utf-8")
    text = replace_exactly_once(text, EXEC_NEEDLE, EXEC_REPLACEMENT, "NEMU exec")
    text = replace_exactly_once(text, METHOD_NEEDLE, METHOD_REPLACEMENT, "timer method")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text, encoding="utf-8")
    print(f"wrote scheduler timer adapter: {args.output}")


if __name__ == "__main__":
    main()
