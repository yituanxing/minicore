from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
ENSURE = ROOT / "tools" / "ensure_rv32_nemu_single_step.sh"
FAST = ROOT / ".github" / "workflows" / "fast-gate.yml"
FULL_BUILD = ROOT / "tools" / "ci" / "build_rv32_references.sh"


class Rv32NemuReferenceCacheTest(unittest.TestCase):
    def test_ensure_script_has_valid_exact_identity_contract(self) -> None:
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
        self.assertIn("e1e18bec22a1e6a19dbb300b43063ed5d3216a8d9f6ccf6400355d4fb897de9e", text)
        self.assertIn("$HOME/.cache/aethercore/references", text)
        self.assertIn("AETHERCORE_RV32_NEMU_CANDIDATE", text)
        self.assertIn("rv32-nemu-so.txt", text)
        self.assertIn("NEMU_SINGLE_STEP=1", text)
        self.assertIn("probe_rv32_nemu_deterministic.sh", text)
        self.assertIn("aethercore_rv32_nemu_cache=hit", text)
        self.assertIn("aethercore_rv32_nemu_cache=seeded", text)
        self.assertIn("aethercore_rv32_nemu_cache=build", text)

    def test_fast_gate_resolves_reference_without_workspace_path_dependency(self) -> None:
        text = FAST.read_text(encoding="utf-8")
        exact_step = text[text.index("Incremental FreeRTOS WFI exact DiffTest") :]
        self.assertIn(
            'rv32_nemu_so="$(bash tools/ensure_rv32_nemu_single_step.sh)"',
            exact_step,
        )
        self.assertNotIn('path_file="build/ci/rv32-nemu-so.txt"', exact_step)
        self.assertIn("STALL_PERIOD=\"$stall\" run-difftest", exact_step)

    def test_full_gate_seeds_the_same_persistent_reference(self) -> None:
        text = FULL_BUILD.read_text(encoding="utf-8")
        self.assertIn("AETHERCORE_RV32_NEMU_CANDIDATE=\"$reference_so\"", text)
        self.assertIn("bash tools/ensure_rv32_nemu_single_step.sh", text)
        self.assertIn("printf '%s\\n' \"$cached_reference\" > \"$PATH_FILE\"", text)


if __name__ == "__main__":
    unittest.main()
