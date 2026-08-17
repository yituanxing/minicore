from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "tools/ci/l32_software_artifact_cache.sh"
PROFILE = ROOT / "tools" / "ci" / "l32_userspace_profile.sh"
EARLY = ROOT / ".github/workflows/l32-linux-boot.yml"
DEEPER = ROOT / ".github/workflows/l32-linux-deeper-boot.yml"
MINIMAL = ROOT / ".github/workflows/l32-minimal-initramfs.yml"
BUSYBOX = ROOT / ".github/workflows/l32-busybox-build.yml"
RUNTIME_IMAGE_CACHE = ROOT / "tools/ci/l32_runtime_image_cache.sh"


class L32SoftwareArtifactCacheContract(unittest.TestCase):
    def test_cache_fails_closed_on_inputs_pass_marker_and_output_hashes(self):
        text = CACHE.read_text()
        for required in (
            "L32_SOFTWARE_CACHE_HIT", "L32_SOFTWARE_CACHE_MISS", "L32_SOFTWARE_CACHE_MARK",
            "software-cache.txt", "input_key", "result_marker", "grep -qx", "sha256sum", "expected=", "actual=",
            "build did not produce its qualified PASS result", "build is missing qualified output",
        ):
            self.assertIn(required, text)
        for target in (
            "busybox)", "runtime-probe)", "minimal-initramfs)", "busybox-initramfs)",
            "linux-payload)", "minimal-payload)", "busybox-payload)",
        ):
            self.assertIn(target, text)

    def test_only_userspace_owned_targets_are_profile_sensitive(self):
        text = CACHE.read_text()
        self.assertIn("profile_sensitive=0", text)
        self.assertEqual(text.count("profile_sensitive=1"), 4)
        for required in (
            'result_file="${L32_USERSPACE_BUSYBOX_BUILD_DIR}/result.txt"',
            'result_file="${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}/result.txt"',
            'result_file="${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}/result.txt"',
            'result_file="${L32_USERSPACE_PAYLOAD_BUILD_DIR}/result.txt"',
            'dynamic_inputs+=("profile=${L32_USERSPACE_PROFILE}")',
            'grep -qx "profile=${cache_profile}" "${result_file}"',
            'printf \'profile %s\\n\' "${cache_profile}"',
        ):
            self.assertIn(required, text)
        for required in (
            'result_file="${ROOT_DIR}/build/l32-linux-initramfs/result.txt"',
            'result_file="${ROOT_DIR}/build/l32-linux-boot/result.txt"',
            'result_file="${ROOT_DIR}/build/l32-minimal-init-boot/result.txt"',
        ):
            self.assertIn(required, text)

    def test_busybox_cache_qualifies_wrapper_prerequisites_and_elf_evidence(self):
        text = CACHE.read_text()
        for required in (
            '"${L32_USERSPACE_BUSYBOX_BUILD_DIR}/l32-${L32_USERSPACE_PROFILE}-ilp32-gcc"',
            '"${L32_USERSPACE_BUSYBOX_BUILD_DIR}/musl-prefix/lib/libc.a"',
            '"${L32_USERSPACE_BUSYBOX_BUILD_DIR}/musl-prefix/lib/crt1.o"',
            '"${L32_USERSPACE_BUSYBOX_BUILD_DIR}/musl-prefix/lib/crti.o"',
            '"${L32_USERSPACE_BUSYBOX_BUILD_DIR}/musl-prefix/lib/crtn.o"',
            '"${L32_USERSPACE_BUSYBOX_BUILD_DIR}/evidence/toolchain-probe-profile.txt"',
            '"${L32_USERSPACE_BUSYBOX_BUILD_DIR}/evidence/musl-probe-profile.txt"',
            '"${L32_USERSPACE_BUSYBOX_BUILD_DIR}/evidence/busybox-profile.txt"',
        ):
            self.assertIn(required, text)

    def test_profile_helper_keeps_historical_default_paths(self):
        profile = PROFILE.read_text()
        self.assertIn('L32_USERSPACE_PROFILE="${AETHERCORE_L32_USERSPACE_PROFILE:-rv32ima}"', profile)
        self.assertIn('L32_USERSPACE_BUILD_SUFFIX=""', profile)
        self.assertIn('L32_USERSPACE_BUILD_SUFFIX="-rv32imac"', profile)
        self.assertIn('L32_USERSPACE_BUSYBOX_BUILD_DIR="${ROOT_DIR}/build/l32-busybox${L32_USERSPACE_BUILD_SUFFIX}"', profile)
        self.assertIn('L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR="${ROOT_DIR}/build/l32-linux-busybox${L32_USERSPACE_BUILD_SUFFIX}"', profile)

    def test_runtime_image_cache_is_profile_and_base_kernel_aware(self):
        runtime_cache = RUNTIME_IMAGE_CACHE.read_text()
        for required in (
            'source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"',
            'BUILD_DIR="${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}"',
            'BASE_KERNEL_BUILD_DIR="${ROOT_DIR}/build/l32-linux"',
            'BASE_KERNEL_BUILD_DIR="${ROOT_DIR}/build/l32-linux-rv32imac"',
            '"${BASE_KERNEL_IMAGE}"', '"${BASE_KERNEL_RESULT}"',
            '"${L32_USERSPACE_BUSYBOX_BUILD_DIR}/busybox-src/busybox"',
            '"${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}/l32-runtime-probe"',
            '"${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/lua"',
            '"${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/sqlite-smoke"',
            '"${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/bash"',
            '"${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/busybox-real"',
            '"${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/zlib-smoke"',
            '"${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}/libpng-smoke"',
            'printf \'profile=%s\\nisa=%s\\nrequire_c=%s\\n\'',
            'grep -qx "profile=${L32_USERSPACE_PROFILE}" "${RESULT}"',
            'echo "profile ${L32_USERSPACE_PROFILE}"',
            "L32_RUNTIME_IMAGE_CACHE_HIT", "L32_RUNTIME_IMAGE_CACHE_MISS", "L32_RUNTIME_IMAGE_CACHE_MARK",
        ):
            self.assertIn(required, runtime_cache)

    def test_cpu_validation_workflows_reuse_qualified_software(self):
        early = EARLY.read_text()
        deeper = DEEPER.read_text()
        minimal = MINIMAL.read_text()
        busybox = BUSYBOX.read_text()

        for text in (early, deeper, minimal, busybox):
            self.assertIn("tools/ci/l32_software_artifact_cache.sh", text)
            self.assertIn("bash -n tools/ci/l32_software_artifact_cache.sh", text)

        self.assertIn("l32_software_artifact_cache.sh linux-payload tools/ci/l32_linux_payload_build.sh", early)
        self.assertIn("l32_software_artifact_cache.sh linux-payload tools/ci/l32_linux_payload_build.sh", deeper)
        self.assertIn("l32_software_artifact_cache.sh minimal-initramfs tools/ci/l32_minimal_initramfs_build.sh", minimal)
        self.assertIn("l32_software_artifact_cache.sh minimal-payload tools/ci/l32_minimal_init_payload_build.sh", minimal)
        self.assertIn("l32_software_artifact_cache.sh busybox \\\n            bash tools/ci/l32_busybox_build.sh", busybox)
        self.assertIn("tools/ci/l32_runtime_image_cache.sh", busybox)
        self.assertIn("l32_software_artifact_cache.sh busybox-payload tools/ci/l32_busybox_payload_build.sh", busybox)


if __name__ == "__main__":
    unittest.main()
