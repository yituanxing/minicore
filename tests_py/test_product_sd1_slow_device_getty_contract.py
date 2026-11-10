"""The full SD1 Debian rootfs must keep native login on slow RTL coldplug.

The real RTL 17572M UART recorded dev-ttyS0.device timing out before udevd
finished startup, so the stock serial-getty dependency failed.  These static
guards prevent the derived SD1 media builder from silently reintroducing that
90-second boot-time failure; UART login remains the runtime acceptance gate.
"""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
BUILDER = ROOT / "tools/ci/rv64_debian_rootfs_build.sh"


class ProductSd1SlowDeviceGettyContract(unittest.TestCase):
    def test_sd1_slow_device_timeout_is_scoped_to_native_disk_root(self):
        source = BUILDER.read_text()
        disk_root = source.split('if [[ "${STAGE}" == d3 ]]; then', 1)[1]
        sd1 = disk_root.split("else", 1)[1]
        self.assertIn("DefaultDeviceTimeoutSec=10min", sd1)
        self.assertIn("90-sd1-device-coldplug.conf", sd1)
        self.assertIn('sudo mkdir -p "${ROOTFS_DIR}/etc/systemd/system.conf.d"', sd1)

    def test_stock_debian_serial_getty_kept_without_competing_console_getty(self):
        source = BUILDER.read_text()
        self.assertIn("getty.target.wants/serial-getty@ttyS0.service", source)
        self.assertNotIn("getty.target.wants/console-getty.service", source)
        self.assertIn("/usr/sbin/agetty", source)
        self.assertIn("/usr/bin/login", source)


if __name__ == "__main__":
    unittest.main()
