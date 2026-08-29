#!/usr/bin/env python3
"""Install the bounded P3-B userspace PMP fault probe into pinned NuttX apps.

P3-B deliberately reuses the already-enabled ``hello`` application instead of
adding a permanent application/configuration surface to the pinned apps tree.
Normal ``hello`` behavior remains byte-for-byte equivalent at the source level;
only ``hello pmpfault`` performs one volatile U-mode load from the kernel flash
base (0x80000000).  PMP must reject that load before it reaches the external
memory path, and NuttX must then cancel only the offending user task.

This is a CI-only source overlay applied to the freshly extracted pinned
NuttX-apps 13.0.0 tree.  It is intentionally idempotent and fail-closed on
upstream source drift.
"""

from __future__ import annotations

import argparse
from pathlib import Path


MARKER = "P3_FAULT_BEGIN address=0x80000000"

OLD_INCLUDES = """#include <nuttx/config.h>
#include <stdio.h>
"""

NEW_INCLUDES = """#include <nuttx/config.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
"""

OLD_MAIN = """int main(int argc, FAR char *argv[])
{
  printf(\"Hello, World!!\\n\");
  return 0;
}
"""

NEW_MAIN = """int main(int argc, FAR char *argv[])
{
  if (argc > 1 && strcmp(argv[1], \"pmpfault\") == 0)
    {
      volatile const uint32_t *forbidden =
        (volatile const uint32_t *)(uintptr_t)0x80000000u;
      uint32_t value;

      printf(\"P3_FAULT_BEGIN address=0x80000000\\n\");
      fflush(stdout);
      value = *forbidden;
      printf(\"P3_FAULT_SURVIVED value=0x%08lx\\n\", (unsigned long)value);
      return 99;
    }

  printf(\"Hello, World!!\\n\");
  return 0;
}
"""


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(
            f"P3-B overlay FAIL: expected exactly one {label}, found {count}"
        )
    return text.replace(old, new, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apps_root", type=Path)
    args = parser.parse_args()

    hello = args.apps_root.resolve() / "examples/hello/hello_main.c"
    if not hello.is_file():
        raise SystemExit(f"P3-B overlay FAIL: pinned hello source is missing: {hello}")

    text = hello.read_text(encoding="utf-8")
    text = replace_once(text, OLD_INCLUDES, NEW_INCLUDES, "hello include anchor")
    text = replace_once(text, OLD_MAIN, NEW_MAIN, "hello main anchor")
    hello.write_text(text, encoding="utf-8")

    generated = hello.read_text(encoding="utf-8")
    for fragment in (
        MARKER,
        'strcmp(argv[1], "pmpfault") == 0',
        "volatile const uint32_t *forbidden",
        "(uintptr_t)0x80000000u",
        "P3_FAULT_SURVIVED",
        'printf("Hello, World!!\\n")',
    ):
        if fragment not in generated:
            raise SystemExit(f"P3-B overlay FAIL: generated probe is missing {fragment}")

    print(f"P3-B fault probe overlay PASS: {hello}")
    print("  normal hello path preserved")
    print("  hello pmpfault performs one volatile U-mode load from 0x80000000")


if __name__ == "__main__":
    main()
