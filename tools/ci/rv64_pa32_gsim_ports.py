#!/usr/bin/env python3
"""Read public GSIM-generated C++ port API; fail if expected PA32 seam is absent."""
import pathlib
import re
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: rv64_pa32_gsim_ports.py GENERATED_HEADER MANIFEST")
header = pathlib.Path(sys.argv[1])
text = header.read_text()
signals = []
for line in text.splitlines():
    m = re.search(r"\b(?:set|get)_([a-zA-Z0-9_$]+)\s*\(", line)
    if m:
        signals.append((("input" if "set_" in line else "output"), m.group(1)))
needed = ("imemInst", "memReady", "memRdata", "ptwReady", "ptwRdata",
          "uartValid", "commit", "reset")
missing = [name for name in needed if not any(name in signal for _, signal in signals)]
if missing:
    raise SystemExit(f"GSIM generated API lacks PA32 transport signals: {missing}")
output = pathlib.Path(sys.argv[2])
output.write_text("\n".join(f"{direction}\t{signal}" for direction, signal in sorted(signals)) + "\n")
print(f"AETHERCORE_PA32_GSIM_PORTS_FOUND total={len(signals)} inputs={sum(s[0]=='input' for s in signals)} outputs={sum(s[0]=='output' for s in signals)}")
