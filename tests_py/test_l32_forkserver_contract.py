from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAKEFILE = ROOT / "Makefile.l32-linux-boot"
RUNNER = ROOT / "sim/opensbi_forkserver_main.cpp"


class L32ForkserverContract(unittest.TestCase):
    def test_runner_uses_verilator_clone_hooks_around_posix_fork(self):
        text = RUNNER.read_text()
        for required in (
            "top.prepareClone();",
            "const pid_t pid = ::fork();",
            "top.atClone();",
            "::waitpid(pid, &status, 0)",
            "L32_FORKSERVER_READY",
            "L32_FORKSERVER_CASE_START",
            "L32_FORKSERVER_CASE_PASS",
            "L32_FORKSERVER_CASE_TIMEOUT",
            "L32_FORKSERVER_RESULT",
            "L32_FORKSERVER_PASS",
            "L32_FORKSERVER_INPUT_SEIP",
            "L32_FORKSERVER_RX_INTERRUPT",
        ):
            self.assertIn(required, text)
        self.assertLess(text.index("top.prepareClone();"), text.index("const pid_t pid = ::fork();"))
        self.assertLess(text.index("const pid_t pid = ::fork();"), text.index("top.atClone();"))

    def test_forkserver_keeps_parent_at_warm_shell_boundary(self):
        text = RUNNER.read_text()
        ready = text.index("L32_FORKSERVER_READY")
        prepare = text.index("top.prepareClone();")
        child_run = text.index("runCase(top, ctx, mem, w, cycles, counts")
        self.assertLess(ready, prepare)
        self.assertLess(prepare, child_run)
        self.assertIn("if (pid == 0)", text)
        self.assertIn("_exit(rc);", text)

    def test_makefile_builds_forkserver_single_threaded(self):
        text = MAKEFILE.read_text()
        for required in (
            "FORKSERVER_OBJ_DIR := $(BUILD_DIR)/obj-forkserver",
            "FORKSERVER_BATCH_FILE ?=",
            "FORKSERVER_TRIGGER ?= L32 BUSYBOX SHELL READY",
            "FORKSERVER_BOOT_MAX_CYCLES ?= 450000000",
            "FORKSERVER_CASE_MAX_CYCLES ?= 50000000",
            "forkserver-sim: rtl",
            "sim/opensbi_forkserver_main.cpp",
            "forkserver-local: forkserver-sim",
            "L32_FORKSERVER_READY",
            "L32_FORKSERVER_PASS",
        ):
            self.assertIn(required, text)
        forkserver_block = text[text.index("forkserver-sim: rtl"):text.index("run-existing:")]
        self.assertNotIn("$(VERILATOR_THREAD_FLAGS)", forkserver_block)


if __name__ == "__main__":
    unittest.main()
