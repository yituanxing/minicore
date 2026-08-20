import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class Rv64MinimalInitramfsContractTest(unittest.TestCase):
    def test_userspace_is_libc_free_rv64_and_forces_uart_refill(self):
        text = (ROOT / "software/rv64_userspace/minimal_init.S").read_text()
        self.assertIn("RV64_INIT_MESSAGE_LEN 22", text)
        self.assertIn('"RV64 USER UART IRQ OK\\n"', text)
        self.assertIn("li      a7, 64", text)
        self.assertIn("li      a7, 124", text)
        self.assertIn("ecall", text)

    def test_build_derives_from_qualified_rv64_baseline(self):
        text = (ROOT / "tools/ci/rv64_minimal_initramfs_build.sh").read_text()
        self.assertIn("RV64_LINUX_EARLY_BUILD_RESULT: status=PASS", text)
        self.assertIn('cp "${BASELINE_OBJ}/.config" "${OBJ_DIR}/.config"', text)
        self.assertIn("-e BLK_DEV_INITRD", text)
        self.assertIn("INITRAMFS_SOURCE", text)
        self.assertIn("-march=rv64ima_zicsr_zifencei -mabi=lp64", text)
        self.assertIn("Class:[[:space:]]*ELF64", text)

    def test_payload_keeps_exact_rv64_handoff_and_default_pty_path(self):
        text = (ROOT / "tools/ci/rv64_minimal_init_payload_build.sh").read_text()
        self.assertIn("rdinit=/init", text)
        self.assertNotIn("pty.legacy_count=0", text)
        self.assertIn("RV64_OPENSBI_PAYLOAD_OFFSET", text)
        self.assertIn("RV64_LINUX_PHYS_ENTRY", text)
        self.assertIn("RV64_MINIMAL_INIT_PAYLOAD_BUILD_RESULT: status=PASS", text)


if __name__ == "__main__":
    unittest.main()
