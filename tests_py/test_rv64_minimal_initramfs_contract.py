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

    def test_linux_source_download_fails_over_but_keeps_exact_archive_sha(self):
        text = (ROOT / "tools/ci/rv64_linux_early_build.sh").read_text()
        self.assertIn("https://mirrors.edge.kernel.org", text)
        self.assertIn("https://www.kernel.org", text)
        self.assertIn('printf \'%s  %s\\n\' "${RV64_LINUX_SHA256}" "${tmp}" | sha256sum -c -', text)
        self.assertIn("trying next mirror", text)
        self.assertIn("unable to fetch the exact Linux", text)

    def test_payload_keeps_exact_rv64_handoff_and_default_pty_path(self):
        text = (ROOT / "tools/ci/rv64_minimal_init_payload_build.sh").read_text()
        self.assertIn("rdinit=/init", text)
        self.assertNotIn("pty.legacy_count=0", text)
        self.assertIn("RV64_OPENSBI_PAYLOAD_OFFSET", text)
        self.assertIn("RV64_LINUX_PHYS_ENTRY", text)
        self.assertIn("RV64_MINIMAL_INIT_PAYLOAD_BUILD_RESULT: status=PASS", text)
        self.assertIn("aethercore.EmitAetherSoCDts", text)
        self.assertIn('dtc -I dts -O dtb -o "${DTB}" "${DTS}"', text)
        self.assertNotIn("make_l32_dtb.py", text)

    def test_hosted_workflow_checkpoints_each_validated_layer_before_deep_proof(self):
        text = (ROOT / ".github/workflows/rv64-minimal-initramfs-v1.yml").read_text()
        producer = (ROOT / ".github/workflows/hosted-verilator-cache.yml").read_text()
        self.assertIn("uses: actions/cache/restore@v4", text)
        self.assertIn("uses: actions/cache/save@v4", text)
        self.assertIn("uses: ./.github/workflows/hosted-verilator-cache.yml", text)
        self.assertIn("needs: verilator-tool", text)
        self.assertIn("Revalidate fixed Verilator from producer cache", text)
        self.assertIn("Save Scala dependencies after focused bootstrap", text)
        self.assertIn("Save validated RV64 Linux GCC immediately", text)
        self.assertIn("Save qualified RV64 Linux/OpenSBI baseline immediately", text)
        self.assertIn("Save exact-head simulation build after any executed proof", text)
        self.assertIn("Propagate Linux baseline failure after preserving caches", text)
        self.assertIn("Propagate real PID1 proof failure after saving simulation build", text)
        self.assertIn("rv64-linux-gcc-v1", text)
        self.assertIn("verilator-5.024-v1", text)
        self.assertIn("build/rv64-linux-early/evidence/", text)

        self.assertIn("aethercore-hosted-verilator-5-024-v1", producer)
        self.assertIn("cancel-in-progress: false", producer)
        self.assertIn("Save exact validated Verilator immediately", producer)
        self.assertIn("tools/ensure_verilator_5_024.sh", producer)


if __name__ == "__main__":
    unittest.main()
