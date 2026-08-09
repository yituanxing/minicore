from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/l32-busybox-build.yml"
FREEZE = ROOT / "tools/ci/l32_busybox_runtime_freeze.sh"
MAKEFILE = ROOT / "Makefile.l32-linux-boot"


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
            'read x; [ "$$x" = PIPE_TOKEN ]',
            'printf "L32 BUSYBOX PIPE CHILD %s\\n" OK',
            "printf 'L32 BUSYBOX PIPE PARENT %s\\n' OK",
            'MILESTONE="L32 BUSYBOX PIPE PARENT OK"',
            "MIN_INTERRUPTS=1",
            "MIN_SEIP=1",
            'UART_TRIGGER="L32 BUSYBOX SHELL READY"',
            'UART_COMMAND="$L32_BUSYBOX_PIPE_COMMAND"',
            "grep -qx 'L32 BUSYBOX PIPE CHILD OK'",
            "grep -qx 'L32 BUSYBOX PIPE PARENT OK'",
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
        self.assertIn("$$x", command)

        makefile = MAKEFILE.read_text()
        for required in (
            "L32_UART_INPUT_COMPLETE",
            "L32_UART_RX_INTERRUPT",
            "L32_UART_INPUT_SEIP",
        ):
            self.assertIn(required, makefile)

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
