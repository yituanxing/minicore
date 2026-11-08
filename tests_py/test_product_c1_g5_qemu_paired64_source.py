"""Fail-closed tests for the authentic same-artifact QEMU 64-step subset oracle.

Only checks QEMU source qualification and generated C++ oracle. A successful
test is not proof that Product G5 RTL matched QEMU; CI must run the RTL host.
"""

import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE_PATH = ROOT / "tools/ci/product_c1_arch_relay/qemu_paired64_source.py"
TOOLS = SOURCE_PATH.parent

import sys
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location("qemu_paired64_source", SOURCE_PATH)
SOURCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SOURCE)


class Paired64SourceTest(unittest.TestCase):
    def test_digest_pins_are_distinct_from_old_g5_ram(self):
        self.assertEqual(SOURCE.COUNT, 64)
        self.assertEqual(SOURCE.RAM_SHA,
                         "c7912452a249352d16fdda53a30450f3a457773e9805182011bd51b255b20983")
        self.assertNotEqual(SOURCE.RAM_SHA,
                            "42129f32725e43c1db5bb38b400bb7d593bd92936271d0250e0d5273547810f2")

    def test_generated_gpr_alias_refers_to_current_oracle_not_old_ram(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            SOURCE.gpr_header(root / "qemu_captured_gprs.h")
            self.assertIn("kPairedInitialGprs",
                          (root / "qemu_captured_gprs.h").read_text())

    def test_synthetic_header_compiles(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            state = {
                "gprs": [0] * 32,
                "steps": [{"pc": 0x80000000, "after_pc": 0x80000002,
                           "raw": 0x7179, "length": 2, "gprs": [0] * 32}
                          for _ in range(64)],
                "delta": {0x80001000: 42},
            }
            SOURCE.header(root / "paired_qemu_trace.h", state)
            SOURCE.gpr_header(root / "qemu_captured_gprs.h")
            gpp = shutil.which("g++") or shutil.which("clang++")
            if not gpp:
                self.skipTest("no C++ compiler")
            subprocess.run([gpp, "-std=c++20", "-fsyntax-only", "-x", "c++",
                            "-include", str(root / "qemu_captured_gprs.h"),
                            os.devnull], check=True, capture_output=True)

    def test_absent_or_malformed_source_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "missing immutable"):
                SOURCE.validate(Path(tmp))

    def test_paired_real_source_end_ram_sha_verified_if_available(self):
        source = os.environ.get("AETHERCORE_QEMU_PAIRED64_SOURCE")
        if not source:
            self.skipTest("set AETHERCORE_QEMU_PAIRED64_SOURCE for full SHA-authenticated fixture")
        result = SOURCE.validate(Path(source))
        self.assertEqual(result["number_of_steps"], 64)
        self.assertEqual(len(result["delta"]), 94)
        self.assertEqual(result["post_step_ram_sha"],
                         "73c07b3d05057060d3aa1ac05ec35d8d5e8883694554b9b32c8a465267a09fe6")


if __name__ == "__main__":
    unittest.main()
