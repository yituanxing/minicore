from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
REFERENCE_BUILDER = ROOT / "tools" / "ci" / "build_rv32_references.sh"
CACHE_HELPER = ROOT / "tools" / "ensure_git_revision.sh"
DETERMINISTIC_PROBE = ROOT / "tools" / "probe_rv32_nemu_deterministic.sh"


class Rv32ReferenceCacheTest(unittest.TestCase):
    def test_shell_scripts_parse(self) -> None:
        for script in (REFERENCE_BUILDER, CACHE_HELPER, DETERMINISTIC_PROBE):
            with self.subTest(script=script.name):
                result = subprocess.run(
                    ["bash", "-n", str(script)],
                    cwd=ROOT,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False,
                )
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_binary_cache_is_keyed_and_validated_by_frozen_outputs(self) -> None:
        text = REFERENCE_BUILDER.read_text(encoding="utf-8")
        self.assertIn('REVISION="8601834e4889e6bf3b6113eb5f824ba7689126f5"', text)
        self.assertIn('OPT_SHA256="0e9dc52aeb2f02c399beaa6c5415ff2f4b6c54cfc9aec84f5be0282fe608cd8a"', text)
        self.assertIn('SINGLE_SHA256="e1e18bec22a1e6a19dbb300b43063ed5d3216a8d9f6ccf6400355d4fb897de9e"', text)
        self.assertIn('CACHE_KEY="${REVISION}-${OPT_SHA256:0:16}-${SINGLE_SHA256:0:16}"', text)
        self.assertIn('sha256sum "$so"', text)
        self.assertIn('grep -q "^revision=${REVISION}$"', text)
        self.assertIn('grep -q "^single_step=${expected_single_step}$"', text)
        self.assertIn('grep -q "^perf_opt=${expected_perf_opt}$"', text)
        self.assertIn('RV32 NEMU binary cache hit:', text)
        self.assertIn('RV32 NEMU binary cache miss:', text)
        self.assertIn('RV32 NEMU binary cache stored:', text)
        self.assertIn('validate_reference "$stage/optimized"', text)
        self.assertIn('validate_reference "$stage/single-step"', text)

    def test_cache_hit_still_materializes_expected_evidence_and_path(self) -> None:
        text = REFERENCE_BUILDER.read_text(encoding="utf-8")
        self.assertIn('cp -a "$cache/evidence" "$destination/evidence"', text)
        self.assertIn('cp -a "$cache/nemu/build" "$destination/nemu/build"', text)
        self.assertIn('materialize_reference "$OPT_CACHE" "$OPT_ARCHIVE_DIR"', text)
        self.assertIn('materialize_reference "$SINGLE_CACHE" "$PROBE_DIR"', text)
        self.assertIn('write_reference_path', text)


if __name__ == "__main__":
    unittest.main()
