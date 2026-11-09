"""Static fail-closed checks for Host-v3 G follow-on; no guest execution."""
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_migrated_v3_g_followon_20m.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-v3-g-8372-to-8472-INERT-DRAFT.yml"


class GFollowonStatic(unittest.TestCase):
    def test_zero_cycle_plan_and_guarded_execute(self):
        env = dict(os.environ)
        for key in ("AETHERCORE_SD1_V3_G_RELAY_EXECUTE",
                    "AETHERCORE_SD1_RELAY_CONFIRM",
                    "AETHERCORE_SD1_V3_G_8372_HOST_SHA"):
            env.pop(key, None)
        plan = subprocess.run(["bash", str(SCRIPT), "--plan"], env=env,
                              cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(plan.returncode, 0, plan.stderr)
        self.assertIn("no_guest_cycles=1", plan.stdout)
        out = ROOT / "build/product-sd1-migrated-v3-8p5b-g-followon"
        before = out.exists()
        blocked = subprocess.run(
            ["bash", str(SCRIPT), "--execute-reviewed-20m",
             str(ROOT / "unavailable"), "8392"], env=env, cwd=ROOT,
            text=True, capture_output=True)
        self.assertEqual(blocked.returncode, 16)
        self.assertIn("independent Host-v3 qualification", blocked.stderr)
        self.assertEqual(before, out.exists())

    def test_five_stages_and_8p5b_cap(self):
        src = SCRIPT.read_text()
        for item in ("^(8392|8412|8432|8452|8472)$",
                     "source=qualified-v3-F-8372m",
                     "REVIEWED_F_8372M_OFFLINE_ATTESTED_AND_V3_QUALIFIED_20M_PASS",
                     "AETHERCORE_SD1_V3_G_8372_HOST_SHA",
                     "AETHERCORE_SD1_V3_G_8372_RTL_SHA",
                     "AETHERCORE_SD1_V3_G_8372_CARD_SHA",
                     "8352000000", "-le 8500000000",
                     "snapshot_host_sha256", "snapshot_rtl_sha256",
                     "card_progress_sha256", "guest_uart_service_verified':False",
                     "PRODUCT_SD1_V3_G_REAL_RTL_STAGE_SAVED"):
            self.assertIn(item, src)
        self.assertNotIn("PRODUCT_SD1_V3_F_REAL_RTL_STAGE_SAVED", src)

    def test_f_terminal_unreviewed_and_provenance_fail_closed(self):
        wf = WORKFLOW.read_text()
        for key in (
            "G_SOURCE_SHA", "F_RUN", "F_HEAD", "F_8372_ID",
            "F_8372_ZIP", "F_HOST", "F_RTL", "F_CARD",
            "F_ATTEST_RUN", "F_ATTEST_HEAD", "F_ATTEST_ID", "F_ATTEST_ZIP",
            "AETHERCORE_SD1_V3_G_8372_HOST_SHA",
            "AETHERCORE_SD1_V3_G_8372_RTL_SHA",
            "AETHERCORE_SD1_V3_G_8372_CARD_SHA",
        ):
            self.assertIn(f"{key}: UNREVIEWED_FAIL_CLOSED", wf)
        self.assertIn("V3_RUN: '42498142872'", wf)
        self.assertIn("product-sd1-v3-reviewed-8372-to-8472-five-stages", wf)
        self.assertIn("product-sd1-v3-f-8272-to-8372.yml", wf)
        self.assertIn("product-sd1-v3-f8372-offline-attest.yml", wf)
        self.assertIn("F8372_offline_sha_attest_no_guest", wf)
        self.assertIn("product-sd1-v3-f8372-offline-attested-$F_ATTEST_RUN", wf)
        self.assertIn("f8372-offline-attestation.sha256", wf)
        self.assertIn("sha256sum -c f8372-offline-attestation.sha256", wf)
        self.assertIn("only_failing_step_is_known_verifier_unbound_e==true", wf)
        self.assertIn("failure_due_only_to_final_e_unbound_verifier", wf)
        self.assertIn("G_real_product_rtl_five_stages", wf)
        self.assertIn("run_attempt==1", wf)
        self.assertIn('.conclusion=="success"', wf)
        self.assertIn("boot_cycles=8372000000", wf)
        self.assertIn("source_cycles=8352000000", wf)
        self.assertIn("debian_login_claim=0", wf)

    def test_upload_before_next_stage_and_final_gate(self):
        wf = WORKFLOW.read_text()
        last = -1
        for to in (8392, 8412, 8432, 8452, 8472):
            run = wf.index(f"Advance genuine RTL Host-v3 {to-20}M to {to}M")
            save = wf.index(f"Upload complete {to}M before next guest RTL cycle")
            self.assertLess(last, run)
            self.assertLess(run, save)
            self.assertIn(f"name: product-sd1-migrated-v3-g-{to}m-", wf)
            last = save
        self.assertEqual(wf.count("- name: Advance genuine RTL Host-v3 "), 5)
        self.assertEqual(wf.count("- name: Upload complete "), 5)
        self.assertIn("include-hidden-files: true", wf)
        self.assertIn("PRODUCT_SD1_V3_G_REAL_RTL_8472M_COMPLETE_NOT_DEBIAN_PASS", wf)

    def test_offline_attestation_json_schema_contract(self):
        """Field contract from reviewed F attester commit 4192cc92.

        The F run itself is red due solely to its final shell verifier;
        these checks must not silently accept another unsuccessful stage.
        """
        wf = WORKFLOW.read_text()
        self.assertIn("FIVE_REAL_PRODUCT_RTL_F_STAGES_SHA_AUDITED_KNOWN_FINAL_VERIFIER_TYPO_NOT_DEBIAN_PASS", wf)
        self.assertIn('failure_due_only_to_final_e_unbound_verifier', wf)
        self.assertIn('.conclusion=="failure"', wf)
        self.assertIn('Verify five complete F checkpoints and no false Debian PASS', wf)
        self.assertIn('([.steps[]|select(.conclusion=="failure")]|length==1)', wf)
        for field in (
            'f_run', 'f_head', 'f_job_id', 'f_run_conclusion',
            'only_failing_step_is_known_verifier_unbound_e',
            'five_advances_and_uploads_success', 'no_guest_cycles_executed',
            'debian_login_claim', 'stages', 'cycles', 'artifact_id',
            'zip_sha256', 'host_sha256', 'rtl_sha256', 'card_sha256',
            'retired_delta', 'uart_bytes_delta', 'sd_command_delta',
        ):
            self.assertIn(field, wf, f"G did not authenticate attestation JSON field {field}")
        self.assertIn('f8372-offline-attestation.json', wf)
        self.assertIn('f8372-offline-attestation.sha256', wf)
        self.assertIn('sha256sum -c f8372-offline-attestation.sha256', wf)
        self.assertIn('only_failing_step_is_known_verifier_unbound_e==true', wf)

if __name__ == "__main__":
    unittest.main()
