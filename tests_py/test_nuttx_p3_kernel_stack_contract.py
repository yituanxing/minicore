from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "tools" / "make_aethercore_nuttx_p3_kernel_stack_overlay.py"
BUILD = ROOT / "tools" / "ci" / "nuttx_p3_kernel_stack_build.sh"

DUAL = "#if defined(CONFIG_ARCH_ADDRENV) && defined(CONFIG_ARCH_KERNEL_STACK)"
SINGLE = "#ifdef CONFIG_ARCH_KERNEL_STACK"


class NuttxP3KernelStackContractTest(unittest.TestCase):
    def test_overlay_is_narrow_and_does_not_claim_addrenv(self) -> None:
        text = OVERLAY.read_text()
        for fragment in (
            "include/nuttx/arch.h",
            "arch/risc-v/src/common/riscv_addrenv_kstack.c",
            "sched/task/task_init.c",
            "sched/task/task_fork.c",
            "sched/pthread/pthread_create.c",
            "sched/sched/sched_releasetcb.c",
            "up_addrenv_kstackalloc",
            "up_addrenv_kstackfree",
            "CONFIG_ARCH_KERNEL_STACK may allocate/free per-task kernel stacks without ARCH_ADDRENV",
            "addrenv join/switch/leave, MMU and S-mode behavior remain untouched",
        ):
            self.assertIn(fragment, text)

        # P3 must not invent a fake address environment or bypass exception
        # recovery.  Configuration and runtime qualification are later gates.
        for forbidden in (
            "CONFIG_ARCH_USE_S_MODE=y",
            "CONFIG_ARCH_USE_MMU=y",
            "CONFIG_ARCH_ADDRENV=y",
            "PANIC_WITH_REGS",
            "SIGSEGV",
        ):
            self.assertNotIn(forbidden, text)

    def test_p3a_build_fails_closed_before_runtime_claims(self) -> None:
        text = BUILD.read_text()
        for fragment in (
            "build/nuttx-p1/evidence/result.txt",
            "make_aethercore_nuttx_p3_kernel_stack_overlay.py",
            '"CONFIG_ARCH_KERNEL_STACK": "y"',
            '"CONFIG_ARCH_ADDRENV": None',
            '"CONFIG_ARCH_USE_MMU": None',
            '"CONFIG_ARCH_USE_S_MODE": None',
            "make olddefconfig",
            "olddefconfig removed CONFIG_ARCH_KERNEL_STACK=y",
            "make clean",
            "up_addrenv_kstackalloc",
            "up_addrenv_kstackfree",
            "riscv_exception",
            "exception_common",
            "riscv_percpu_set_kstack",
            "p3a-independent-kernel-stack-build-v1",
            "runtime=not-yet-qualified",
            "fault_isolation=not-yet-qualified",
        ):
            self.assertIn(fragment, text)
        self.assertIn('KSTACK_SIZE="${AETHERCORE_NUTTX_P3_KSTACK_SIZE:-1568}"', text)
        self.assertIn("address_environment=disabled", text)
        self.assertIn("mmu=disabled", text)
        self.assertIn("supervisor_mode=disabled", text)
        self.assertNotIn("pmpfault", text)
        self.assertNotIn("SIGSEGV", text)

    def test_overlay_rewrites_only_kernel_stack_storage_guards(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def put(relative: str, content: str) -> None:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(textwrap.dedent(content).lstrip())

            put(
                "include/nuttx/arch.h",
                f"""
                {DUAL}
                int up_addrenv_kstackalloc(FAR struct tcb_s *tcb);
                #endif

                {DUAL}
                int up_addrenv_kstackfree(FAR struct tcb_s *tcb);
                #endif
                """,
            )
            put(
                "arch/risc-v/src/common/riscv_addrenv_kstack.c",
                f"""
                #include <nuttx/arch.h>
                #include "addrenv.h"
                #include "riscv_internal.h"

                {DUAL}

                /****************************************************************************
                 * Public Functions
                 ****************************************************************************/
                int up_addrenv_kstackalloc(struct tcb_s *tcb) {{ return 0; }}
                int up_addrenv_kstackfree(struct tcb_s *tcb) {{ return 0; }}
                #endif /* CONFIG_ARCH_ADDRENV && CONFIG_ARCH_KERNEL_STACK */
                """,
            )
            put(
                "sched/task/task_init.c",
                f"""
                {DUAL}
                  /* Allocate the kernel stack */

                  if (ttype != TCB_FLAG_TTYPE_KERNEL)
                    {{
                      ret = up_addrenv_kstackalloc(tcb);
                    }}
                #endif
                """,
            )
            put(
                "sched/task/task_fork.c",
                f"""
                #if defined(CONFIG_ARCH_ADDRENV)
                  /* Join the parent address environment */
                  addrenv_join(parent, child);
                #endif

                {DUAL}
                  /* Allocate the kernel stack */

                  if (ttype != TCB_FLAG_TTYPE_KERNEL)
                    {{
                      ret = up_addrenv_kstackalloc(child);
                    }}
                #endif
                """,
            )
            put(
                "sched/pthread/pthread_create.c",
                f"""
                #ifdef CONFIG_ARCH_ADDRENV
                  /* Share the address environment of the parent task group. */
                  addrenv_join(this_task(), ptcb);
                #endif

                {DUAL}
                  /* Allocate the kernel stack */

                  ret = up_addrenv_kstackalloc(ptcb);
                #endif
                """,
            )
            put(
                "sched/sched/sched_releasetcb.c",
                f"""
                {DUAL}
                      /* Release the kernel stack */

                      up_addrenv_kstackfree(tcb);
                #endif

                #ifdef CONFIG_ARCH_ADDRENV
                      /* Release this thread's reference to the address environment */
                      addrenv_leave(tcb);
                #endif
                """,
            )

            result = subprocess.run(
                ["python3", str(OVERLAY), str(root)],
                cwd=ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertIn("P3 kernel-stack overlay PASS", result.stdout)

            for relative in (
                "include/nuttx/arch.h",
                "arch/risc-v/src/common/riscv_addrenv_kstack.c",
                "sched/task/task_init.c",
                "sched/task/task_fork.c",
                "sched/pthread/pthread_create.c",
                "sched/sched/sched_releasetcb.c",
            ):
                text = (root / relative).read_text()
                self.assertNotIn(DUAL, text, relative)
                self.assertIn(SINGLE, text, relative)

            fork = (root / "sched/task/task_fork.c").read_text()
            pthread = (root / "sched/pthread/pthread_create.c").read_text()
            release = (root / "sched/sched/sched_releasetcb.c").read_text()
            self.assertIn("#if defined(CONFIG_ARCH_ADDRENV)", fork)
            self.assertIn("addrenv_join(parent, child)", fork)
            self.assertIn("#ifdef CONFIG_ARCH_ADDRENV", pthread)
            self.assertIn("addrenv_join(this_task(), ptcb)", pthread)
            self.assertIn("#ifdef CONFIG_ARCH_ADDRENV", release)
            self.assertIn("addrenv_leave(tcb)", release)

    def test_overlay_fails_closed_when_pinned_source_drifts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in (
                "include/nuttx/arch.h",
                "arch/risc-v/src/common/riscv_addrenv_kstack.c",
                "sched/task/task_init.c",
                "sched/task/task_fork.c",
                "sched/pthread/pthread_create.c",
                "sched/sched/sched_releasetcb.c",
            ):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("upstream changed\n")

            result = subprocess.run(
                ["python3", str(OVERLAY), str(root)],
                cwd=ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("P3 overlay FAIL", result.stdout)


if __name__ == "__main__":
    unittest.main()
