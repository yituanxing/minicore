"""Source-only C1 guest handoff contracts; no RTL, kernel build or rootfs."""

from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "software/rv64_userspace/product_sd1_c1_init.c"
BUILDER = ROOT / "tools/ci/product_sd1_c1_initramfs_build.sh"


class ProductSd1C1GuestInitContract(unittest.TestCase):
    def test_guest_pause_is_before_root_mount_and_native_exec(self):
        text = SOURCE.read_text()
        ready = text.index('say("AETHERCORE_C1_READY rootfs_unmounted=1')
        release = text.index("wait_for_release();", ready)
        mount = text.index('mount("/dev/mmcblk0p1", "/newroot", "ext4"', ready)
        exec_native = text.index('execve("/sbin/init"', ready)
        self.assertLess(ready, release)
        self.assertLess(release, mount)
        self.assertLess(mount, exec_native)
        self.assertIn('"AETHERCORE_C1_GO\\n"', text)
        self.assertIn('AETHERCORE_C1_FAIL stage=', text)

    def test_builder_contains_only_generic_static_init_and_refuses_overwrite(self):
        text = BUILDER.read_text()
        self.assertIn("-static", text)
        self.assertIn("nod /dev/console", text)
        self.assertIn("file /init", text)
        self.assertIn("refusing to overwrite an existing C1 initramfs", text)
        self.assertNotIn("docker pull", text)
        self.assertNotIn("qemu-system-riscv64", text)
        self.assertNotIn("run-existing", text)

    def test_host_c_source_syntax_and_no_expensive_guest_run(self):
        result = subprocess.run(
            ["bash", str(BUILDER), "check-source"],
            cwd=ROOT, text=True, capture_output=True, check=True,
        )
        self.assertIn("PRODUCT_SD1_C1_GUEST_INIT_SOURCE_PASS", result.stdout)


if __name__ == "__main__":
    unittest.main()
