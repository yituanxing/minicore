#!/usr/bin/env python3
from __future__ import annotations

import struct
import sys
from pathlib import Path

RESTORE_X8_WORD_INDEX = 234
EXPECTED_RESTORE_X8 = 0x0202A403  # lw x8, 32(x5)
NOP = 0x00000013


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: corrupt_rv32im_scheduler_context.py INPUT OUTPUT")

    source = Path(sys.argv[1])
    destination = Path(sys.argv[2])
    image = bytearray(source.read_bytes())
    offset = RESTORE_X8_WORD_INDEX * 4
    if offset + 4 > len(image):
        raise SystemExit("scheduler image is too small for the frozen corruption probe")

    observed = struct.unpack_from("<I", image, offset)[0]
    if observed != EXPECTED_RESTORE_X8:
        raise SystemExit(
            f"restore-x8 instruction changed: observed 0x{observed:08x}, "
            f"expected 0x{EXPECTED_RESTORE_X8:08x}"
        )

    struct.pack_into("<I", image, offset, NOP)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(image)
    print(
        f"patched word {RESTORE_X8_WORD_INDEX}: "
        f"0x{EXPECTED_RESTORE_X8:08x} -> 0x{NOP:08x}"
    )


if __name__ == "__main__":
    main()
