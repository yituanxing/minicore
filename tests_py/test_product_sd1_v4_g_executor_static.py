"""Static fail-closed contract for the reviewed G 8912M -> 9012M continuation."""

import os
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_migrated_v4_postcross_20m.sh"
TARGETS = (8932, 8952, 8972, 8992, 9012)
MODEL = "ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a"


class V4GExecutorStatic(unittest.TestCase):
    @staticmethod
    def env(**kwargs):
        values = dict(os.environ, AETHERCORE_SD1_V4_POSTCROSS_PROFILE="G")
        values.update(kwargs)
        return values

    def call(self, target, **kwargs):
        return subprocess.run(
            ["bash", str(SCRIPT), "--execute-reviewed-postcross-20m", "/tmp", str(target)],
            env=self.env(**kwargs), capture_output=True, text=True,
        )

    def test_plan_is_zero_cycle_and_exactly_five_stages(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)
        plan = subprocess.run(["bash", str(SCRIPT), "--plan"], env=self.env(),
                              capture_output=True, text=True, check=True)
        self.assertIn("no_guest_cycles=1", plan.stdout)
        self.assertIn("stages=8932,8952,8972,8992,9012; input=F-8912M", plan.stdout)

    def test_unreviewed_execution_never_reaches_simulator(self):
        for stage in TARGETS:
            result = self.call(stage, AETHERCORE_SD1_V4_CATCHUP_EXECUTE="",
                               AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA="")
            self.assertEqual(result.returncode, 16, result.stderr)
            self.assertIn("unreviewed v4 model or timing configuration", result.stderr)

    def test_invalid_stage_and_source_rejected_even_with_all_review_flags(self):
        opts = dict(
            AETHERCORE_SD1_V4_CATCHUP_EXECUTE="REVIEWED_V4_8292M_20M_EXACT_DIFF_AND_SOURCE_SHA_AUDITED",
            AETHERCORE_SD1_RELAY_CONFIRM="SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_10B",
            AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA=MODEL,
            AETHERCORE_VERILATOR_VERSION="5.054", SIM_SETTLE_MODE="reference",
            VERILATOR_THREADS="1", AETHERCORE_DATA_MEM_WAIT_CYCLES="0",
        )
        wrong = self.call(8912, **opts)
        self.assertEqual(wrong.returncode, 16)
        self.assertIn("only five exact 20M G stages", wrong.stderr)
        first = self.call(8932, **opts)
        self.assertEqual(first.returncode, 16)
        self.assertIn("must start from full authenticated F-8912M", first.stderr)

    def test_g_only_from_sealed_8912_input_and_no_overwrite(self):
        script = SCRIPT.read_text()
        for token in (
            'G_FIRST="$ROOT/build/product-sd1-v4-g-source/8912m"',
            'OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-g"',
            "8932|8952|8972|8992|9012",
            "AETHERCORE_SD1_V4_G_8912_HOST_SHA",
            "AETHERCORE_SD1_V4_G_8912_RTL_SHA",
            "AETHERCORE_SD1_V4_G_8912_CARD_SHA",
            '"$SOURCE_M" -eq "$FIRST_M"',
            '! -e "$OUT/${TARGET_M}m"',
            "mv -T -- \"$stage\" \"$OUT/${TARGET_M}m\"",
            "debian_login_claim=0",
        ):
            self.assertIn(token, script)


if __name__ == "__main__":
    unittest.main()
