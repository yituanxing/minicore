"""Fail-closed contract for the isolated, not-yet-real-RTL-proved 256 gate."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools/ci/product_c1_arch_relay/qemu_paired256_source.py"
HOST = ROOT / "sim/product_c1_g5_qemu_paired256_diff_main.cpp"
WORKFLOW = ROOT / ".github/workflows/product-c1-g5-qemu-paired256-commit-shadow.yml"

sys.path.insert(0, str(SOURCE.parent))
import qemu_paired256_source as paired256  # noqa: E402


class Paired256Static(unittest.TestCase):
    def test_scope_does_not_mutate_paired64_or_sd1(self):
        script = SOURCE.read_text()
        host = HOST.read_text()
        workflow = WORKFLOW.read_text()
        self.assertIn("strict.COUNT = COUNT", script)
        self.assertIn("COUNT = 256", script)
        for marker in ("strict.RAM_SHA = strict.sha", "strict.MANIFEST_SHA = strict.sha",
                       "strict.TRACE_SHA = strict.sha", "strict.INSTRUCTION_SHA = strict.sha"):
            self.assertIn(marker, script)
        for marker in ("kPairedSteps.size() != 256", "io_commit_memWrite",
                       "committed.writeMasked", "commit_shadow_ddr_match=1",
                       "not_full_qemu_handoff=1 not_linux_boot=1 not_sd1=1"):
            self.assertIn(marker, host)
        self.assertIn("experiment/c1-arch-handoff-20261108", workflow)
        self.assertNotIn("design/rv64gc-product-architecture-20261103", workflow)
        self.assertNotIn("product_sd1_migrated_v2_followon", workflow)

    def test_qemu_source_must_be_exactly_256_or_fail(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "source members missing"):
                paired256.validate(Path(folder))
        r = subprocess.run([sys.executable, str(SOURCE), "--help"],
                           capture_output=True, text=True, check=False)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_one_capture_then_archive_before_true_rtl(self):
        text = WORKFLOW.read_text()
        self.assertIn("AETHERCORE_C1_FAST_QEMU_ORACLE_RETIRE_COUNT: '256'", text)
        self.assertIn("AETHERCORE_C1_FAST_QEMU_ORACLE_WALL_SECONDS: '180'", text)
        self.assertIn("SOURCE_RUN: '42484457962'", text)
        self.assertIn("SOURCE_ARTIFACT_ID: '13618662668'", text)
        order = ("Capture exactly 256 QEMU retires", "Seal exact QEMU 256-step RAM",
                 "Preserve immutable same-capture QEMU 256-step source before RTL",
                 "Elaborate real Product G5", "Compare exactly 256 true RTL retires")
        positions = [text.index(x) for x in order]
        self.assertEqual(positions, sorted(positions))
        self.assertIn("if: always()", text)

    def test_256_header_generator_compiles_independently(self):
        if shutil.which("g++") is None:
            self.skipTest("g++ unavailable")
        gprs = [0] * 32
        steps = [{"pc": 0x80000000 + i * 4, "after_pc": 0x80000004 + i * 4,
                  "raw": 0x13, "length": 4, "gprs": gprs}
                 for i in range(256)]
        reviewed = {"gprs": gprs, "steps": steps, "delta": {},
                    "source_ram_sha": "0" * 64, "post_step_ram_sha": "0" * 64}
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            original = paired256.strict.COUNT
            try:
                paired256.strict.COUNT = 256
                paired256.strict.header(folder / "paired_qemu_trace.h", reviewed)
                paired256.strict.gpr_header(folder / "qemu_captured_gprs.h")
            finally:
                paired256.strict.COUNT = original
            source = '#include "paired_qemu_trace.h"\n#include "qemu_captured_gprs.h"\nstatic_assert(kPairedSteps.size()==256);\nstatic_assert(kCapturedGprs.size()==32);\n'
            (folder / "header-test.cpp").write_text(source)
            result = subprocess.run(["g++", "-std=c++20", "-fsyntax-only",
                                     "-I", str(folder), str(folder / "header-test.cpp")],
                                    capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_real_qemu_source_if_available(self):
        path = os.getenv("AETHERCORE_QEMU_PAIRED256_SOURCE", "")
        if not path:
            self.skipTest("set AETHERCORE_QEMU_PAIRED256_SOURCE for one-capture 256 DDR")
        evidence = paired256.validate(Path(path))
        self.assertEqual(len(evidence["steps"]), 256)
        self.assertEqual(evidence["steps"][0]["raw"], 0x7179)
        self.assertEqual(evidence["source_ram_sha"], paired256.strict.RAM_SHA)


if __name__ == "__main__":
    unittest.main()
