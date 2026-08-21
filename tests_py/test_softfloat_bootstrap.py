import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BOOTSTRAP = ROOT / "tools" / "ensure_berkeley_softfloat.sh"
HISTORICAL = ROOT / "tools" / "probe_rv32_nemu_historical.sh"


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

    def test_historical_shared_reference_bootstraps_config_in_shared_mode(self) -> None:
        text = HISTORICAL.read_text(encoding="utf-8")
        self.assertIn("CONFIG_SHARE=y", text)
        self.assertIn(
            'make -C "$source_dir" CONFIG_SHARE=y "$config_name"',
            text,
        )
        self.assertIn(
            "ERROR: exact RV32 NEMU config generation failed",
            text,
        )
        self.assertIn('cat "$config_log" >&2', text)
        self.assertNotIn("libsdl2-dev", text)


if __name__ == "__main__":
    unittest.main()
