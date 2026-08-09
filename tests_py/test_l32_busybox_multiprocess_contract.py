from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/l32-busybox-multiprocess.yml"
FREEZE = ROOT / "tools/ci/l32_busybox_runtime_freeze.sh"


class L32BusyBoxMultiprocessContract(unittest.TestCase):
    def test_runtime_reuses_only_qualified_frozen_payload(self):
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
            "L32 BusyBox Multiprocess",
            "Linux 6.6.143 + BusyBox ash pipe/fork/exec/wait",
            "Verify exact frozen BusyBox Linux payload",
            "Run real BusyBox pipeline with child shell over ttyS0",
            "printf 'PIPE_TOKEN\\n' | /bin/sh -c",
            'read x; [ "$x" = PIPE_TOKEN ]',
            "L32 BUSYBOX PIPE CHILD OK",
            "L32 BUSYBOX PIPE PARENT OK",
            'MILESTONE="L32 BUSYBOX PIPE PARENT OK"',
            "MIN_INTERRUPTS=1",
            "MIN_SEIP=1",
            'UART_TRIGGER="L32 BUSYBOX SHELL READY"',
            'UART_COMMAND="$uart_command"',
            "L32_UART_INPUT_COMPLETE",
        ):
            if required == "L32_UART_INPUT_COMPLETE":
                # The Makefile is the runner contract and already checks this.
                makefile = (ROOT / "Makefile.l32-linux-boot").read_text()
                self.assertIn(required, makefile)
            else:
                self.assertIn(required, text)

        child = text.index("L32 BUSYBOX PIPE CHILD OK")
        parent = text.index("L32 BUSYBOX PIPE PARENT OK")
        self.assertLess(child, parent)

    def test_stage_does_not_rebuild_the_frozen_userspace(self):
        text = WORKFLOW.read_text()
        self.assertNotIn("l32_busybox_build.sh", text)
        self.assertNotIn("l32_busybox_initramfs_build.sh", text)
        self.assertNotIn("l32_busybox_payload_build.sh", text)
        self.assertIn("clean: false", text)
        self.assertIn("tools/ci/l32_busybox_runtime_freeze.sh", text)


if __name__ == "__main__":
    unittest.main()
