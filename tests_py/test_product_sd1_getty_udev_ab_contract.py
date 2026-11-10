"""Offline regression checks for a safe QEMU-only 110s udev A/B experiment."""

from pathlib import Path
import struct
import tempfile
import unittest
import shutil
import subprocess
from unittest.mock import patch

from tools.ci.product_sd1_getty_udev_ab import (
    FIRST_LBA, PARTITION_OFFSET, QualificationError,
    digest, has_serial_getty_dependency_failure, prepare_variant, validate_media,
)


class GettyUdevAbTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.partition = self.root / "partition.ext4"
        data = bytearray(2 * 1024 * 1024)
        data[1024 + 56:1024 + 58] = b"\x53\xef"
        self.partition.write_bytes(data)
        self.sd = self.root / "sd.img"
        header = bytearray(PARTITION_OFFSET)
        struct.pack_into("<BBBBBBBBII", header, 446,
                         0, 0, 2, 0, 0x83, 0xfe, 0xff, 0xff,
                         FIRST_LBA, len(data) // 512)
        header[510:512] = b"\x55\xaa"
        self.sd.write_bytes(header + data)
        self.kernel = self.root / "Image"
        self.kernel.write_bytes(b"qualified fake kernel for hash checks")
        self.sd_manifest = self.root / "sd-result.txt"
        self.sd_manifest.write_text(
            "stage=sd1\npartition_table=MBR\npartition1_first_lba=2048\n"
            "partition1_type=0x83\n"
            f"disk_bytes={self.sd.stat().st_size}\n"
            f"sd_image_sha256={digest(self.sd)}\n"
            f"partition_ext4_sha256={digest(self.partition)}\n"
        )
        self.oracle_manifest = self.root / "qemu-result.txt"
        self.oracle_manifest.write_text(
            "qualification=QEMU_VIRT_ONLY_NOT_PRODUCT_SPI_RTL\n"
            f"sd_image_sha256={digest(self.sd)}\n"
            f"oracle_image_sha256={digest(self.kernel)}\n"
        )

    def test_exact_mbr_ext4_sha_and_kernel_contract(self):
        proof = validate_media(self.sd, self.partition, self.sd_manifest,
                               self.kernel, self.oracle_manifest)
        self.assertEqual(proof["source_sd_sha256"], digest(self.sd))
        self.assertEqual(proof["oracle_image_sha256"], digest(self.kernel))

    def test_mutated_source_disk_is_refused_before_copy(self):
        with self.sd.open("r+b") as stream:
            stream.seek(10000)
            stream.write(b"!FUZZ")
        with self.assertRaisesRegex(QualificationError, "SHA-verified"):
            validate_media(self.sd, self.partition, self.sd_manifest,
                           self.kernel, self.oracle_manifest)

    def test_changed_qemu_kernel_is_refused(self):
        self.kernel.write_bytes(b"different kernel")
        with self.assertRaisesRegex(QualificationError, "SHA-verified"):
            validate_media(self.sd, self.partition, self.sd_manifest,
                           self.kernel, self.oracle_manifest)

    def test_getty_failure_recognizes_full_and_ellipsized_unit(self):
        exact = b"[DEPEND] Dependency failed for serial-getty@ttyS0.service - Serial Getty on ttyS0.\r\n"
        abbreviated = (b"[DEPEND] Dependency failed for serial-getty\xe2\x80\xa6S0.service"
                       b" - Serial Getty on ttyS0.\r\n")
        self.assertTrue(has_serial_getty_dependency_failure(exact))
        self.assertTrue(has_serial_getty_dependency_failure(abbreviated))
        self.assertFalse(has_serial_getty_dependency_failure(b"Dependency failed for unrelated.service\r\n"))
        self.assertFalse(has_serial_getty_dependency_failure(
            b"Dependency failed for unrelated.service\r\n"
            b"Started serial-getty@ttyS0.service - Serial Getty on ttyS0\r\n"))

    def test_clone_is_immutable_to_source_and_never_overwrites(self):
        output = self.root / "ab"
        output.mkdir()
        source_sha = digest(self.sd)
        partition_sha = digest(self.partition)
        state = {"timeout_removed": False, "delay_written": False, "console_masked": False}

        def fake_debugfs(partition: Path, command: str, *, write=False):
            if command.startswith("cat /etc/systemd/system.conf.d/"):
                return b"" if state["timeout_removed"] else b"[Manager]\nDefaultDeviceTimeoutSec=10min\n"
            if command.startswith("cat /etc/systemd/system/systemd-udev-trigger.service.d/"):
                return b"ExecStartPre=/usr/bin/sleep 110" if state["delay_written"] else b""
            if command == "stat /etc/systemd/system/console-getty.service":
                return b'Type: symlink Fast link dest: "/dev/null"' if state["console_masked"] else b""
            if command == "stat /etc/systemd/system/getty.target.wants/serial-getty@ttyS0.service":
                return b'Fast link dest: "/usr/lib/systemd/system/serial-getty@.service"'
            if command.startswith("rm /etc/systemd/system.conf.d/"):
                state["timeout_removed"] = True
            if command.startswith("write "):
                state["delay_written"] = True
            if command == "symlink /etc/systemd/system/console-getty.service /dev/null":
                state["console_masked"] = True
            return b""

        with patch("tools.ci.product_sd1_getty_udev_ab.debugfs", side_effect=fake_debugfs):
            report = prepare_variant(self.sd, self.partition, output,
                                     delay_seconds=110, baseline=True)
            self.assertIn("baseline_sd_sha256", report)
            self.assertEqual((output / "baseline.img").stat().st_size,
                             self.sd.stat().st_size)
            self.assertEqual(digest(self.sd), source_sha)
            self.assertEqual(digest(self.partition), partition_sha)
            self.assertEqual(report["baseline_console_getty_mask"], "CLONE_ONLY_SYMLINK_TO_DEV_NULL")
            with self.assertRaises(FileExistsError):
                prepare_variant(self.sd, self.partition, output,
                                delay_seconds=110, baseline=True)

    def test_isolated_ci_workflow_never_uses_signed_or_autorestarts(self):
        workflow = (Path(__file__).resolve().parents[1] /
                    ".github/workflows/product-sd1-getty-udev-ab.yml").read_text()
        self.assertIn("contains(github.event.head_commit.message, '[sd1-getty-udev-ab-run]')", workflow)
        self.assertIn("github.ref == 'refs/heads/fix/sd1-udev-getty-timeout-20261110'", workflow)
        self.assertIn("42831310558", workflow)
        self.assertIn("product_sd1_getty_udev_ab.py --run", workflow)
        self.assertIn("timeout-minutes: 30", workflow)
        self.assertNotIn("  pull_request:", workflow)
        self.assertNotIn("  workflow_dispatch:", workflow)
        upload = workflow.split("uses: actions/upload-artifact@v4", 1)[1]
        self.assertNotIn("*.img", upload)
        self.assertNotIn("*.ext4", upload)

    def test_qemu_diagnostic_disables_automatically_generated_console_getty(self):
        source = (Path(__file__).resolve().parents[1] /
                  "tools/ci/product_sd1_getty_udev_ab.py").read_text()
        # The baseline printed `login:` through console-getty even though
        # serial-getty failed.  Suppress ONLY the auto-generated console
        # competitor on both QEMU arms, keeping the explicitly enabled
        # serial-getty@ttyS0 unchanged and the production rootfs untouched.
        self.assertIn("systemd.getty_auto=no", source)
        # Exported Docker rootfs can retain an independently enabled
        # console-getty even with auto-generation disabled.  Explicitly mask
        # it in *both* diagnostic QEMU variants to prove ttyS0 provenance.
        self.assertIn("systemd.mask=console-getty.service", source)

    @unittest.skipUnless(shutil.which("mkfs.ext4") and shutil.which("debugfs"),
                         "host needs mkfs.ext4 and debugfs for actual ext4 mutation")
    def test_real_debugfs_can_remove_timeout_and_inject_udev_delay(self):
        from tools.ci.product_sd1_getty_udev_ab import debugfs

        tree = self.root / "tree"
        (tree / "etc/systemd/system.conf.d").mkdir(parents=True)
        (tree / "etc/systemd/system").mkdir(parents=True)
        (tree / "etc/systemd/system.conf.d/90-sd1-device-coldplug.conf").write_text(
            "[Manager]\nDefaultDeviceTimeoutSec=10min\n")
        filesystem = self.root / "actual.ext4"
        with filesystem.open("wb") as image:
            image.truncate(16 * 1024 * 1024)
        subprocess.run(["mkfs.ext4", "-q", "-F", "-d", str(tree), str(filesystem)],
                       check=True, capture_output=True)
        config = "/etc/systemd/system.conf.d/90-sd1-device-coldplug.conf"
        self.assertIn(b"DefaultDeviceTimeoutSec=10min", debugfs(filesystem, f"cat {config}"))
        debugfs(filesystem, f"rm {config}", write=True)
        self.assertNotIn(b"DefaultDeviceTimeoutSec=10min", debugfs(filesystem, f"cat {config}"))
        dropin_dir = "/etc/systemd/system/systemd-udev-trigger.service.d"
        debugfs(filesystem, f"mkdir {dropin_dir}", write=True)
        delay = self.root / "delay.conf"
        delay.write_text("[Service]\nExecStartPre=/usr/bin/sleep 110\nTimeoutStartSec=240s\n")
        destination = f"{dropin_dir}/90-sd1-ab-delay.conf"
        debugfs(filesystem, f"write {delay} {destination}", write=True)
        self.assertIn(b"ExecStartPre=/usr/bin/sleep 110", debugfs(filesystem, f"cat {destination}"))
        console_mask = "/etc/systemd/system/console-getty.service"
        self.assertFalse(debugfs(filesystem, f"stat {console_mask}").strip())
        debugfs(filesystem, f"symlink {console_mask} /dev/null", write=True)
        mask_stat = debugfs(filesystem, f"stat {console_mask}")
        self.assertIn(b"symlink", mask_stat)
        self.assertIn(b"/dev/null", mask_stat)


if __name__ == "__main__":
    unittest.main()
