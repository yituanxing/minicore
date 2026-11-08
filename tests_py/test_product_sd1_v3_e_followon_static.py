"""Fail-closed static contract for inert Host-v3 E real RTL follow-on.

These tests deliberately execute no guest clocks and cannot qualify Debian.
"""

import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_migrated_v3_e_followon_20m.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-v3-e-8172-to-8272-INERT-DRAFT.yml"


class EFollowonStatic(unittest.TestCase):
    def test_zero_cycle_plan_and_execute_fail_closed(self):
        env = dict(os.environ)
        for key in (
            "AETHERCORE_SD1_V3_E_RELAY_EXECUTE",
            "AETHERCORE_SD1_RELAY_CONFIRM",
            "AETHERCORE_SD1_V3_E_8172_HOST_SHA",
        ):
            env.pop(key, None)
        plan = subprocess.run(["bash", str(SCRIPT), "--plan"],
                              env=env, cwd=ROOT, text=True,
                              capture_output=True, check=False)
        self.assertEqual(plan.returncode, 0, plan.stderr)
        self.assertIn("no_guest_cycles=1", plan.stdout)
        before = (ROOT / "build/product-sd1-migrated-v3-8p5b-e-followon").exists()
        blocked = subprocess.run(
            ["bash", str(SCRIPT), "--execute-reviewed-20m",
             str(ROOT / "unavailable"), "8192"],
            env=env, cwd=ROOT, text=True,
            capture_output=True, check=False,
        )
        self.assertEqual(blocked.returncode, 16)
        self.assertIn("independent Host-v3 qualification", blocked.stderr)
        self.assertEqual(before,
                         (ROOT / "build/product-sd1-migrated-v3-8p5b-e-followon").exists())

    def test_e_five_stages_and_unmodified_host_cap(self):
        src = SCRIPT.read_text()
        for needle in (
            "^(8192|8212|8232|8252|8272)$",
            "8172m", "TARGET-START", "-le 8500000000",
            "AETHERCORE_SD1_V3_E_RELAY_EXECUTE",
            "REVIEWED_D_8172M_AND_V3_QUALIFIED_20M_PASS",
            "MODEL_ROOT", "qualification-manifest.txt",
            "source_cycles", "snapshot_host_sha256",
            "snapshot_rtl_sha256", "card_progress_sha256",
            "guest_uart_service_verified':False",
            "debian_login_claim=0", "PRODUCT_SD1_V3_E_REAL_RTL_STAGE_SAVED",
        ):
            self.assertIn(needle, src)
        self.assertNotIn('"$SOURCE" == "$ROOT/build/product-sd1-migrated-v3-8p5b-followon',src)

    def test_d_terminal_sha_pins_are_exact_and_source_still_inert(self):
        wf = WORKFLOW.read_text()
        self.assertIn("E_SOURCE_SHA: UNREVIEWED_FAIL_CLOSED", wf)
        pins = {
            "D_8172_ID": "'13624713515'",
            "D_8172_ZIP": "6bfe4e6b2e5d659ede22115c8f320a896fbd215f852c5f46fbba2d69bc2867ee",
            "D_HOST": "4d3c10e6c0a9b382634145e1ea9a79e0257bd87526f4e51ffc566112e86d53f1",
            "D_RTL": "6be6e95fdc67f7da054aa4ae898fc6b68e73107e4bb094e4fbe854024873e862",
            "D_CARD": "0a455d63334465ca900bb0ec27105a7ac8ab9a2d3db7a7455f5c6485fc599a34",
            "AETHERCORE_SD1_V3_E_8172_HOST_SHA": "4d3c10e6c0a9b382634145e1ea9a79e0257bd87526f4e51ffc566112e86d53f1",
            "AETHERCORE_SD1_V3_E_8172_RTL_SHA": "6be6e95fdc67f7da054aa4ae898fc6b68e73107e4bb094e4fbe854024873e862",
            "AETHERCORE_SD1_V3_E_8172_CARD_SHA": "0a455d63334465ca900bb0ec27105a7ac8ab9a2d3db7a7455f5c6485fc599a34",
        }
        for key, value in pins.items():
            self.assertIn(f"{key}: {value}", wf)
        self.assertIn("D_RUN: '42499056943'", wf)
        self.assertIn("D_HEAD: 0ac282e73448fa6730a364a356a690090da3c63f", wf)
        self.assertIn(".conclusion==\"success\"", wf)
        self.assertIn("run_attempt==1", wf)
        self.assertIn("size_in_bytes>1000000", wf)
        self.assertIn("D 8172", SCRIPT.read_text())

    def test_five_real_stages_save_before_next_execution(self):
        wf = WORKFLOW.read_text()
        last = -1
        for i, to in enumerate((8192,8212,8232,8252,8272)):
            advance = wf.index(f"Advance genuine RTL Host-v3 {to-20}M to {to}M")
            saved = wf.index(f"Upload complete {to}M before next guest RTL cycle")
            self.assertLess(last, advance)
            self.assertLess(advance, saved)
            self.assertIn(f"include-hidden-files: true", wf)
            self.assertIn(f"name: product-sd1-migrated-v3-e-{to}m-", wf)
            last = saved
        self.assertEqual(wf.count("- name: Advance genuine RTL Host-v3 "), 5)


if __name__ == "__main__":
    unittest.main()
