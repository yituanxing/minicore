from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "tools/ci/l32_real_programs_build.sh"
CACHE = ROOT / "tools/ci/l32_real_programs_cache.sh"
INITRAMFS = ROOT / "tools/ci/l32_busybox_initramfs_build.sh"


class L32RealProgramComponentCacheContract(unittest.TestCase):
    def test_builder_exposes_independent_component_modes_and_recipe_hashes(self):
        text = BUILD.read_text()
        for required in (
            "build_lua()", "build_sqlite()", "build_bash()", "build_busybox()", "build_zlib()",
            "recipe_hash()", "finalize()", "recipe-hash", "all|lua|sqlite|bash|busybox|zlib|finalize",
            "declare -f build_lua", "declare -f build_sqlite", "declare -f build_bash",
            "declare -f build_busybox", "declare -f build_zlib",
        ):
            self.assertIn(required, text)

        hashes = {
            component: subprocess.check_output(
                [str(BUILD), "recipe-hash", component], text=True
            ).strip()
            for component in ("lua", "sqlite", "bash", "busybox", "zlib")
        }
        self.assertEqual(len(set(hashes.values())), 5)
        for digest in hashes.values():
            self.assertRegex(digest, r"^[0-9a-f]{64}$")

    def test_cache_qualifies_and_rebuilds_components_independently(self):
        text = CACHE.read_text()
        for required in (
            'COMPONENT_CACHE_DIR="${BUILD_DIR}/component-cache"',
            "components=(lua sqlite bash busybox zlib)",
            "component_outputs()", "component_identity()", "component_key()", "component_hit()", "mark_component()",
            "L32_REAL_PROGRAM_COMPONENT_CACHE_HIT", "L32_REAL_PROGRAM_COMPONENT_CACHE_MISS",
            "L32_REAL_PROGRAM_COMPONENT_CACHE_MARK", '"${BUILD_SCRIPT}" "${component}"',
            '"${BUILD_SCRIPT}" finalize', "declare -A keys", "input_key", "sha256",
            "software/l32_real/lua-smoke.lua", "software/l32_real/sqlite-smoke.c",
            "software/l32_real/bash-smoke.sh", "software/l32_real/zlib-smoke.c",
        ):
            self.assertIn(required, text)

        # A whole-file recipe/manifest hash would make an unrelated component
        # change invalidate every output, defeating the component cache.
        self.assertNotIn('hash_or_missing "${ROOT_DIR}/software/l32_real/manifest.env"', text)
        self.assertNotIn('hash_or_missing "${ROOT_DIR}/tools/ci/l32_real_programs_build.sh"', text)

    def test_busybox_vi_contract_keeps_search_replace_enabled(self):
        text = BUILD.read_text()
        self.assertIn("'CONFIG_FEATURE_VI_SEARCH'", text)
        self.assertIn("CONFIG_FEATURE_VI_COLON CONFIG_FEATURE_VI_SEARCH", text)

    def test_runtime_initramfs_provides_dev_null(self):
        self.assertIn("nod /dev/null 0666 0 0 c 1 3", INITRAMFS.read_text())


if __name__ == "__main__":
    unittest.main()
