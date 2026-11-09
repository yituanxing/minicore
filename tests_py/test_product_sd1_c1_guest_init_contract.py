"""Source-only C1 guest handoff contracts; no RTL, kernel build or rootfs."""

from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "software/rv64_userspace/product_sd1_c1_init.c"
BUILDER = ROOT / "tools/ci/product_sd1_c1_initramfs_build.sh"
KERNEL_BUILDER = ROOT / "tools/ci/product_sd1_c1_kernel_build.sh"


class ProductSd1C1GuestInitContract(unittest.TestCase):
    def test_guest_pause_is_before_root_mount_and_native_exec(self):
        text = SOURCE.read_text()
        ready = text.index('say("AETHERCORE_C1_READY rootfs_unmounted=1')
        tty_open = text.index('open("/dev/ttyS0"')
        tty_raw = text.index('tcsetattr(input_fd, TCSANOW, &raw)')
        tty_blocking = text.index('fcntl(input_fd, F_SETFL, flags & ~O_NONBLOCK)')
        tty_read = text.index('read(input_fd, &byte, 1)')
        release = text.index("wait_for_release();", ready)
        mount = text.index('mount(root_device, "/newroot", "ext4"', ready)
        exec_native = text.index('execve("/sbin/init"', ready)
        # The first host-observed READY must guarantee that receiving GO is
        # already armed. Otherwise host UART send can outrun guest termios.
        self.assertLess(tty_open, tty_raw)
        self.assertLess(tty_raw, tty_blocking)
        self.assertLess(tty_blocking, ready)
        self.assertLess(ready, tty_read)
        self.assertLess(ready, release)
        self.assertLess(release, mount)
        self.assertLess(mount, exec_native)
        self.assertIn('"AETHERCORE_C1_GO\\n"', text)
        self.assertIn('AETHERCORE_C1_FAIL stage=', text)
        self.assertIn('select_root_from_cmdline()', text)
        self.assertIn('"/dev/mmcblk0p1"', text)
        self.assertIn('"/dev/vda1"', text)

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

    def test_c1_kernel_is_a_separate_rootfs_independent_product_profile(self):
        script = KERNEL_BUILDER.read_text()
        self.assertIn('cp -a --reflink=auto "$SEED/obj"', script)
        self.assertIn('--set-str INITRAMFS_SOURCE "$C1_INIT/initramfs.list"', script)
        self.assertIn('rootfs_binding=NONE', script)
        self.assertIn('BUILT_UNQUALIFIED', script)
        self.assertNotIn('RV64_DEBIAN_FULL_ROOTFS_ARCHIVE', script)
        self.assertNotIn('qemu-system-', script)
        subprocess.run(["bash", "-n", str(KERNEL_BUILDER)], check=True)


if __name__ == "__main__":
    unittest.main()
