import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


def parse_env(path: pathlib.Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key] = value
    return values


class Rv64BusyboxContractTest(unittest.TestCase):
    def test_reuses_frozen_userspace_upstream_versions(self):
        rv64 = parse_env(ROOT / "software/rv64_busybox/manifest.env")
        l32 = parse_env(ROOT / "software/l32_busybox/manifest.env")
        self.assertEqual(rv64["RV64_MUSL_VERSION"], l32["MUSL_VERSION"])
        self.assertEqual(rv64["RV64_MUSL_SHA256"], l32["MUSL_SHA256"])
        self.assertEqual(rv64["RV64_BUSYBOX_VERSION"], l32["BUSYBOX_VERSION"])
        self.assertEqual(rv64["RV64_BUSYBOX_SHA256"], l32["BUSYBOX_SHA256"])
        self.assertEqual(rv64["RV64_USERSPACE_ISA"], "rv64ima_zicsr_zifencei")
        self.assertEqual(rv64["RV64_USERSPACE_ABI"], "lp64")

    def test_busybox_build_is_narrow_static_rv64_softfloat(self):
        text = (ROOT / "tools/ci/rv64_busybox_build.sh").read_text()
        self.assertIn("AETHERCORE_RV64_LINUX_CROSS_COMPILE", text)
        self.assertIn("-march=\"${RV64_USERSPACE_ISA}\"", text)
        self.assertIn("-mabi=\"${RV64_USERSPACE_ABI}\"", text)
        self.assertIn("--target=riscv64-linux-musl", text)
        self.assertIn("CONFIG_STATIC", text)
        self.assertIn("CONFIG_ASH", text)
        self.assertIn("CONFIG_SH_IS_ASH", text)
        self.assertIn("statically linked", text)
        self.assertIn("RV64_ELF_PROFILE_PASS", text)
        self.assertIn("RV64_BUSYBOX_BUILD_RESULT: status=PASS", text)
        self.assertIn("flags & 0x7", text)
        for forbidden in ("sqlite-smoke", "/opt/l32", "l32_real_programs", "opensbi_forkserver"):
            self.assertNotIn(forbidden, text)

    def test_initramfs_derives_from_qualified_kernel_and_only_adds_shell(self):
        text = (ROOT / "tools/ci/rv64_busybox_initramfs_build.sh").read_text()
        self.assertIn("RV64_LINUX_EARLY_BUILD_RESULT: status=PASS", text)
        self.assertIn('cp "${BASELINE_OBJ}/.config" "${OBJ_DIR}/.config"', text)
        self.assertIn("RV64_BUSYBOX_BUILD_RESULT: status=PASS", text)
        self.assertIn("CONFIG_INITRAMFS_COMPRESSION_NONE=y", text)
        self.assertIn("slink /bin/sh busybox", text)
        self.assertIn("nod /dev/console", text)
        self.assertIn('RV64 BUSYBOX SHELL READY', text)
        self.assertIn("exec /bin/sh -i", text)
        self.assertIn("RV64_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS", text)
        for forbidden in ("lua-smoke", "sqlite-smoke", "/opt/l32", "busybox-real"):
            self.assertNotIn(forbidden, text)

    def test_payload_preserves_exact_rv64_handoff_and_default_pty_path(self):
        text = (ROOT / "tools/ci/rv64_busybox_payload_build.sh").read_text()
        self.assertIn("rdinit=/init", text)
        self.assertNotIn("pty.legacy_count=0", text)
        self.assertIn("RV64_OPENSBI_PAYLOAD_OFFSET", text)
        self.assertIn("RV64_LINUX_PHYS_ENTRY", text)
        self.assertIn("RV64_BUSYBOX_PAYLOAD_BUILD_RESULT: status=PASS", text)

    def test_qualification_is_manual_only_and_requires_real_interrupts(self):
        text = (ROOT / ".github/workflows/rv64-busybox-shell-v1.yml").read_text()
        self.assertIn("workflow_dispatch:", text)
        self.assertNotIn("pull_request:", text)
        self.assertNotIn("push:", text)
        self.assertIn('MILESTONE="RV64 BUSYBOX SHELL READY"', text)
        self.assertIn("MIN_STIP=1", text)
        self.assertIn("MIN_SEIP=1", text)
        self.assertIn("AetherCoreOpenSbiRV64SimTop", text)
        self.assertIn("MAX_CYCLES=800000000", text)


if __name__ == "__main__":
    unittest.main()
