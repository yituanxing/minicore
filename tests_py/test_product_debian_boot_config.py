"""Debian RTL boots must exclude heavyweight debug selftests, not CPU features.

This is a source-level guard.  The built kernel's resolved .config is checked
again by rv64_debian_linux_build.sh after olddefconfig, so Kconfig dependencies
cannot quietly re-enable the selftests in the actual Image.
"""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
KERNEL = ROOT / "tools/ci/rv64_debian_linux_build.sh"
BASELINE = ROOT / "tools/ci/rv64_linux_early_build.sh"
PAYLOAD = ROOT / "tools/ci/rv64_minimal_init_payload_build.sh"

UNNECESSARY_BOOT_DEBUG = (
    "DEBUG_PLIST", "DEBUG_PAGEALLOC", "DEBUG_VM", "DEBUG_VM_PGTABLE",
    "SCHED_DEBUG", "DEBUG_RT_MUTEXES", "DEBUG_SPINLOCK",
    "DEBUG_MUTEXES", "DEBUG_RWSEMS", "DEBUG_LIST", "MEMTEST",
)


class ProductDebianBootConfig(unittest.TestCase):
    def test_ram_debian_uses_raw_cpio_and_sd1_keeps_empty_initramfs(self):
        content = KERNEL.read_text()
        self.assertIn('if [[ "${ROOTFS_STAGE}" == sd1 ]]; then', content)
        self.assertIn("initramfs_source=''", content)
        self.assertIn('if [[ "${ROOTFS_STAGE}" != sd1 ]]; then', content)
        self.assertIn('-e INITRAMFS_COMPRESSION_NONE -d INITRAMFS_COMPRESSION_GZIP', content)
        self.assertIn("'CONFIG_INITRAMFS_COMPRESSION_NONE=y'", content)
        self.assertIn('^CONFIG_INITRAMFS_COMPRESSION_(GZIP|BZIP2|LZMA|XZ|LZO|LZ4|ZSTD)=y$', content)
        self.assertIn('rv64_minimal_init_payload_build.sh', content)
        payload = PAYLOAD.read_text()
        self.assertIn('firmware_bytes="$(stat -c %s "${FW_BIN}")"', payload)
        self.assertIn('RV64_OPENSBI_FDT_ADDR - RV64_OPENSBI_FW_TEXT_START', payload)
        self.assertLess(content.index('-e INITRAMFS_COMPRESSION_NONE'), content.index('olddefconfig'))
        self.assertLess(content.index("'CONFIG_INITRAMFS_COMPRESSION_NONE=y'"), content.index(' -j"${JOBS}" Image'))

    def test_each_unneeded_debug_selftest_is_explicitly_disabled(self):
        content = KERNEL.read_text()
        # The request must apply in the common config path for D1/D2/D3/SD1.
        self.assertLess(content.index("-d DEBUG_PLIST"), content.index("if [[ \"${ROOTFS_STAGE}\" == d2"))
        for symbol in UNNECESSARY_BOOT_DEBUG:
            self.assertRegex(content, rf"(?<!\w)-d {symbol}(?!\w)")

    def test_resolved_kconfig_is_checked_after_olddefconfig(self):
        content = KERNEL.read_text()
        self.assertIn("make -C \"${SOURCE_DIR}\" O=\"${OBJ_DIR}\" \\\n  ARCH=riscv CROSS_COMPILE=\"${CROSS}\" olddefconfig", content)
        self.assertIn("if grep -Eq \"^CONFIG_${disabled}=(y|m)$\"", content)
        self.assertIn('fail "Debian ${ROOTFS_STAGE} unexpectedly enabled costly debug selftest ${disabled}"', content)
        self.assertLess(content.index("olddefconfig"), content.index("for disabled in"))
        self.assertLess(content.index("for disabled in"), content.index(' -j"${JOBS}" Image'))

    def test_linux_abi_and_hardware_are_not_replaced_by_tiny_kernel(self):
        content = KERNEL.read_text()
        for necessary in (
            "-e RISCV_ISA_C -e FPU", "-e BLK_DEV_INITRD", "-e BINFMT_ELF",
            "CONFIG_MMU=y", "CONFIG_FPU=y", "CONFIG_RISCV_ISA_C=y",
            "-e SERIAL_8250_CONSOLE", "-e UNIX98_PTYS", "-e FUTEX",
            "-e EXT4_FS", "-e MMC_SPI", "-e MSDOS_PARTITION",
        ):
            self.assertIn(necessary, content)
        self.assertNotIn("DEBUG_PLIST", BASELINE.read_text())


if __name__ == "__main__":
    unittest.main()
