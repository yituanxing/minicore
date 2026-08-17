from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
FREEZE = ROOT / "tools" / "ci" / "l32_busybox_runtime_freeze.sh"
PROFILE = ROOT / "tools" / "ci" / "l32_userspace_profile.sh"


class L32BusyBoxRuntimeFreezeContract(unittest.TestCase):
    def test_freeze_consumes_one_profile_owned_artifact_chain(self) -> None:
        text = FREEZE.read_text()
        for required in (
            'source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"',
            'BUSYBOX_BUILD_DIR="${L32_USERSPACE_BUSYBOX_BUILD_DIR}"',
            'RUNTIME_PROBE_BUILD_DIR="${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}"',
            'REAL_BUILD_DIR="${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}"',
            'LINUX_BUILD_DIR="${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}"',
            'PAYLOAD_BUILD_DIR="${L32_USERSPACE_PAYLOAD_BUILD_DIR}"',
            'grep -qx "profile=${L32_USERSPACE_PROFILE}"',
            "L32_BUSYBOX_RUNTIME_FREEZE: status=PASS",
            'echo "profile=${L32_USERSPACE_PROFILE}"',
            'echo "isa=${L32_USERSPACE_EFFECTIVE_ISA}"',
            'echo "require_c=${L32_USERSPACE_REQUIRE_C}"',
        ):
            self.assertIn(required, text)

    def test_freeze_replays_qualified_hashes_across_all_userspace_layers(self) -> None:
        text = FREEZE.read_text()
        for required in (
            "busybox_sha256",
            "probe_sha256",
            "image_sha256",
            "lua_sha256",
            "sqlite_sha256",
            "bash_sha256",
            "busybox_real_sha256",
            "zlib_sha256",
            "libpng_sha256",
            "firmware_bin_sha256",
            "sha256sum -c",
        ):
            self.assertIn(required, text)
        for marker in (
            "L32_BUSYBOX_BUILD_RESULT: status=PASS",
            "L32_RUNTIME_PROBE_BUILD_RESULT: status=PASS",
            "L32_REAL_PROGRAMS_BUILD_RESULT: status=PASS",
            "L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS",
            "L32_BUSYBOX_SHELL_PAYLOAD_BUILD_RESULT: status=PASS",
        ):
            self.assertIn(marker, text)

    def test_historical_default_freeze_path_remains_unchanged(self) -> None:
        profile = PROFILE.read_text()
        self.assertIn('L32_USERSPACE_BUILD_SUFFIX=""', profile)
        self.assertIn(
            'L32_USERSPACE_PAYLOAD_BUILD_DIR="${ROOT_DIR}/build/l32-busybox-shell-boot${L32_USERSPACE_BUILD_SUFFIX}"',
            profile,
        )
        text = FREEZE.read_text()
        self.assertIn('"${PAYLOAD_BUILD_DIR}/runtime-freeze.txt"', text)


if __name__ == "__main__":
    unittest.main()
