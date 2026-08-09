from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/l32-busybox-build.yml"
FREEZE = ROOT / "tools/ci/l32_busybox_runtime_freeze.sh"
MAKEFILE = ROOT / "Makefile.l32-linux-boot"
RUNNER = ROOT / "sim/opensbi_boot_main.cpp"


class L32BusyBoxMultiprocessContract(unittest.TestCase):
    def test_runtime_verifier_requires_qualified_exact_payload(self):
        text = FREEZE.read_text()
        for required in (
            "L32_BUSYBOX_BUILD_RESULT: status=PASS",
            "L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS",
            "L32_BUSYBOX_SHELL_PAYLOAD_BUILD_RESULT: status=PASS",
            "busybox_sha256",
            "image_sha256",
            "sha256sum -c",
            "fw_payload.bin",
            "L32_BUSYBOX_RUNTIME_FREEZE: status=PASS",
        ):
            self.assertIn(required, text)
        self.assertNotIn("l32_busybox_build.sh", text)
        self.assertNotIn("l32_busybox_initramfs_build.sh", text)
        self.assertNotIn("l32_busybox_payload_build.sh", text)

    def test_workflow_forces_pipe_child_exec_wait_and_parent_resume(self):
        text = WORKFLOW.read_text()
        for required in (
            "L32 BusyBox Shell + Multiprocess",
            "musl 1.2.5 + BusyBox 1.36.1 + Linux 6.6.143 shell/process",
            "Verify exact BusyBox Linux runtime payload",
            "Run real Linux BusyBox multiprocess pipeline over ttyS0",
            "printf 'PIPE_TOKEN\\n' | /bin/sh -c",
            'read x; case "$x" in PIPE_TOKEN)',
            'printf "L32 BUSYBOX PIPE CHILD %s\\n" OK',
            "*) exit 1;; esac",
            "printf 'L32 BUSYBOX PIPE PARENT %s\\n' OK",
            'MILESTONE="L32 BUSYBOX PIPE PARENT OK"',
            "MIN_INTERRUPTS=1",
            "MIN_SEIP=1",
            'UART_TRIGGER="L32 BUSYBOX SHELL READY"',
            'printf \'%s\\n\' "$L32_BUSYBOX_PIPE_COMMAND" > "$command_file"',
            'UART_COMMAND_FILE="$command_file"',
            "grep -Fxq 'L32 BUSYBOX PIPE CHILD OK'",
            "grep -Fxq 'L32 BUSYBOX PIPE PARENT OK'",
            "grep -q '^L32_UART_INPUT_COMPLETE '",
            "grep -q '^L32_UART_RX_INTERRUPT '",
            "grep -q '^L32_UART_INPUT_SEIP '",
        ):
            self.assertIn(required, text)

        command_match = re.search(
            r"L32_BUSYBOX_PIPE_COMMAND: >-\n\s+(.+)", text
        )
        self.assertIsNotNone(command_match)
        command = command_match.group(1)
        self.assertNotIn("L32 BUSYBOX PIPE CHILD OK", command)
        self.assertNotIn("L32 BUSYBOX PIPE PARENT OK", command)
        self.assertIn("$x", command)
        self.assertNotIn("$$x", command)
        self.assertNotIn('[ "$x" = PIPE_TOKEN ]', command)
        self.assertIn('case "$x" in PIPE_TOKEN)', command)

        makefile = MAKEFILE.read_text()
        for required in (
            "VERILATOR_MODEL_OPT ?= -O3",
            "VERILATOR_THREADS ?= 1",
            "VERILATOR_THREAD_FLAGS := $(if $(filter 1,$(VERILATOR_THREADS)),,--threads $(VERILATOR_THREADS))",
            'VERILATOR_MAKE_OPT ?= -MAKEFLAGS "OPT_FAST=-O3 OPT_GLOBAL=-O3"',
            "SIM_CXXFLAGS ?= -std=c++20 -O3 -march=native",
            "$(VERILATOR) $(VERILATOR_MODEL_OPT) $(VERILATOR_THREAD_FLAGS) $(VERILATOR_MAKE_OPT) --cc --exe --build",
            '-CFLAGS "$(SIM_CXXFLAGS)"',
            "UART_COMMAND_FILE ?=",
            "POST_INPUT_MAX_CYCLES ?= 0",
            "PROGRESS_INTERVAL_CYCLES ?= 25000000",
            "RUN_DEPS ?= sim",
            "run-existing:",
            'missing existing L32 simulator $(OBJ_DIR)/V$(TOP); run run-local once first',
            "RUN_DEPS= run-local",
            "run-local: $(RUN_DEPS)",
            "export AETHERCORE_UART_COMMAND := $(UART_COMMAND)",
            'uart_command="$${AETHERCORE_UART_COMMAND-}"',
            'uart_command="$$(cat "$(UART_COMMAND_FILE)")"',
            '"$$uart_command" "$(POST_INPUT_MAX_CYCLES)" "$(PROGRESS_INTERVAL_CYCLES)"',
            "L32_UART_INPUT_COMPLETE",
            "L32_UART_RX_INTERRUPT",
            "L32_UART_INPUT_SEIP",
        ):
            self.assertIn(required, makefile)

        phony_match = re.search(r"^\.PHONY:\s+(.+)$", makefile, re.MULTILINE)
        self.assertIsNotNone(phony_match)
        phony_targets = set(phony_match.group(1).split())
        for target in ("all", "rtl", "sim", "run-local", "run-existing", "clean"):
            self.assertIn(target, phony_targets)

        self.assertNotIn('"$(UART_COMMAND)" 2>&1', makefile)
        self.assertNotIn("--x-assign fast", makefile)
        self.assertNotIn("--x-initial fast", makefile)

    def test_runtime_harness_has_bounded_post_input_fast_fail_and_hot_path_guards(self):
        text = RUNNER.read_text()
        for required in (
            "POST_INPUT_MAX_CYCLES",
            "PROGRESS_INTERVAL_CYCLES",
            "L32_SIM_PROGRESS",
            "L32_POST_INPUT_TIMEOUT",
            "read32Unchecked",
            "endsWith(uart, milestone)",
            "std::cout.put(byte)",
            "if (byte == '\\n') std::cout.flush()",
        ):
            self.assertIn(required, text)
        self.assertNotIn("std::cout << byte << std::flush", text)
        self.assertNotIn("uart.find(milestone)", text)
        self.assertNotIn("uart.find(uartTrigger)", text)
        self.assertNotIn('uart.find("OpenSBI v1.6")', text)

    def test_workflow_rebuilds_then_hash_qualifies_before_runtime(self):
        text = WORKFLOW.read_text()
        for required in (
            "tools/ci/l32_busybox_build.sh",
            "tools/ci/l32_busybox_initramfs_build.sh",
            "tools/ci/l32_busybox_payload_build.sh",
            "tools/ci/l32_busybox_runtime_freeze.sh",
            "clean: false",
        ):
            self.assertIn(required, text)

        payload = text.index("Build OpenSBI with BusyBox Linux payload")
        verify = text.index("Verify exact BusyBox Linux runtime payload")
        runtime = text.index("Run real Linux BusyBox multiprocess pipeline over ttyS0")
        self.assertLess(payload, verify)
        self.assertLess(verify, runtime)


if __name__ == "__main__":
    unittest.main()
