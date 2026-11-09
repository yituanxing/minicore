"""Static fail-closed contract for the reviewed F 8812M -> 8912M continuation."""

import os
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_migrated_v4_postcross_20m.sh"
TARGETS = (8832, 8852, 8872, 8892, 8912)
MODEL = "ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a"


class V4FExecutorStatic(unittest.TestCase):
    @staticmethod
    def env(**kwargs):
        values = dict(os.environ, AETHERCORE_SD1_V4_POSTCROSS_PROFILE="F")
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
        self.assertIn("stages=8832,8852,8872,8892,8912; input=E-8812M", plan.stdout)

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
        wrong = self.call(8812, **opts)
        self.assertEqual(wrong.returncode, 16)
        self.assertIn("only five exact 20M F stages", wrong.stderr)
        first = self.call(8832, **opts)
        self.assertEqual(first.returncode, 16)
        self.assertIn("must start from full authenticated E-8812M", first.stderr)

    def test_f_only_from_sealed_8812_input_and_no_overwrite(self):
        script = SCRIPT.read_text()
        for token in (
            'F_FIRST="$ROOT/build/product-sd1-v4-f-source/8812m"',
            'OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-f"',
            "8832|8852|8872|8892|8912",
            "AETHERCORE_SD1_V4_F_8812_HOST_SHA",
            "AETHERCORE_SD1_V4_F_8812_RTL_SHA",
            "AETHERCORE_SD1_V4_F_8812_CARD_SHA",
            '"$SOURCE_M" -eq "$FIRST_M"',
            '! -e "$OUT/${TARGET_M}m"',
            "mv -T -- \"$stage\" \"$OUT/${TARGET_M}m\"",
            "debian_login_claim=0",
        ):
            self.assertIn(token, script)


if __name__ == "__main__":
    unittest.main()

