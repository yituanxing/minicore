"""Independent Product cache-profile XC7 mapping is fail-closed, not FPGA proof."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest

from tools.ci.product_fpga_xc7_cache_ab_compare import ComparisonError, compare


ROOT = Path(__file__).resolve().parents[1]
EXPORT = ROOT / "tools/ci/product_fpga_xc7_cache_ab.sh"
MAPPER = ROOT / "tools/ci/product_fpga_xc7_unplaced.sh"
WORKFLOW = ROOT / ".github/workflows/product-fpga-xc7-cache-ab.yml"


def _fixture(directory: Path, sha: str, entries: int, *, luts: int = 10, version: str = "Yosys mock"):
    (directory / "rtl").mkdir(parents=True)
    sv = directory / "rtl/Core.sv"
    sv.write_text("module AetherCoreProductFpgaUartDdrBoard; endmodule\n")
    (directory / "rtl.sha256").write_text(f"{hashlib.sha256(sv.read_bytes()).hexdigest()}  Core.sv\n")
    bundle = hashlib.sha256((directory / "rtl.sha256").read_bytes()).hexdigest()
    emitter = "aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1CacheAbRv64gc"
    export = {"status": "RTL_EXPORT_PASS", "git_sha": sha,
              "top": "AetherCoreProductFpgaUartDdrBoard", "emitter": emitter,
              "cache_i_entries": entries, "cache_d_entries": entries,
              "geometry": "small", "isa": "rv64gc", "abi": "lp64d",
              "spi_sd_enabled": True, "clock_frequency_hz": 50_000_000,
              "vendor_ddr_phy_included": False, "pll_included": False,
              "board_xdc_included": False, "rtl_file_hashes_sha256": bundle, "rtl_files": 1}
    (directory / "manifest.json").write_text(json.dumps(export))
    mapped = directory / "mapped"
    mapped.mkdir()
    (mapped / "result.json").write_text(json.dumps({
        "status": "XC7_UNPLACED_MAPPING_PASS", "git_sha": sha,
        "top": export["top"], "emitter": emitter,
        "cache_i_entries": entries, "cache_d_entries": entries,
        "rtl_file_hashes_sha256": bundle, "yosys_version": version,
        "placed": False, "routed": False, "physical_timing_qualified": False}))
    (mapped / "hierarchy.json").write_text(json.dumps({
        "status": "XC7_MAPPED_HIERARCHY_PARSED", "top": export["top"],
        "total_luts": luts, "total_ffs": 20, "total_dsp48e1": 4,
        "primitive_counts": {"LUT6": luts, "RAM32M": 3, "RAMB18E1": 0}}))


class ProductXc7CacheAbContract(unittest.TestCase):
    def test_opt_in_sequential_same_sha_workflow(self):
        wf = WORKFLOW.read_text()
        self.assertIn("workflow_dispatch:", wf)
        self.assertIn("types: [labeled]", wf)
        self.assertIn("head.repo.full_name == github.repository", wf)
        self.assertIn("github.event.label.name == 'product-fpga-xc7-cache-ab'", wf)
        self.assertIn("persist-credentials: false", wf)
        self.assertIn("github.ref == 'refs/heads/work/xc7-cache-ab-isolated-20261109'", wf)
        self.assertEqual(wf.count("\n  push:\n"), 1)
        self.assertIn("branches: [work/xc7-cache-ab-isolated-20261109]", wf)
        self.assertIn("paths: [.github/workflows/product-fpga-xc7-cache-ab.yml]", wf)
        self.assertIn("for entries in 64 256; do", wf)
        self.assertNotIn("for entries in 64 256 512; do", wf)
        self.assertIn("product_fpga_xc7_cache_ab_compare.py", wf)
        self.assertIn("if: always()", wf)
        self.assertIn("tools/ci/product_fpga_xc7_unplaced.sh", wf)
        source = EXPORT.read_text()
        self.assertIn("ElaborateProductFpgaUartDdrSpiSd1CacheAbRv64gc", source)
        self.assertIn("cache_i_entries:$entries, cache_d_entries:$entries", source)
        self.assertIn("--target-dir \"$rtl_dir\"", source)
        self.assertIn("AETHERCORE_XC7_CACHE_AB_ENTRIES", MAPPER.read_text())

    def test_bad_mode_and_profile_fail_without_any_real_synthesis(self):
        with TemporaryDirectory() as temp:
            for bad in (("prepare", "128"), ("map", "1024"), ("synth", "64")):
                result = subprocess.run(["bash", str(EXPORT), *bad, temp],
                                        cwd=ROOT, capture_output=True, text=True)
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)

    def test_comparison_rejects_mismatched_identity_or_missing_success(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            _fixture(root / "cache-64", "same-source", 64, luts=100)
            _fixture(root / "cache-256", "same-source", 256, luts=114)
            report = compare(root)
            self.assertEqual(report["delta_2kib_minus_512b"]["luts"], 14)
            self.assertEqual(report["delta_2kib_minus_512b"]["ram32m"], 0)
            self.assertFalse(report["fmax_qualified"])
            modified = root / "cache-256/mapped/result.json"
            value = json.loads(modified.read_text())
            value["git_sha"] = "unrelated-source"
            modified.write_text(json.dumps(value))
            with self.assertRaisesRegex(ComparisonError, "inconsistent git_sha"):
                compare(root)
            value["git_sha"] = "same-source"
            value["status"] = "XC7_UNPLACED_MAPPING_FAILED"
            modified.write_text(json.dumps(value))
            with self.assertRaisesRegex(ComparisonError, "missing real mapping"):
                compare(root)

    def test_original_production_export_cannot_silently_be_accepted_as_ab(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
            _fixture(root / "cache-256", sha, 256)
            fixture = root / "cache-256"
            fakebin = root / "bin"
            fakebin.mkdir()
            (fakebin / "yosys").write_text("#!/bin/sh\necho 'mock Yosys'\n")
            (fakebin / "yosys").chmod(0o755)
            env = dict(os.environ, PATH=f"{fakebin}:{os.environ['PATH']}")
            # Invalid size/producer is rejected before launching fake Yosys.
            for size in ("64", "512"):
                env["AETHERCORE_XC7_CACHE_AB_ENTRIES"] = size
                result = subprocess.run(["bash", str(MAPPER), str(fixture), str(root / f"bad-{size}")],
                                        env=env, cwd=ROOT, capture_output=True, text=True)
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertIn("not pinned", result.stderr)
            env.pop("AETHERCORE_XC7_CACHE_AB_ENTRIES")
            result = subprocess.run(["bash", str(MAPPER), str(fixture), str(root / "bad-production")],
                                    env=env, cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
