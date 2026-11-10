"""Fail closed if the separate getty candidate can affect the signed RTL run.

This test does not claim a QEMU or Product RTL login outcome.  It ensures the
candidate test is restricted to the one repair branch and reports QEMU-only.
"""

from pathlib import Path
import unittest
from tools.ci.product_sd1_getty_candidate_uart_proof import ordered_service_login


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-getty-candidate-qemu.yml"


class CandidateWorkflowContract(unittest.TestCase):
    def test_only_the_separate_candidate_push_can_launch(self):
        source = WORKFLOW.read_text()
        self.assertIn("  push:\n    branches:\n      - fix/sd1-udev-getty-timeout-20261110", source)
        self.assertIn("github.repository == 'yituanxing/minicore'", source)
        self.assertIn("github.ref == 'refs/heads/fix/sd1-udev-getty-timeout-20261110'", source)
        self.assertIn("contains(github.event.head_commit.message, '[sd1-getty-qemu-run]')", source)
        self.assertNotIn("  pull_request:", source)
        self.assertNotIn("  workflow_dispatch:", source)
        self.assertIn("cancel-in-progress: false", source)

    def test_qemu_is_explicitly_not_rtl_and_media_is_not_uploaded(self):
        source = WORKFLOW.read_text()
        self.assertIn("CANDIDATE_QEMU_ORDERED_GETTY_LOGIN_PASS_NOT_RTL", source)
        self.assertIn("RV64_SD1_QEMU_TIMEOUT_SECONDS: '540'", source)
        self.assertIn("build/rv64-debian-sd1-sd/result.txt", source)
        self.assertNotIn("AETHERCORE_SD1_LONGRUN_CONFIRM: 'YES'", source)
        uploads = source.split("uses: actions/upload-artifact@v4", 1)[1]
        self.assertNotIn("sd-rootfs.img", uploads)
        self.assertNotIn("partition-rootfs.ext4", uploads)

    def test_ordered_login_needs_real_serial_getty_service(self):
        getty = b"[  OK  ] Started \x1b[0;1;39mserial-getty@ttyS0.service\x1b[0m - Serial Getty on ttyS0.\r\n"
        login = b"localhost login: "
        self.assertTrue(ordered_service_login(getty + login))
        self.assertFalse(ordered_service_login(login))
        self.assertFalse(ordered_service_login(login + getty))
        self.assertFalse(ordered_service_login(getty + b"No login here"))

    def test_historical_native_oracle_uart_has_ordered_getty_and_login(self):
        reference = ROOT / "docs/evidence/sd1-native-qemu-42207139595/boot-uart.log"
        self.assertTrue(ordered_service_login(reference.read_bytes()))


if __name__ == "__main__":
    unittest.main()
