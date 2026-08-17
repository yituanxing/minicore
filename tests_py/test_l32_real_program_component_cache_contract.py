from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "tools/ci/l32_real_programs_build.sh"
CACHE = ROOT / "tools/ci/l32_real_programs_cache.sh"
PROFILE = ROOT / "tools/ci/l32_userspace_profile.sh"
PROFILE_AUDIT = ROOT / "tools/ci/riscv_elf_profile.py"
INITRAMFS = ROOT / "tools/ci/l32_busybox_initramfs_build.sh"
LIBPNG_SOURCE = ROOT / "software/l32_real/libpng-smoke.c"


class L32RealProgramComponentCacheContract(unittest.TestCase):
    def test_builder_exposes_independent_component_modes_and_recipe_hashes(self):
        text = BUILD.read_text()
        for required in (
            "build_lua()", "build_sqlite()", "build_bash()", "build_busybox()", "build_zlib()", "build_libpng()",
            "recipe_hash()", "finalize()", "recipe-hash", "all|lua|sqlite|bash|busybox|zlib|libpng|finalize",
            "declare -f check_elf", "declare -f build_lua", "declare -f build_sqlite", "declare -f build_bash",
            "declare -f build_busybox", "declare -f build_zlib", "declare -f build_libpng",
        ):
            self.assertIn(required, text)

        hashes = {
            component: subprocess.check_output(
                [str(BUILD), "recipe-hash", component], text=True
            ).strip()
            for component in ("lua", "sqlite", "bash", "busybox", "zlib", "libpng")
        }
        self.assertEqual(len(set(hashes.values())), 6)
        for digest in hashes.values():
            self.assertRegex(digest, r"^[0-9a-f]{64}$")

    def test_cache_qualifies_and_rebuilds_components_independently(self):
        text = CACHE.read_text()
        for required in (
            'source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"',
            'BUILD_DIR="${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}"',
            'CACHE_ROOT="${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/l32/real-programs"',
            'COMPONENT_CACHE_DIR="${CACHE_ROOT}/components${L32_USERSPACE_BUILD_SUFFIX}"',
            "components=(lua sqlite bash busybox zlib libpng)",
            "component_profile_name()", "component_outputs()", "component_identity()", "component_key()", "component_cache_entry()",
            "component_hit()", "mark_component()",
            "L32_REAL_PROGRAM_COMPONENT_CACHE_HIT", "L32_REAL_PROGRAM_COMPONENT_CACHE_MISS",
            "L32_REAL_PROGRAM_COMPONENT_CACHE_MARK", '"${BUILD_SCRIPT}" "${component}"',
            '"${BUILD_SCRIPT}" finalize', "declare -A keys", "declare -A decisions", "input_key", "sha256",
            'echo "decision ${component} ${decisions[${component}]} ${keys[${component}]}"',
            'cached="${entry}/outputs/${rel}"', 'cp -p "${cached}" "${tmp}"',
            "software/l32_real/lua-smoke.lua", "software/l32_real/sqlite-smoke.c",
            "software/l32_real/bash-smoke.sh", "software/l32_real/zlib-smoke.c", "software/l32_real/libpng-smoke.c",
            'libpng) printf', "LIBPNG_VERSION", "LIBPNG_SHA256", "zlib_sha256",
        ):
            self.assertIn(required, text)

        self.assertNotIn('COMPONENT_CACHE_DIR="${BUILD_DIR}/component-cache"', text)
        self.assertNotIn('hash_or_missing "${ROOT_DIR}/software/l32_real/manifest.env"', text)
        self.assertNotIn('hash_or_missing "${ROOT_DIR}/tools/ci/l32_real_programs_build.sh"', text)

    def test_cache_identity_is_profile_specific_and_audit_aware(self):
        text = CACHE.read_text()
        for required in (
            'printf \'profile=%s\\n\' "${L32_USERSPACE_PROFILE}"',
            'printf \'userspace_isa=%s\\n\' "${L32_USERSPACE_EFFECTIVE_ISA}"',
            'printf \'require_c=%s\\n\' "${L32_USERSPACE_REQUIRE_C}"',
            'hash_or_missing "${PROFILE_HELPER}"',
            'hash_or_missing "${PROFILE_AUDIT}"',
            'hash_or_missing "${WRAPPER_TOOL}"',
            'echo "profile ${L32_USERSPACE_PROFILE}"',
            'echo "isa ${L32_USERSPACE_EFFECTIVE_ISA}"',
            'echo "require_c ${L32_USERSPACE_REQUIRE_C}"',
            'grep -qx "profile=${L32_USERSPACE_PROFILE}" "${RESULT}"',
        ):
            self.assertIn(required, text)
        profile = PROFILE.read_text()
        self.assertIn('L32_USERSPACE_BUILD_SUFFIX=""', profile)
        self.assertIn('L32_USERSPACE_BUILD_SUFFIX="-rv32imac"', profile)
        self.assertIn("--require-c", PROFILE_AUDIT.read_text())

    def test_component_cache_preserves_profile_evidence_with_binary(self):
        text = CACHE.read_text()
        self.assertIn('printf \'%s\\n\' "${BUILD_DIR}/evidence/${profile_name}-profile.txt"', text)
        for profile_name in ("lua", "sqlite", "bash", "busybox-real", "zlib", "libpng"):
            self.assertIn(profile_name, text)
        self.assertIn('[[ -n "${expected}" && -s "${cached}" ]] || return 1', text)
        self.assertIn('echo "sha256 $(sha256sum "${cached}" | awk \'{print $1}\') ${rel}"', text)

    def test_finalize_does_not_refetch_sources_after_component_hits(self):
        text = BUILD.read_text()
        finalize = text.split("finalize() {", 1)[1].split("\n}\n\nmain() {", 1)[0]
        self.assertNotIn("fetch_verified", finalize)
        self.assertIn("source-sha256.txt", finalize)
        self.assertIn("sha256sum", finalize)
        self.assertIn('"${BUILD_DIR}/lua"', finalize)
        self.assertIn('"${BUILD_DIR}/libpng-smoke"', finalize)
        for pin in (
            "LUA_SHA256", "SQLITE_SHA256", "BASH_SHA256", "BUSYBOX_SHA256", "ZLIB_SHA256", "LIBPNG_SHA256",
        ):
            self.assertIn(pin, finalize)

    def test_libpng_component_is_real_and_dependency_local(self):
        build = BUILD.read_text()
        for required in (
            'fetch_verified "${LIBPNG_ARCHIVE}" "${LIBPNG_SHA256}"',
            'fetch_verified "${ZLIB_ARCHIVE}" "${ZLIB_SHA256}"',
            "./configure --host=riscv32-linux-musl --disable-shared --enable-static",
            "libpng16.la", ".libs/libpng16.a", "libpng-smoke.c", "libpng-smoke-build.log",
            'check_elf libpng "${BUILD_DIR}/libpng-smoke"',
        ):
            self.assertIn(required, build)
        smoke = LIBPNG_SOURCE.read_text()
        for required in (
            "png_create_write_struct", "png_set_IHDR", "PNG_ALL_FILTERS", "png_write_row",
            "png_create_read_struct", "png_read_row", "memcmp(row, expected", "crc32(",
            'memcmp(type, "IDAT", 4)', "png_longjmp", "L32_LIBPNG_REAL_PASS",
        ):
            self.assertIn(required, smoke)

    def test_cache_local_initializers_are_safe_under_nounset(self):
        text = CACHE.read_text()
        self.assertIn(
            'local component="$1" key="$2"\n  local entry marker\n  entry="$(component_cache_entry "${component}" "${key}")"',
            text,
        )
        self.assertIn(
            'local component="$1" key="$2"\n  local entry parent tmp_dir marker\n  entry="$(component_cache_entry "${component}" "${key}")"',
            text,
        )
        self.assertNotIn('local entry="$(component_cache_entry "${component}" "${key}")" marker=', text)
        self.assertNotIn('local entry="$(component_cache_entry "${component}" "${key}")" parent=', text)

    def test_busybox_vi_contract_keeps_search_replace_enabled(self):
        text = BUILD.read_text()
        self.assertIn("'CONFIG_FEATURE_VI_SEARCH'", text)
        self.assertIn("CONFIG_FEATURE_VI_COLON CONFIG_FEATURE_VI_SEARCH", text)

    def test_runtime_initramfs_provides_dev_null(self):
        text = INITRAMFS.read_text()
        self.assertIn("nod /dev/null 0666 0 0 c 1 3", text)
        self.assertIn("file /opt/l32/libpng-smoke ${LIBPNG_ELF} 0755 0 0", text)


if __name__ == "__main__":
    unittest.main()
