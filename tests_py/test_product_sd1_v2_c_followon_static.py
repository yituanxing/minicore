"""Fail-closed static contract for the NOT-YET-ENABLED 8012M->8092M C relay.

Static tests are not signed Debian Product RTL evidence. B must complete and
the exact final 8012M artifact must be independently pinned before any C run.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_migrated_v2_followon_20m.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-migrated-v2-8p1b-followon-DRAFT.yml"


class FollowonStatic(unittest.TestCase):
    def test_plan_and_default_rejection_write_nothing(self):
        with tempfile.TemporaryDirectory() as scratch:
            env = dict(os.environ)
            env.pop("AETHERCORE_SD1_V2_C_RELAY_EXECUTE", None)
            env.pop("AETHERCORE_SD1_RELAY_CONFIRM", None)
            before = list(Path(scratch).iterdir())
            plan = subprocess.run(
                ["bash", str(SCRIPT), "--plan"], cwd=scratch, env=env,
                capture_output=True, text=True, check=False)
            self.assertEqual(plan.returncode, 0, plan.stderr)
            self.assertIn("PLAN_ONLY no_guest_cycles=1", plan.stdout)
            bad = subprocess.run(
                ["bash", str(SCRIPT), "--execute-reviewed-20m", scratch, "8032"],
                cwd=scratch, env=env, capture_output=True, text=True, check=False)
            self.assertEqual(bad.returncode, 16)
            self.assertIn("separate B terminal approval", bad.stderr)
            self.assertEqual(list(Path(scratch).iterdir()), before)

    def test_b_artifact_pins_intentionally_missing_and_pr_label_unique(self):
        src = WORKFLOW.read_text()
        self.assertIn("on:\n  pull_request:\n    types: [labeled]", src)
        self.assertIn("product-sd1-v2-reviewed-8012-to-8092-four-stages", src)
        self.assertIn("github.event.pull_request.number == 379", src)
        self.assertIn("C_EXPERIMENT_SOURCE_SHA: UNREVIEWED_FAIL_CLOSED", src)
        for key in ("B_SOURCE_RUN", "B_TERMINAL_ARTIFACT_ID",
                    "B_TERMINAL_ZIP_SHA256", "B_8012_HOST_SHA256",
                    "B_8012_RTL_SHA256", "B_8012_CARD_SHA256"):
            self.assertIn(f"{key}: UNREVIEWED_FAIL_CLOSED", src)
        self.assertIn("c62877dc9f3ad14a5d197f2e425ed9f0accb3384", src)
        self.assertIn("42493512441", src)
        self.assertIn("13622134809", src)
        self.assertIn("b629ad30a84e933e75372dd7a15c9cffb976d10d7391130b7d84fee0626ac2b4", src)

    def test_four_real_20m_steps_preserve_before_next(self):
        wf = WORKFLOW.read_text()
        for prior,next_ in [(8012,8032),(8032,8052),(8052,8072),(8072,8092)]:
            advance = f"Advance real Product RTL {prior}M to {next_}M"
            upload = f"Upload complete {next_}M"
            self.assertIn(advance,wf)
            self.assertIn(upload,wf)
            self.assertLess(wf.index(advance),wf.index(upload))
        for i,j,k in [(8032,8052,8072),(8052,8072,8092)]:
            self.assertLess(wf.index(f"Upload complete {i}M"),
                            wf.index(f"Advance real Product RTL {i}M to {j}M"))
        src=SCRIPT.read_text()
        for marker in ('^(8032|8052|8072|8092)', '8100000000',
                       'source_cycles', 'snapshot_host_sha256',
                       'snapshot_rtl_sha256', 'card_progress_sha256',
                       'product_sd1_cpu_observer_audit',
                       'product_sd1_card_snapshot',
                       'PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED',
                       "'guest_uart_service_verified':False",
                       "'service_login_gate_pass':False",
                       'no_debian_login_claim=1'):
            self.assertIn(marker,src)
        self.assertNotIn('8100000001',src)

    def test_original_v1_8b_guard_and_original_driver_still_exist(self):
        runner = (ROOT / "sim/product_rv64_checkpoint_sd1_main.cpp").read_text()
        old = (ROOT / "tools/ci/product_sd1_migrated_autorelay_20m.sh").read_text()
        b = (ROOT / "tools/ci/product_sd1_migrated_v2_relay_20m.sh").read_text()
        self.assertIn('migratedRelay?8000000000ULL:',runner)
        self.assertIn('"$((batch_start_m+100))" -le 8000',old)
        self.assertIn('"$3" =~ ^(7932|7952|7972|7992|8012)$',b)


if __name__ == "__main__":
    unittest.main()
