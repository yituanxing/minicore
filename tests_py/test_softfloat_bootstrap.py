import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BOOTSTRAP = ROOT / "tools" / "ensure_berkeley_softfloat.sh"
HISTORICAL = ROOT / "tools" / "probe_rv32_nemu_historical.sh"
ENSURE_REFERENCE = ROOT / "tools" / "ensure_rv32_nemu_single_step.sh"


class SoftFloatBootstrapTest(unittest.TestCase):
    def test_bootstrap_pins_and_validates_one_exact_revision(self) -> None:
        text = BOOTSTRAP.read_text(encoding="utf-8")
        match = re.search(r'^SOFTFLOAT_REVISION="([0-9a-f]{40})"$', text, re.MULTILINE)
        self.assertIsNotNone(match)
        self.assertEqual(
            match.group(1),
            "a0c6494cdc11865811dec815d5c0049fba9d82a8",
        )
        self.assertIn('rev-parse HEAD', text)
        self.assertIn('COPYING.txt', text)
        self.assertIn('fetch --quiet --depth=1 origin "$SOFTFLOAT_REVISION"', text)
        self.assertIn('git -c http.version=HTTP/1.1', text)
        self.assertIn('for attempt in 1 2 3 4', text)

    def test_historical_nemu_tree_is_preseeded_before_make(self) -> None:
        text = HISTORICAL.read_text(encoding="utf-8")
        bootstrap = text.index("ensure_berkeley_softfloat.sh")
        first_make = text.index('make -C "$source_dir"')
        self.assertLess(bootstrap, first_make)
        self.assertIn('softfloat-revision.txt', text)
        self.assertIn('softfloat_revision=$softfloat_revision', text)

    def test_historical_shared_reference_isolates_host_config_linker_flags(self) -> None:
        text = HISTORICAL.read_text(encoding="utf-8")
        self.assertIn("CONFIG_SHARE=y", text)
        self.assertIn(
            'env -u LDFLAGS make -C "$source_dir" "$config_name"',
            text,
        )
        self.assertNotIn(
            'make -C "$source_dir" CONFIG_SHARE=y "$config_name"',
            text,
        )
        self.assertIn(
            "ERROR: exact RV32 NEMU config generation failed",
            text,
        )
        self.assertIn('cat "$config_log" >&2', text)
        self.assertNotIn("libsdl2-dev", text)

    def test_historical_reference_freezes_recipe_provenance_not_runner_path_bytes(self) -> None:
        probe = HISTORICAL.read_text(encoding="utf-8")
        ensure = ENSURE_REFERENCE.read_text(encoding="utf-8")

        for digest in (
            "9221c1979f056b978179d36404ab3801aa474b67560efcb8093d2da0fef4791a",
            "52ed03a1c6e9c57b6fac319d245c5e0af31589f7d305519cea6eabee0e68ca56",
            "a218e0ee1b15a461ff27e1bda133d43bf21ccf14977463faf4b872f071c788fa",
        ):
            self.assertIn(digest, probe)
            self.assertIn(digest, ensure)

        self.assertIn('host-toolchain.txt', probe)
        self.assertIn('derived_defconfig_sha256=', probe)
        self.assertIn('generated_config_sha256=', probe)
        self.assertIn('build_composition_sha256=', probe)
        self.assertIn('CACHE_FORMAT="rv32-nemu-single-step-v2"', ensure)
        self.assertIn('validate_reference "$REFERENCE_SO" "$EVIDENCE_DIR"', ensure)
        self.assertIn('prefix_matches=true', ensure)
        self.assertIn('guard_matches=true', ensure)
        self.assertIn('memory_roundtrip_matches=true', ensure)
        self.assertNotIn('EXPECTED_SHA256=', ensure)
        self.assertNotIn('canonical exact RV32 NEMU SHA256 changed', ensure)

    def test_candidate_cache_seed_requires_matching_evidence(self) -> None:
        ensure = ENSURE_REFERENCE.read_text(encoding="utf-8")
        self.assertIn('AETHERCORE_RV32_NEMU_CANDIDATE_EVIDENCE', ensure)
        self.assertIn('cp -a "$candidate_evidence/." "$staging/evidence/"', ensure)
        self.assertIn('reference_sha256=$actual_sha', ensure)
        self.assertIn('first_sha256=$actual_sha', ensure)
        self.assertIn('second_sha256=$actual_sha', ensure)


if __name__ == "__main__":
    unittest.main()