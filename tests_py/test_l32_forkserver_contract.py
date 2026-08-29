from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAKEFILE = ROOT / "Makefile.l32-linux-boot"
RUNNER = ROOT / "sim" / "opensbi_forkserver_main.cpp"
PROFILE_AUDIT = ROOT / "tools" / "ci" / "riscv_elf_profile.py"


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
        child_run = text.index("const int rc = runCase(")
        self.assertLess(ready, prepare)
        self.assertLess(prepare, child_run)
        self.assertIn("if (pid == 0)", text)
        self.assertIn("_exit(rc);", text)

    def test_runner_has_explicit_c_peer_without_changing_default_top(self):
        text = RUNNER.read_text()
        for required in (
            "#ifdef AETHERCORE_L32_C_TOP",
            '#include "VAetherCoreOpenSbiCSimTop.h"',
            "using OpenSbiTop = VAetherCoreOpenSbiCSimTop;",
            '#include "VAetherCoreOpenSbiSimTop.h"',
            "using OpenSbiTop = VAetherCoreOpenSbiSimTop;",
            "void record(OpenSbiTop& top, Counts& c)",
            "bool cycle(OpenSbiTop& top",
            "int runCase(OpenSbiTop& top",
            "OpenSbiTop top{&ctx};",
        ):
            self.assertIn(required, text)

    def test_per_case_userspace_compressed_oracle_is_address_bounded(self):
        text = RUNNER.read_text()
        for required in (
            "kUserspaceAddressLimit = 0x80000000ULL",
            "top.io_commit_instBytes",
            "top.io_commit_pc",
            "userspaceCompressed",
            "pc < kUserspaceAddressLimit",
            "startUserspaceCompressed",
            "userCompressedDelta",
            "requireUserspaceCompressed",
            "L32_FORKSERVER_USER_COMPRESSED_MISSING",
            "L32_FORKSERVER_USER_COMPRESSED_PASS",
            "delta-userspace-compressed=",
        ):
            self.assertIn(required, text)
        self.assertIn("if (requireUserspaceCompressed && userCompressedDelta == 0)", text)
        self.assertIn("return 13;", text)

    def test_userspace_runtime_address_bound_has_static_elf_support(self):
        audit = PROFILE_AUDIT.read_text()
        for required in (
            "def executable_load_end(data: bytes) -> int:",
            "PT_LOAD = 1",
            "PF_X = 0x1",
            'parser.add_argument("--max-exec-vaddr-exclusive"',
            "exec_vaddr_end = executable_load_end(data)",
            "executable PT_LOAD end",
            "exec_vaddr_end=",
            "exec_vaddr_limit=",
        ):
            self.assertIn(required, audit)

    def test_makefile_builds_forkserver_single_threaded_and_opt_in_is_fail_closed(self):
        text = MAKEFILE.read_text()
        for required in (
            "RTL_DIR ?= $(BUILD_DIR)/rtl",
            "FORKSERVER_OBJ_DIR := $(BUILD_DIR)/obj-forkserver",
            "FORKSERVER_RUN_DEPS ?= forkserver-sim",
            "FORKSERVER_BATCH_FILE ?=",
            "FORKSERVER_TRIGGER ?= L32 BUSYBOX SHELL READY",
            "FORKSERVER_BOOT_MAX_CYCLES ?= 450000000",
            "FORKSERVER_CASE_MAX_CYCLES ?= 50000000",
            "FORKSERVER_REQUIRE_USER_COMPRESSED ?= 0",
            "forkserver-sim: rtl forkserver-sim-existing-rtl",
            "forkserver-sim-existing-rtl:",
            "sim/opensbi_forkserver_main.cpp",
            "forkserver-local: $(FORKSERVER_RUN_DEPS)",
            '"$(FORKSERVER_REQUIRE_USER_COMPRESSED)"',
            "L32_FORKSERVER_USER_COMPRESSED_PASS",
            "C forkserver returned without per-case userspace compressed evidence",
            "L32_FORKSERVER_READY",
            "L32_FORKSERVER_PASS",
        ):
            self.assertIn(required, text)
        forkserver_block = text[text.index("forkserver-sim: rtl"):text.index("run-existing:")]
        self.assertNotIn("$(VERILATOR_THREAD_FLAGS)", forkserver_block)


if __name__ == "__main__":
    unittest.main()
