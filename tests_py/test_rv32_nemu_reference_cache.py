from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
ENSURE = ROOT / "tools" / "ensure_rv32_nemu_single_step.sh"
FAST = ROOT / ".github" / "workflows" / "fast-gate.yml"
FULL_BUILD = ROOT / "tools" / "ci" / "build_rv32_references.sh"


class Rv32NemuReferenceCacheTest(unittest.TestCase):
    def test_ensure_script_has_valid_recipe_and_cache_integrity_contract(self) -> None:
        syntax = subprocess.run(
            ["bash", "-n", str(ENSURE)],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(syntax.returncode, 0, syntax.stderr)
        text = ENSURE.read_text(encoding="utf-8")
        self.assertIn("8601834e4889e6bf3b6113eb5f824ba7689126f5", text)
        self.assertIn("a0c6494cdc11865811dec815d5c0049fba9d82a8", text)
        self.assertIn("9221c1979f056b978179d36404ab3801aa474b67560efcb8093d2da0fef4791a", text)
        self.assertIn("52ed03a1c6e9c57b6fac319d245c5e0af31589f7d305519cea6eabee0e68ca56", text)
        self.assertIn("a218e0ee1b15a461ff27e1bda133d43bf21ccf14977463faf4b872f071c788fa", text)
        self.assertIn("$HOME/.cache/aethercore/references", text)
        self.assertIn('CACHE_FORMAT="rv32-nemu-single-step-v2"', text)
        self.assertIn("AETHERCORE_RV32_NEMU_CANDIDATE", text)
        self.assertIn("AETHERCORE_RV32_NEMU_CANDIDATE_EVIDENCE", text)
        self.assertIn("NEMU_SINGLE_STEP=1", text)
        self.assertIn("probe_rv32_nemu_deterministic.sh", text)
        self.assertIn("aethercore_rv32_nemu_cache=hit", text)
        self.assertIn("aethercore_rv32_nemu_cache=seeded", text)
        self.assertIn("aethercore_rv32_nemu_cache=build", text)
        self.assertIn('reference_sha256=$actual_sha', text)
        self.assertNotIn("EXPECTED_SHA256=", text)

    def test_cold_build_validates_frozen_recipe_instead_of_absolute_runner_path(self) -> None:
        text = ENSURE.read_text(encoding="utf-8")
        self.assertIn(
            'CANONICAL_WORK_DIR="${AETHERCORE_RV32_NEMU_WORK_DIR:-$ROOT/build/rv32-nemu-probe}"',
            text,
        )
        self.assertIn('find "$CANONICAL_WORK_DIR/nemu/build"', text)
        self.assertIn(
            'validate_reference "$candidate" "$CANONICAL_WORK_DIR/evidence"',
            text,
        )
        self.assertIn("absolute source path", text)
        self.assertIn("full runtime DiffTest", text)
        self.assertNotIn("canonical exact RV32 NEMU SHA256 changed", text)
        self.assertNotIn(".rv32-nemu-build.XXXXXX", text)

    def test_fast_gate_resolves_reference_without_workspace_path_dependency(self) -> None:
        text = FAST.read_text(encoding="utf-8")
        exact_step = text[text.index("Incremental FreeRTOS WFI exact DiffTest") :]
        self.assertIn(
            'rv32_nemu_so="$(bash tools/ensure_rv32_nemu_single_step.sh)"',
            exact_step,
        )
        self.assertNotIn('path_file="build/ci/rv32-nemu-so.txt"', exact_step)
        self.assertIn("STALL_PERIOD=\"$stall\" run-difftest", exact_step)

    def test_full_gate_seeds_the_same_persistent_reference_with_evidence(self) -> None:
        text = FULL_BUILD.read_text(encoding="utf-8")
        self.assertIn("AETHERCORE_RV32_NEMU_CANDIDATE=\"$reference_so\"", text)
        self.assertIn(
            "AETHERCORE_RV32_NEMU_CANDIDATE_EVIDENCE=\"$PROBE_DIR/evidence\"",
            text,
        )
        self.assertIn("bash tools/ensure_rv32_nemu_single_step.sh", text)
        self.assertIn("printf '%s\\n' \"$cached_reference\" > \"$PATH_FILE\"", text)


if __name__ == "__main__":
    unittest.main()