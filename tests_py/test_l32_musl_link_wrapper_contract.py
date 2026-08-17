from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
WRAPPER = ROOT / "tools" / "ci" / "l32_musl_link_wrapper.sh"
PROFILE = ROOT / "tools" / "ci" / "l32_userspace_profile.sh"
BUSYBOX_BUILD = ROOT / "tools" / "ci" / "l32_busybox_build.sh"


class L32MuslLinkWrapperContract(unittest.TestCase):
    def test_wrapper_generation_is_profile_owned(self) -> None:
        text = WRAPPER.read_text()
        for required in (
            'source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"',
            'BUILD_DIR="${L32_USERSPACE_BUSYBOX_BUILD_DIR}"',
            'L32_CC="${BUILD_DIR}/l32-${L32_USERSPACE_PROFILE}-ilp32-gcc"',
            'OUTPUT="${L32_USERSPACE_MUSL_WRAPPER}"',
            'MUSL_PREFIX="${BUILD_DIR}/musl-prefix"',
            "crt1.o",
            "crti.o",
            "crtn.o",
            "-Wl,--start-group -lc",
            "L32_MUSL_LINK_WRAPPER_RESULT: status=PASS",
        ):
            self.assertIn(required, text)

    def test_generated_wrapper_reloads_same_profile_contract(self) -> None:
        text = WRAPPER.read_text()
        generated = text.split("cat > \"${OUTPUT}.tmp.$$\" <<'EOF'", 1)[1]
        self.assertIn('source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"', generated)
        self.assertIn('L32_CC="${BUILD_DIR}/l32-${L32_USERSPACE_PROFILE}-ilp32-gcc"', generated)
        self.assertIn("-nostdinc", generated)
        self.assertIn("-nostdlib -static", generated)
        self.assertIn("-specs=*) ;;", generated)

    def test_profile_contract_exports_distinct_wrapper_path(self) -> None:
        profile = PROFILE.read_text()
        self.assertIn(
            'L32_USERSPACE_MUSL_WRAPPER="${L32_USERSPACE_BUSYBOX_BUILD_DIR}/l32-musl-real-gcc"',
            profile,
        )
        self.assertIn("L32_USERSPACE_MUSL_WRAPPER", profile)

    def test_busybox_build_uses_canonical_wrapper_after_musl_install(self) -> None:
        text = BUSYBOX_BUILD.read_text()
        for required in (
            'MUSL_LINK_WRAPPER_BUILDER="${ROOT_DIR}/tools/ci/l32_musl_link_wrapper.sh"',
            'MUSL_UPSTREAM_GCC="${MUSL_PREFIX}/bin/musl-gcc"',
            '"${MUSL_LINK_WRAPPER_BUILDER}"',
            'MUSL_GCC="${L32_USERSPACE_MUSL_WRAPPER}"',
            '"${MUSL_GCC}" -Os -static "${BUILD_DIR}/musl-probe.c"',
            'CC="${MUSL_GCC}"',
            'echo "musl_wrapper=${MUSL_GCC}"',
            'echo "musl_wrapper_sha256=',
        ):
            self.assertIn(required, text)
        self.assertNotIn('MUSL_GCC="${MUSL_PREFIX}/bin/musl-gcc"', text)


if __name__ == "__main__":
    unittest.main()
