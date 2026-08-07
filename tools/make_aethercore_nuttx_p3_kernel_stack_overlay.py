#!/usr/bin/env python3
"""Enable NuttX's existing RISC-V kernel-stack machinery without an MMU addrenv.

Pinned NuttX 13.0.0 already switches between the user and per-task kernel
stack in riscv_exception_common.S whenever CONFIG_ARCH_KERNEL_STACK is set.
The allocator itself is also ordinary kernel-heap storage.  What prevents the
pure protected/PMP configuration from using that machinery is a set of source
and prototype guards that unnecessarily require CONFIG_ARCH_ADDRENV as well.
The pinned arch/Kconfig also places ARCH_KERNEL_STACK inside the broader
ARCH_ADDRENV && ARCH_NEED_ADDRENV_MAPPING menu guard even though the option
itself only depends on BUILD_KERNEL || BUILD_PROTECTED.

P3 changes only those storage/accounting guards and opens a narrow Kconfig
window for ARCH_KERNEL_STACK.  Address-environment join, switch, leave, MMU,
mapping and S-mode paths are deliberately untouched.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re


DUAL_GUARD = "#if defined(CONFIG_ARCH_ADDRENV) && defined(CONFIG_ARCH_KERNEL_STACK)"
KSTACK_GUARD = "#ifdef CONFIG_ARCH_KERNEL_STACK"
DUAL_GUARD_RE = re.compile(
    r"#if[ \t]+defined\s*\(\s*CONFIG_ARCH_ADDRENV\s*\)\s*&&\s*"
    r"defined\s*\(\s*CONFIG_ARCH_KERNEL_STACK\s*\)"
)


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"P3 overlay FAIL: expected exactly one {label} anchor in {path}, found {count}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_kconfig_visibility(path: Path) -> None:
    """Expose only ARCH_KERNEL_STACK outside the pinned ADDRENV mapping menu.

    NuttX 13.0.0 nests ARCH_KERNEL_STACK below
    ``if ARCH_ADDRENV && ARCH_NEED_ADDRENV_MAPPING``.  Pure PMP protected
    builds intentionally keep ARCH_ADDRENV disabled, so olddefconfig removes
    the requested kernel-stack option before any C/assembly hardening can be
    built.  Close the outer guard immediately before the kernel-stack block and
    reopen it immediately afterwards.  Everything else remains under the
    original ADDRENV/mapping condition.
    """

    replace_once(
        path,
        "endif # ARCH_STACK_DYNAMIC\n\nconfig ARCH_KERNEL_STACK",
        "endif # ARCH_STACK_DYNAMIC\n\n"
        "endif # ARCH_ADDRENV && ARCH_NEED_ADDRENV_MAPPING\n\n"
        "config ARCH_KERNEL_STACK",
        "kernel-stack Kconfig opening boundary",
    )
    replace_once(
        path,
        "endif # ARCH_KERNEL_STACK\n\nconfig ARCH_PGPOOL_MAPPING",
        "endif # ARCH_KERNEL_STACK\n\n"
        "if ARCH_ADDRENV && ARCH_NEED_ADDRENV_MAPPING\n\n"
        "config ARCH_PGPOOL_MAPPING",
        "kernel-stack Kconfig closing boundary",
    )


def patch_prototype(path: Path, function: str) -> None:
    """Patch only the documented declaration block for one kstack API.

    Apache release tarballs and git snapshots may differ in whitespace or
    trailing preprocessor comments.  Matching the whole three-line
    guard/prototype/endif sequence was therefore needlessly brittle.  Keep the
    operation fail-closed by first isolating the named API documentation block,
    then require exactly one ADDRENV+KSTACK guard and exactly one expected
    prototype inside that block.
    """

    text = path.read_text(encoding="utf-8")
    marker = f" * Name: {function}\n"
    marker_pos = text.find(marker)
    if marker_pos < 0:
        raise SystemExit(
            f"P3 overlay FAIL: missing {function} documentation block in {path}"
        )
    next_block = text.find("/****************************************************************************", marker_pos + len(marker))
    if next_block < 0:
        raise SystemExit(
            f"P3 overlay FAIL: unterminated {function} declaration block in {path}"
        )

    block = text[marker_pos:next_block]
    prototype_re = re.compile(
        rf"int\s+{re.escape(function)}\s*\(\s*FAR\s+struct\s+tcb_s\s*\*\s*tcb\s*\)\s*;"
    )
    prototype_matches = list(prototype_re.finditer(block))
    guard_matches = list(DUAL_GUARD_RE.finditer(block))
    if len(prototype_matches) != 1 or len(guard_matches) != 1:
        preview = "\\n".join(block.splitlines()[-12:])
        raise SystemExit(
            "P3 overlay FAIL: expected one protected kernel-stack guard and "
            f"one {function} prototype in {path}; guards={len(guard_matches)} "
            f"prototypes={len(prototype_matches)}; block-tail=\\n{preview}"
        )

    patched_block = DUAL_GUARD_RE.sub(KSTACK_GUARD, block, count=1)
    patched = text[:marker_pos] + patched_block + text[next_block:]
    path.write_text(patched, encoding="utf-8")


def patch_alloc_call(path: Path, call_prefix: str, label: str) -> None:
    old = f"{DUAL_GUARD}\n  /* Allocate the kernel stack */\n\n{call_prefix}"
    new = (
        f"{KSTACK_GUARD}\n"
        "  /* Pure protected/PMP tasks still need a trusted kernel stack. */\n\n"
        f"{call_prefix}"
    )
    replace_once(path, old, new, label)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("nuttx", type=Path, help="extracted pinned NuttX source tree")
    args = parser.parse_args()
    root = args.nuttx.resolve()

    required = [
        root / "include/nuttx/arch.h",
        root / "arch/risc-v/src/common/riscv_addrenv_kstack.c",
        root / "sched/task/task_init.c",
        root / "sched/task/task_fork.c",
        root / "sched/pthread/pthread_create.c",
        root / "sched/sched/sched_releasetcb.c",
    ]
    for path in required:
        if not path.is_file():
            raise SystemExit(f"P3 overlay FAIL: required pinned source is missing: {path}")

    arch_h, kstack_c, task_init, task_fork, pthread_create, release_tcb = required

    # The real pinned build always has arch/Kconfig.  Keep synthetic unit
    # fixtures from having to reproduce the whole Kconfig tree, but fail closed
    # whenever a real configured NuttX tree is being patched.
    arch_kconfig = root / "arch/Kconfig"
    if arch_kconfig.is_file():
        patch_kconfig_visibility(arch_kconfig)
    elif (root / ".config").is_file():
        raise SystemExit(
            f"P3 overlay FAIL: configured pinned source is missing: {arch_kconfig}"
        )

    # Keep the historical up_addrenv_* names to minimize the pinned upstream
    # patch surface.  The functions themselves only allocate/free kernel heap.
    patch_prototype(arch_h, "up_addrenv_kstackalloc")
    patch_prototype(arch_h, "up_addrenv_kstackfree")

    replace_once(
        kstack_c,
        f'{DUAL_GUARD}\n\n/****************************************************************************\n * Public Functions',
        f'{KSTACK_GUARD}\n\n/****************************************************************************\n * Public Functions',
        "RISC-V kernel-stack implementation guard",
    )
    replace_once(
        kstack_c,
        "#endif /* CONFIG_ARCH_ADDRENV && CONFIG_ARCH_KERNEL_STACK */",
        "#endif /* CONFIG_ARCH_KERNEL_STACK */",
        "RISC-V kernel-stack implementation end guard",
    )

    patch_alloc_call(
        task_init,
        "  if (ttype != TCB_FLAG_TTYPE_KERNEL)\n",
        "task kernel-stack allocation guard",
    )
    patch_alloc_call(
        task_fork,
        "  if (ttype != TCB_FLAG_TTYPE_KERNEL)\n",
        "fork kernel-stack allocation guard",
    )
    patch_alloc_call(
        pthread_create,
        "  ret = up_addrenv_kstackalloc(ptcb);\n",
        "pthread kernel-stack allocation guard",
    )

    old_release = (
        f"{DUAL_GUARD}\n"
        "      /* Release the kernel stack */\n\n"
        "      up_addrenv_kstackfree(tcb);\n"
        "#endif"
    )
    new_release = (
        f"{KSTACK_GUARD}\n"
        "      /* Release the independent protected kernel stack. */\n\n"
        "      up_addrenv_kstackfree(tcb);\n"
        "#endif"
    )
    replace_once(
        release_tcb,
        old_release,
        new_release,
        "kernel-stack release guard",
    )

    # Fail closed if this focused overlay accidentally changed the real
    # address-environment lifecycle.  Those operations must remain conditional
    # on CONFIG_ARCH_ADDRENV and are outside the PMP-only P3 milestone.
    lifecycle_checks = {
        task_fork: "#if defined(CONFIG_ARCH_ADDRENV)\n  /* Join the parent address environment */",
        pthread_create: "#ifdef CONFIG_ARCH_ADDRENV\n  /* Share the address environment of the parent task group. */",
        release_tcb: "#ifdef CONFIG_ARCH_ADDRENV\n      /* Release this thread's reference to the address environment */",
    }
    for path, fragment in lifecycle_checks.items():
        if fragment not in path.read_text(encoding="utf-8"):
            raise SystemExit(
                f"P3 overlay FAIL: address-environment lifecycle boundary changed unexpectedly in {path}"
            )

    print("P3 kernel-stack overlay PASS")
    print("  CONFIG_ARCH_KERNEL_STACK is visible to pure BUILD_PROTECTED configurations")
    print("  CONFIG_ARCH_KERNEL_STACK may allocate/free per-task kernel stacks without ARCH_ADDRENV")
    print("  addrenv join/switch/leave, mapping, MMU and S-mode behavior remain untouched")


if __name__ == "__main__":
    main()
