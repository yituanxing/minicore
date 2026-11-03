"""Targeted GSIM cast workaround must not alter semantics outside one parser path."""
import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PATCH = ROOT / "tools/ci/rv64_pa32_gsim_cast_patch.py"
spec = importlib.util.spec_from_file_location("pa32_gsim_patch", PATCH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class CastPatchContract(unittest.TestCase):
    def test_casts_preserved_as_complete_expression_before_bits(self):
        fixture = """
ExpTree* dupTreeWithBits(ExpTree* tree, int _hi, int _lo) {
    switch(top->opType) {
        case OP_DSHR: case OP_DSHL: case OP_SUB: {
        }
    }
}
"""
        patched = module.patch_source(fixture)
        self.assertIn("case OP_ASUINT: case OP_ASSINT:", patched)
        self.assertIn("bits->addChild(top)", patched)
        self.assertEqual(patched.count("case OP_DSHR: case OP_DSHL: case OP_SUB:"), 1)
        with self.assertRaises(ValueError):
            module.patch_source(patched)

    def test_drift_is_rejected(self):
        with self.assertRaises(ValueError):
            module.patch_source("case OP_DSHR: case OP_DSHL: case OP_SUB: {")
        with self.assertRaises(ValueError):
            module.patch_source(module.FUNCTION + " }")

    def test_orig_source_is_never_edited(self):
        script = (ROOT/"tools/ci/rv64_pa32_gsim_source_patch.sh").read_text()
        self.assertIn("45a73d6afb9b1893781cfb57ccd54096987bb23d", script)
        self.assertIn("git -C \"$src\" checkout -q --detach FETCH_HEAD", script)
        self.assertIn('python3 tools/ci/rv64_pa32_gsim_cast_patch.py "$src/src/splitNodes.cpp"', script)
        self.assertIn("gsim-scoped.patch", script)
        self.assertIn("AETHERCORE_PA32_GSIM_PATCHED_FRONTEND_PASS", script)

if __name__ == "__main__":
    unittest.main()
