#!/usr/bin/env python3
"""Enable NuttX's existing RISC-V kernel-stack machinery without an MMU addrenv.

Pinned NuttX 13.0.0 already switches between the user and per-task kernel
stack in riscv_exception_common.S whenever CONFIG_ARCH_KERNEL_STACK is set.
The allocator itself is also ordinary kernel-heap storage.  What prevents the
pure protected/PMP configuration from using that machinery is a set of source
and prototype guards that unnecessarily require CONFIG_ARCH_ADDRENV as well.

P3 changes only those storage/accounting guards.  Address-environment join,
switch, leave, MMU and S-mode paths are deliberately untouched.
"""

from __future__ import annotations

import argparse
from pathlib import Path


DUAL_GUARD = "#if defined(CONFIG_ARCH_ADDRENV) && defined(CONFIG_ARCH_KERNEL_STACK)"
KSTACK_GUARD = "#ifdef CONFIG_ARCH_KERNEL_STACK"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"P3 overlay FAIL: expected exactly one {label} anchor in {path}, found {count}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_prototype(path: Path, function: str) -> None:
    old = f"{DUAL_GUARD}\nint {function}(FAR struct tcb_s *tcb);\n#endif"
    new = f"{KSTACK_GUARD}\nint {function}(FAR struct tcb_s *tcb);\n#endif"
    replace_once(path, old, new, f"{function} prototype guard")


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
    print("  CONFIG_ARCH_KERNEL_STACK may allocate/free per-task kernel stacks without ARCH_ADDRENV")
    print("  addrenv join/switch/leave, MMU and S-mode behavior remain untouched")


if __name__ == "__main__":
    main()
