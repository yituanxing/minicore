"""Offline contracts for the distinct Product C1 OpenSBI/DTB build.

No Linux, OpenSBI, Verilator or QEMU boot is performed by this source test.
"""

from pathlib import Path
import hashlib
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
C1 = ROOT / "tools/ci/product_sd1_c1_firmware_build.sh"
COMMON = ROOT / "tools/ci/rv64_minimal_init_payload_build.sh"
STRICT_SD1 = ROOT / "tools/ci/rv64_debian_payload_build.sh"


class ProductSd1C1FirmwareContract(unittest.TestCase):
    def test_c1_uses_distinct_kernel_and_firmware_not_strict_sd1(self):
        source = C1.read_text()
        self.assertIn('build/product-sd1-c1-kernel', source)
        self.assertIn('build/product-sd1-c1-initramfs', source)
        self.assertIn('build/product-sd1-c1-boot', source)
        self.assertNotIn('build/rv64-debian-boot"', source)
        self.assertIn("PRODUCT_SD1_C1_KERNEL_BUILD_RESULT: status=BUILT_UNQUALIFIED", source)
        self.assertIn("PRODUCT_SD1_C1_INITRAMFS_RESULT: status=READY_UNVERIFIED", source)
        self.assertIn("PRODUCT_SD1_C1_FIRMWARE_RESULT: status=READY_UNVERIFIED", source)
        self.assertIn("rootfs_binding=NONE", source)
        self.assertIn('refusing to overwrite', source)

    def test_c1_pins_real_guest_bytes_and_product_spi_bootargs(self):
        source = C1.read_text()
        for required in (
            "kernel_sha256", "config_sha256", "init_sha256",
            "init_elf_sha256", "initramfs_spec_sha256",
            "CONFIG_INITRAMFS_SOURCE=", "CONFIG_MMC_SPI=y",
            "CONFIG_SPI_OC_TINY=y", "CONFIG_EXT4_FS=y",
            "RV64_PAYLOAD_PRODUCT_BOARD=1", "RV64_PAYLOAD_ENABLE_SPI_SD=1",
            "root=/dev/mmcblk0p1", "rootfstype=ext4", "rdinit=/init",
            "riscv,ndev = <52>;", "mmc-spi-slot",
            "firmware_bin_sha256", "fdt_sha256", "linux_config_sha256",
        ):
            self.assertIn(required, source)
        self.assertIn("rootfs_binding=NONE", source)
        self.assertNotIn("init=/sbin/init", source.split("export RV64_PAYLOAD_BOOTARGS=")[-1])
        self.assertNotIn("RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS", source)
        self.assertNotIn("RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS", source)

    def test_generic_builder_defaults_stay_strict_and_c1_is_opt_in(self):
        common = COMMON.read_text()
        c1 = C1.read_text()
        strict = STRICT_SD1.read_text()
        # SD1 seed cache includes this precise helper's bytes. A C1-only
        # change must NEVER invalidate another already-qualified media cache.
        self.assertEqual(
            hashlib.sha256(COMMON.read_bytes()).hexdigest(),
            "c827188f1fa4fe11bbbe66d8ecd6db4a972fc008fc1da2b82850a93d9f12530c",
        )
        self.assertIn('grep -qx "${LINUX_QUALIFIER}: status=PASS"', common)
        self.assertIn("sed -n 's/^image_sha256=//p'", common)
        self.assertIn("PRODUCT_SD1_C1_FIRMWARE_INPUT_PRECHECK: status=PASS", c1)
        self.assertIn("qualification=BYTES_VERIFIED_FOR_OPENSBI_INPUT_ONLY_NOT_RTL", c1)
        self.assertIn("RV64_PAYLOAD_LINUX_QUALIFIER='PRODUCT_SD1_C1_FIRMWARE_INPUT_PRECHECK'", c1)
        self.assertIn('ln -s "$C1_IMAGE"', c1)
        # Strict SD1's same generic builder continues to demand a PASS result,
        # and expressly rejects the C1 kernel that embeds an initramfs.
        self.assertIn("grep -qx 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS'", strict)
        self.assertIn("CONFIG_INITRAMFS_SOURCE=\"\"", strict)
        self.assertIn('rv64_minimal_init_payload_build.sh', strict)
        self.assertIn('rootfs_result_sha256', strict)
        self.assertNotIn("PRODUCT_SD1_C1_FIRMWARE_INPUT_PRECHECK", strict)

    def test_invalid_invocation_fails_before_any_build(self):
        result = subprocess.run(
            ["bash", str(C1), "unexpected"],
            text=True, capture_output=True, check=False, timeout=10,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("usage:", result.stderr)


if __name__ == "__main__":
    unittest.main()
