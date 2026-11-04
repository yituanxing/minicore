"""The costly four-variant Product FPGA PPA must survive isolated CI failures."""

from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-fpga-physical-pin-ppa.yml"
SCRIPT = ROOT / "tools/compare_product_fpga_pin_cost.sh"


class ProductPhysicalPpaResilience(unittest.TestCase):
    def test_four_variants_are_independent_and_preserve_evidence(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("fail-fast: false", workflow)
        self.assertIn("isa: [integer, floating]", workflow)
        self.assertIn("boundary: [probe, pins]", workflow)
        self.assertIn("needs: [validate-product-rtl, fpga-physical-synthesis]", workflow)
        report_job = workflow.split("  report-physical-ppa:\n", 1)[1]
        self.assertIn("    if: always()", report_job)
        self.assertIn("- name: Preserve partial variant evidence\n        if: always()", workflow)
        self.assertIn("- name: Upload independent synthesis artifact\n        if: always()", workflow)
        self.assertIn("actions/download-artifact@v4", workflow)
        self.assertIn("merge-multiple: false", workflow)
        self.assertIn("tools/ci/collect_product_physical_ppa.py", workflow)
        self.assertIn("${{ github.run_attempt }}", workflow)
        self.assertIn('test "$(cat "$dir/commit.sha")" = "$expected"', workflow)
        self.assertIn("tools/report_product_fpga_pin_cost.py", workflow)
        self.assertEqual(workflow.count("uses: actions/cache/restore@v4"), 2)
        self.assertEqual(workflow.count("uses: actions/cache/save@v4"), 1)
        self.assertIn("~/.cache/aethercore/mill-${{ env.MILL_VERSION }}", workflow)
        self.assertIn("Require validated Mill distribution before synthesis", workflow)
        self.assertIn("steps.mill-dist-cache.outputs.cache-hit", workflow)

    def test_one_source_recipe_supports_local_full_and_ci_single_variant(self):
        script = SCRIPT.read_text()
        self.assertIn('for isa in integer floating; do', script)
        self.assertIn('for boundary in probe pins; do', script)
        self.assertIn('selected_isa="${3:-}"', script)
        self.assertIn('selected_boundary="${4:-}"', script)
        self.assertIn('if [[ $# -eq 2 ]]; then', script)
        self.assertIn('report_product_fpga_pin_cost.py', script)
        self.assertIn('PRODUCT_FPGA_PHYSICAL_VARIANT_PASS:', script)

    def test_invalid_variant_is_rejected_before_expensive_build(self):
        result = subprocess.run(
            ["bash", str(SCRIPT), "small", "/unused", "integer", "invalid"],
            cwd=ROOT, capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("invalid ISA/boundary variant", result.stderr)

    def test_yosys_shutdown_evidence_is_bounded_and_keeps_failure_status(self):
        script = SCRIPT.read_text()
        self.assertIn("set -euo pipefail", script)
        self.assertIn('monitor_yosys_resources "$synth_pid"', script)
        self.assertIn('tee "$run_dir/yosys.log" | publish_yosys_progress', script)
        self.assertIn('if wait "$synth_pid"; then', script)
        self.assertIn('exit "$synth_status"', script)
        self.assertIn('rss_kib=', script)
        self.assertIn('oom_kill=', script)
        self.assertIn('stages <= 800', script)
        self.assertIn('diagnostics <= 30', script)
        result = subprocess.run(
            ["bash", "-n", str(SCRIPT)], cwd=ROOT,
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
