"""Safety tests for inert H8472->8492->8500 Host-v3 RTL relay (no guest cycles)."""
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_migrated_v3_h_followon.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-v3-h-8472-to-8500-INERT-DRAFT.yml"


class HRelaySafety(unittest.TestCase):
    def test_plan_no_guest_and_locked_execution(self):
        env = dict(os.environ)
        for key in ("AETHERCORE_SD1_V3_H_RELAY_EXECUTE",
                    "AETHERCORE_SD1_RELAY_CONFIRM",
                    "AETHERCORE_SD1_V3_H_8472_HOST_SHA"):
            env.pop(key, None)
        plan = subprocess.run(["bash", str(SCRIPT), "--plan"], cwd=ROOT,
                              env=env, text=True, capture_output=True)
        self.assertEqual(plan.returncode, 0, plan.stderr)
        self.assertIn("no_guest_cycles=1", plan.stdout)
        self.assertIn("stages=8492,8500", plan.stdout)
        target = ROOT / "build/product-sd1-migrated-v3-8p5b-h-followon"
        before = target.exists()
        bad = subprocess.run(["bash", str(SCRIPT), "--execute-reviewed-relay",
                              str(ROOT / "unavailable"), "8492"], cwd=ROOT,
                             env=env, text=True, capture_output=True)
        self.assertEqual(bad.returncode, 16)
        self.assertIn("qualification", bad.stderr)
        self.assertEqual(before, target.exists())

    def test_two_different_deltas_and_strict_v3_cap(self):
        src = SCRIPT.read_text()
        for term in ("^(8492|8500)$", "8492) SOURCE_M=8472; DELTA=20000000",
                     "8500) SOURCE_M=8492; DELTA=8000000",
                     "-le 8500000000", '"$START" "$DELTA"',
                     "delta=$DELTA", "int(sys.argv[6])", "source_cycles",
                     "PRODUCT_SD1_V3_H_REAL_RTL_STAGE_SAVED",
                     "guest_uart_service_verified':False",
                     "REVIEWED_G_8472M_SUCCESS_SHA_AUDITED_AND_V3_QUALIFIED_28M_PASS",
                     "AETHERCORE_SD1_V3_H_8472_HOST_SHA",
                     "AETHERCORE_SD1_V3_H_8472_RTL_SHA",
                     "AETHERCORE_SD1_V3_H_8472_CARD_SHA"):
            self.assertIn(term, src)
        self.assertNotIn("TARGET_M-20", src)

    def test_reviewed_g_whole_run_and_five_sha_seals_required(self):
        wf = WORKFLOW.read_text()
        self.assertIn("product-sd1-v3-reviewed-8472-to-8500-two-stages", wf)
        self.assertIn("G_RUN: '42544874400'", wf)
        self.assertIn("product-sd1-v3-g-8372-to-8472.yml", wf)
        self.assertIn("G_real_product_rtl_five_stages", wf)
        self.assertIn("Verify five complete G checkpoints and no false Debian PASS", wf)
        self.assertIn(".run_attempt==1", wf)
        self.assertIn('.conclusion=="success"', wf)
        self.assertIn("H_G_5_STAGE_ZIP_SHA_FULL_HOST_RTL_CHAIN_ATTESTED_NO_GUEST_CYCLES", wf)
        attested = {
            8392: ('13642728790', '9021ec36cce5478e9e8c13c5aee82e8eb8af5d21762ccf05fb6deabeb3d6a982'),
            8412: ('13643051391', '287fe2254735b48087b63da72bf550b8b0d7a2b489e2cd9cc60283f7b29d5d89'),
            8432: ('13642599956', 'be4eda15b205ee90352336d88162cb746651d89ac8ecdb10e4a0a7a9c986fa94'),
            8452: ('13642684784', 'bba32bbd6ff92c407fc8bb13a394a4b6b87bf2a38973f2b125798fa03da4d916'),
            8472: ('13643018568', '0e51a2d1640e260e1e2c573745b1825df172654b6e9880284ed4219972bbc68f'),
        }
        for m, (artifact_id, zip_sha) in attested.items():
            self.assertIn(f"G_{m}_ID: '{artifact_id}'", wf)
            self.assertIn(f"G_{m}_ZIP: {zip_sha}", wf)
            self.assertIn(f"g-{chr(36)}{{m}}.zip", wf)
        self.assertIn("H_SOURCE_SHA: UNREVIEWED_FAIL_CLOSED", wf)
        self.assertIn("G_HEAD: f7a85ca8dbd55dfb491842cf4c0f514e0d74041a", wf)
        g_final = {
            "HOST": "bb5087c81339deaa0de03e2466ea5cf5203a8ceb114a28b8cbb0fa8ab9f323e7",
            "RTL": "39cb0285446472386df3e9c7c7b393f715aa4e42467fa1bfc2b8a7e7da0cdc1f",
            "CARD": "cdddc16be18c04e05d58225cb844609bd60891c8892eb458b9bd361346d46dd2",
        }
        for key, sha in g_final.items():
            self.assertIn(f"G_{key}: {sha}", wf)
            self.assertIn(f"AETHERCORE_SD1_V3_H_8472_{key}_SHA: {sha}", wf)
        self.assertIn("source_host_sha256", wf)
        self.assertIn("source_rtl_sha256", wf)
        self.assertIn("snapshot_host_sha256", wf)
        self.assertIn("snapshot_rtl_sha256", wf)
        self.assertIn("card_progress_sha256", wf)
        self.assertIn("debian_login_claim", wf)

    def test_every_stage_saved_before_next_execution(self):
        wf = WORKFLOW.read_text()
        a = wf.index("Advance genuine RTL Host-v3 8472M to 8492M")
        b = wf.index("Upload complete 8492M before next guest RTL cycle")
        c = wf.index("Advance genuine RTL Host-v3 8492M to 8500M")
        d = wf.index("Upload complete 8500M before next guest RTL cycle")
        self.assertLess(a, b)
        self.assertLess(b, c)
        self.assertLess(c, d)
        self.assertEqual(wf.count("- name: Advance genuine RTL Host-v3 "), 2)
        self.assertEqual(wf.count("- name: Upload complete "), 2)
        self.assertIn("include-hidden-files: true", wf)
        self.assertIn("PRODUCT_SD1_V3_H_REAL_RTL_8500M_CEILING_REACHED_NOT_DEBIAN_PASS", wf)

    def test_workflow_syntax(self):
        import yaml
        wf = yaml.safe_load(WORKFLOW.read_text())
        job = wf["jobs"]["H_real_product_rtl_two_stages"]
        self.assertEqual(len([s for s in job["steps"] if
                              s["name"].startswith("Advance genuine RTL Host-v3 ")]), 2)
        for s in job["steps"]:
            if "run" not in s:
                continue
            p = subprocess.run(["bash", "-n"], input=s["run"], text=True,
                               capture_output=True)
            self.assertEqual(p.returncode, 0, f"{s['name']}: {p.stderr}")


if __name__ == "__main__":
    unittest.main()
