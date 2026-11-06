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


if __name__ == "__main__":
    unittest.main()
