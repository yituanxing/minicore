"""Artifact collection must support both full and failed-jobs-only reruns."""

from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "ci"))
from collect_product_physical_ppa import collect  # noqa: E402


class ProductFpgaPpaArtifacts(unittest.TestCase):
    def test_reuse_successful_lanes_from_previous_attempt(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "download"
            target = root / "collected"

            def artifact(isa, boundary, attempt, complete=True):
                parent = source / (
                    f"product-physical-fpga-ppa-small-{isa}-{boundary}-123-{attempt}"
                ) / "small" / isa / boundary
                parent.mkdir(parents=True)
                (parent / "commit.sha").write_text("abc\n")
                (parent / "yosys.log").write_text("yosys\n")
                if complete:
                    (parent / "metrics.json").write_text(f'{{"attempt": {attempt}}}\n')

            for isa in ("integer", "floating"):
                for boundary in ("probe", "pins"):
                    artifact(isa, boundary, 1)
            artifact("floating", "pins", 2)

            collect("small", "123", source, target)
            self.assertEqual((target / "small/integer/probe/attempt.txt").read_text(), "1\n")
            self.assertEqual((target / "small/floating/pins/attempt.txt").read_text(), "2\n")
            self.assertIn('"attempt": 2', (target / "small/floating/pins/metrics.json").read_text())

            # Never hide a new failed synthesis behind a stale successful one.
            artifact("floating", "pins", 3, complete=False)
            with self.assertRaisesRegex(ValueError, "attempt=3: missing metrics.json"):
                collect("small", "123", source, root / "failed")

    def test_rejects_missing_lane(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "artifacts"
            source.mkdir()
            with self.assertRaisesRegex(ValueError, "no synthesis artifact"):
                collect("small", "123", source, Path(temp) / "out")


if __name__ == "__main__":
    unittest.main()
