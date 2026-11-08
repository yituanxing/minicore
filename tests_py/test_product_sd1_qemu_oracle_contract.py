"""Offline, no-guest execution contracts for SD1 native-Debian QEMU oracle."""

from pathlib import Path
import importlib.util
import unittest


ROOT = Path(__file__).resolve().parents[1]
PREPARE = ROOT / "tools/ci/product_sd1_qemu_init_oracle.sh"
WATCH = ROOT / "tools/ci/product_sd1_qemu_watch.py"

spec = importlib.util.spec_from_file_location("product_sd1_qemu_watch", WATCH)
qemu_watch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qemu_watch)


class ProductSd1QemuOracleContract(unittest.TestCase):
    def test_qemu_virt_is_distinct_from_product_spi_and_keeps_disk_immutable(self):
        script = PREPARE.read_text()
        watch = WATCH.read_text()
        self.assertIn("-e VIRTIO -e VIRTIO_MMIO -e VIRTIO_BLK", script)
        self.assertIn("CONFIG_${setting}=y", script)
        self.assertIn("root=/dev/vda1", watch)
        self.assertIn("virtio-blk-device,drive=sd1", watch)
        self.assertIn("snapshot=on", watch)
        self.assertIn('"-bios", "default"', watch)
        self.assertIn("QEMU_VIRT_ONLY_NOT_PRODUCT_SPI_RTL", script)
        self.assertIn("PRODUCT_SD1_QEMU_NATIVE_DEBIAN_INIT_LOGIN_PROMPT_PASS_NOT_RTL", watch)
        self.assertIn("sha256sum --check --status", script)
        self.assertIn("refusing to overwrite", script)
        self.assertNotIn("MILESTONE='login:'", script)
        self.assertNotIn("run-existing", script)

    def test_native_init_and_login_require_mounted_root_in_order(self):
        proof = qemu_watch.BootProof()
        proof.observe(b"Linux boot\nRun /sbin/init as init process\n")
        proof.observe(b"test login: ")
        self.assertFalse(proof.login_prompt)
        proof.observe(b"VFS: Mounted root (ext4 filesystem) readonly\n")
        self.assertFalse(proof.pid1_executed)
        proof.observe(b"Run /sbin/init as init proc")
        self.assertFalse(proof.pid1_executed)
        proof.observe(b"ess\nDebian GNU/Linux trixie\ndebian login: ")
        self.assertTrue(proof.root_mounted)
        self.assertTrue(proof.pid1_executed)
        self.assertTrue(proof.login_prompt)

    def test_login_without_root_or_real_init_never_passes(self):
        for fragments in (
            [b"login: "],
            [b"Run /sbin/init as init process\nlogin: "],
            [b"VFS: Mounted root (ext4 filesystem)\nlogin: "],
            [b"VFS: Mounted root (ext4 filesystem)\n", b"login: ",
             b"Run /sbin/init as init process\n"],
        ):
            with self.subTest(fragments=fragments):
                proof = qemu_watch.BootProof()
                for chunk in fragments:
                    proof.observe(chunk)
                self.assertFalse(proof.login_prompt)

    def test_kernel_panic_is_fatal_even_when_login_marker_exists(self):
        proof = qemu_watch.BootProof()
        proof.observe(b"VFS: Mounted root (ext4 filesystem)\n")
        proof.observe(b"Run /sbin/init as init process\n")
        proof.observe(b"Kernel panic - not syncing\n")
        self.assertIsNotNone(proof.failure)
        self.assertFalse(proof.login_prompt)

    def test_qemu_uart_phase_timeline_is_ordered_and_host_only(self):
        proof = qemu_watch.BootProof()
        proof.observe(b"Started fake.service\nlogin: ", 0.1)
        self.assertFalse(proof.phase_wall_seconds)
        proof.observe(b"VFS: Mounted root (ext4 filesystem)\n", 0.4)
        proof.observe(b"Run /sbin/init as init process\n", 0.5)
        proof.observe(b"\x1b[0;32m Started \x1b[0m systemd-journald.ser", 1.2)
        self.assertNotIn("first_started_service", proof.phase_wall_seconds)
        proof.observe(b"vice - Journal Service.\r\n", 1.3)
        proof.observe(b"localhost login: ", 2.0)
        self.assertEqual(proof.phase_wall_seconds, {
            "root_mount": 0.4,
            "native_pid1": 0.5,
            "first_started_service": 1.3,
            "login": 2.0,
        })
        timeline = qemu_watch.phase_result_lines(proof, 2.0)
        self.assertIn("phase_root_mount_of_login_wall_percent=20.000", timeline)
        self.assertIn("phase_first_started_service_of_login_wall_percent=65.000", timeline)
        self.assertIn("HOST_MONOTONIC_UART_OBSERVATION_NOT_GUEST_ICOUNT", timeline)
        with self.assertRaisesRegex(ValueError, "nonpositive"):
            qemu_watch.phase_result_lines(proof, 0)

    def test_missing_service_marker_is_not_invented(self):
        proof = qemu_watch.BootProof()
        proof.observe(b"VFS: Mounted root (ext4 filesystem)\n", 0.3)
        proof.observe(b"Run /sbin/init as init process\n", 0.4)
        proof.observe(b"localhost login: ", 1.0)
        self.assertTrue(proof.login_prompt)
        self.assertIn("phase_first_started_service=NOT_OBSERVED",
                      qemu_watch.phase_result_lines(proof, 1.0))


if __name__ == "__main__":
    unittest.main()
