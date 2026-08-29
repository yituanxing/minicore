from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / "tools" / "nuttx_p3_fault_probe.py"
RUNTIME = ROOT / "tools" / "ci" / "nuttx_p3_fault_isolation.sh"


class NuttxP3FaultContractTest(unittest.TestCase):
    def test_probe_is_bounded_idempotent_and_preserves_normal_hello(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apps = Path(directory)
            hello = apps / "examples/hello/hello_main.c"
            hello.parent.mkdir(parents=True)
            hello.write_text(
                textwrap.dedent(
                    """
                    #include <nuttx/config.h>
                    #include <stdio.h>

                    int main(int argc, FAR char *argv[])
                    {
                      printf("Hello, World!!\\n");
                      return 0;
                    }
                    """
                ).lstrip()
            )

            for _ in range(2):
                result = subprocess.run(
                    ["python3", str(PROBE), str(apps)],
                    cwd=ROOT,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                )
                self.assertEqual(result.returncode, 0, result.stdout)
                self.assertIn("P3-B fault probe overlay PASS", result.stdout)

            generated = hello.read_text()
            self.assertEqual(generated.count("P3_FAULT_BEGIN"), 1)
            self.assertEqual(generated.count("P3_FAULT_SURVIVED"), 1)
            self.assertIn('strcmp(argv[1], "pmpfault") == 0', generated)
            self.assertIn("volatile const uint32_t *forbidden", generated)
            self.assertIn("(uintptr_t)0x80000000u", generated)
            self.assertIn('printf("Hello, World!!\\n")', generated)

    def test_probe_fails_closed_on_pinned_source_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apps = Path(directory)
            hello = apps / "examples/hello/hello_main.c"
            hello.parent.mkdir(parents=True)
            hello.write_text("upstream changed\n")
            result = subprocess.run(
                ["python3", str(PROBE), str(apps)],
                cwd=ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("P3-B overlay FAIL", result.stdout)

    def test_runtime_requires_precise_fault_isolation_and_recovery(self) -> None:
        text = RUNTIME.read_text()
        for fragment in (
            "build/nuttx-p3a",
            "nuttx_p3_fault_probe.py",
            "make -j\"${JOBS}\" CROSSDEV=riscv64-unknown-elf-",
            "P3_FAULT_BEGIN address=0x80000000",
            "P3_FAULT_SURVIVED",
            "cause=0x5",
            "value=0x80000000",
            "EXCEPTION: Load access fault",
            "Segmentation fault in",
            "probe -> PMP fault -> task isolation -> NSH",
            "UMODE_COMMAND_EVIDENCE",
            "PANIC!!!",
            "STALL_PERIODS=(0 3)",
            "p3b-pmp-fault-isolation-v1",
            "recovery=forced-cancel-and-return-to-nsh",
            "kernel_stack=enabled",
            "address_environment=disabled",
            "mmu=disabled",
            "supervisor_mode=disabled",
        ):
            self.assertIn(fragment, text)

        self.assertNotIn("CONFIG_ARCH_ADDRENV=y", text)
        self.assertNotIn("CONFIG_ARCH_USE_MMU=y", text)
        self.assertNotIn("CONFIG_ARCH_USE_S_MODE=y", text)


if __name__ == "__main__":
    unittest.main()
