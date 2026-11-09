"""Fail-closed static contract for inert Host-v3 F real RTL follow-on.

These tests deliberately execute no guest clocks and cannot qualify Debian.
"""

import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_migrated_v3_f_followon_20m.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-v3-f-8272-to-8372-INERT-DRAFT.yml"


class FFollowonStatic(unittest.TestCase):
    def test_zero_cycle_plan_and_execute_fail_closed(self):
        env = dict(os.environ)
        for key in (
            "AETHERCORE_SD1_V3_F_RELAY_EXECUTE",
            "AETHERCORE_SD1_RELAY_CONFIRM",
            "AETHERCORE_SD1_V3_F_8272_HOST_SHA",
        ):
            env.pop(key, None)
        plan = subprocess.run(["bash", str(SCRIPT), "--plan"],
                              env=env, cwd=ROOT, text=True,
                              capture_output=True, check=False)
        self.assertEqual(plan.returncode, 0, plan.stderr)
        self.assertIn("no_guest_cycles=1", plan.stdout)
        before = (ROOT / "build/product-sd1-migrated-v3-8p5b-f-followon").exists()
        blocked = subprocess.run(
            ["bash", str(SCRIPT), "--execute-reviewed-20m",
             str(ROOT / "unavailable"), "8292"],
            env=env, cwd=ROOT, text=True,
            capture_output=True, check=False,
        )
        self.assertEqual(blocked.returncode, 16)
        self.assertIn("independent Host-v3 qualification", blocked.stderr)
        self.assertEqual(before,
                         (ROOT / "build/product-sd1-migrated-v3-8p5b-f-followon").exists())

    def test_f_five_stages_and_unmodified_host_cap(self):
        src = SCRIPT.read_text()
        for needle in (
            "^(8292|8312|8332|8352|8372)$",
            "8272m", "TARGET-START", "-le 8500000000",
            "AETHERCORE_SD1_V3_F_RELAY_EXECUTE",
            "REVIEWED_E_8272M_AND_V3_QUALIFIED_20M_PASS",
            "MODEL_ROOT", "qualification-manifest.txt",
            "source_cycles", "snapshot_host_sha256",
            "source_cycles \"$ready\")\" == 8252000000",
            "snapshot_rtl_sha256", "card_progress_sha256",
            "guest_uart_service_verified':False",
            "debian_login_claim=0", "PRODUCT_SD1_V3_F_REAL_RTL_STAGE_SAVED",
        ):
            self.assertIn(needle, src)
        self.assertNotIn('"$SOURCE" == "$ROOT/build/product-sd1-migrated-v3-8p5b-followon',src)

    def test_e_terminal_sha_pins_are_exact_and_source_still_inert(self):
        wf = WORKFLOW.read_text()
        self.assertIn("F_SOURCE_SHA: UNREVIEWED_FAIL_CLOSED", wf)
        pins = {
            "E_8272_ID": "'13625192349'",
            "E_8272_ZIP": "bd3b903eafd58f1df0984470dd43406f9eeca32cb630eb9ce16125fe1844b8d0",
            "E_HOST": "74ab6999381fd71963c55e4ceff9ab233fa1428481e4670eb5d29500f4d42983",
            "E_RTL": "5fd39e72ef38c04a307923944df40a8286d8a0501dc8572abdffb88c4bf37220",
            "E_CARD": "84d82da0246259917a700166e0e75e53d6a7931cf869538c357d5c926772045d",
            "AETHERCORE_SD1_V3_F_8272_HOST_SHA": "74ab6999381fd71963c55e4ceff9ab233fa1428481e4670eb5d29500f4d42983",
            "AETHERCORE_SD1_V3_F_8272_RTL_SHA": "5fd39e72ef38c04a307923944df40a8286d8a0501dc8572abdffb88c4bf37220",
            "AETHERCORE_SD1_V3_F_8272_CARD_SHA": "84d82da0246259917a700166e0e75e53d6a7931cf869538c357d5c926772045d",
        }
        for key, value in pins.items():
            self.assertIn(f"{key}: {value}", wf)
        self.assertIn("E_RUN: '42500032369'", wf)
        self.assertIn("E_HEAD: 809cc36592e01cc607ed6ff95b10f69b3703a4f2", wf)
        self.assertIn(".conclusion==\"success\"", wf)
        self.assertIn("run_attempt==1", wf)
        self.assertIn("size_in_bytes>1000000", wf)
        self.assertIn("source_cycles=8252000000", wf)
        self.assertIn("boot_cycles=8272000000", wf)
        self.assertIn("E 8272", SCRIPT.read_text())

    def test_five_real_stages_save_before_next_execution(self):
        wf = WORKFLOW.read_text()
        last = -1
        for i, to in enumerate((8292,8312,8332,8352,8372)):
            advance = wf.index(f"Advance genuine RTL Host-v3 {to-20}M to {to}M")
            saved = wf.index(f"Upload complete {to}M before next guest RTL cycle")
            self.assertLess(last, advance)
            self.assertLess(advance, saved)
            self.assertIn(f"include-hidden-files: true", wf)
            self.assertIn(f"name: product-sd1-migrated-v3-f-{to}m-", wf)
            last = saved
        self.assertEqual(wf.count("- name: Advance genuine RTL Host-v3 "), 5)


if __name__ == "__main__":
    unittest.main()
