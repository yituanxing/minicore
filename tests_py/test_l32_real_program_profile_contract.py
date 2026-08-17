from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "tools" / "ci" / "l32_real_programs_build.sh"
PROFILE = ROOT / "tools" / "ci" / "l32_userspace_profile.sh"
AUDIT = ROOT / "tools" / "ci" / "riscv_elf_profile.py"
WRAPPER = ROOT / "tools" / "ci" / "l32_musl_link_wrapper.sh"


class L32RealProgramProfileContract(unittest.TestCase):
    def test_builder_consumes_one_userspace_profile_identity(self) -> None:
        text = BUILD.read_text()
        for required in (
            'source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"',
            'BUILD_DIR="${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}"',
            'MUSL_CC="${L32_USERSPACE_MUSL_WRAPPER}"',
            'PROFILE_AUDIT="${ROOT_DIR}/tools/ci/riscv_elf_profile.py"',
            'WRAPPER_TOOL="${ROOT_DIR}/tools/ci/l32_musl_link_wrapper.sh"',
            'grep -qx "profile=${L32_USERSPACE_PROFILE}"',
            '"${WRAPPER_TOOL}" >/dev/null',
        ):
            self.assertIn(required, text)

    def test_every_runtime_elf_uses_generic_profile_audit(self) -> None:
        text = BUILD.read_text()
        self.assertIn("c_policy=(--forbid-c)", text)
        self.assertIn("c_policy=(--require-c)", text)
        for name in ("lua", "sqlite", "bash", "busybox-real", "zlib", "libpng"):
            self.assertIn(f"check_elf {name}", text)
        self.assertIn('"${EVIDENCE_DIR}/${name}-profile.txt"', text)

    def test_recipe_hash_owns_profile_audit_recipe(self) -> None:
        text = BUILD.read_text()
        self.assertIn("declare -f check_elf", text)
        for component in ("lua", "sqlite", "bash", "busybox", "zlib", "libpng"):
            self.assertIn(f"{component}) declare -f build_", text)

    def test_finalize_records_profile_without_changing_workload_set(self) -> None:
        text = BUILD.read_text()
        for required in (
            'echo "profile=${L32_USERSPACE_PROFILE}"',
            'echo "isa=${L32_USERSPACE_EFFECTIVE_ISA}"',
            'echo "abi=${L32_USERSPACE_ABI}"',
            'echo "require_c=${L32_USERSPACE_REQUIRE_C}"',
            "L32_REAL_PROGRAMS_BUILD_RESULT: status=PASS",
            "lua lua-smoke.lua sqlite-smoke bash bash-smoke.sh busybox-real zlib-smoke libpng-smoke",
        ):
            self.assertIn(required, text)

    def test_profile_auditor_and_wrapper_are_generic_dependencies(self) -> None:
        profile = PROFILE.read_text()
        audit = AUDIT.read_text()
        wrapper = WRAPPER.read_text()
        self.assertIn('L32_USERSPACE_BUILD_SUFFIX="-rv32imac"', profile)
        self.assertIn("--require-c", audit)
        self.assertIn("--forbid-c", audit)
        self.assertIn("L32_MUSL_LINK_WRAPPER_RESULT: status=PASS", wrapper)


if __name__ == "__main__":
    unittest.main()
