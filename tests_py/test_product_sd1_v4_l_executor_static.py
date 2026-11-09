"""Fail-closed contract for L 9412M -> 9512M genuine RTL continuation.

The L profile remains inert without the complete K-9412M Host/RTL/card hashes
and the reviewer-triggered workflow. This test must not run the simulator.
"""

import os
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_migrated_v4_postcross_20m.sh"
TARGETS = (9432, 9452, 9472, 9492, 9512)
MODEL = "ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a"


class V4LExecutorStatic(unittest.TestCase):
    @staticmethod
    def env(**kwargs):
        values = dict(os.environ, AETHERCORE_SD1_V4_POSTCROSS_PROFILE="L")
        values.update(kwargs)
        return values

    def call(self, target, **kwargs):
        return subprocess.run(
            ["bash", str(SCRIPT), "--execute-reviewed-postcross-20m", "/tmp", str(target)],
            env=self.env(**kwargs), capture_output=True, text=True,
        )

    @staticmethod
    def reviewed_flags():
        return dict(
            AETHERCORE_SD1_V4_CATCHUP_EXECUTE="REVIEWED_V4_8292M_20M_EXACT_DIFF_AND_SOURCE_SHA_AUDITED",
            AETHERCORE_SD1_RELAY_CONFIRM="SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_10B",
            AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA=MODEL,
            AETHERCORE_VERILATOR_VERSION="5.054", SIM_SETTLE_MODE="reference",
            VERILATOR_THREADS="1", AETHERCORE_DATA_MEM_WAIT_CYCLES="0",
        )

    def test_plan_is_zero_cycle_and_exactly_five_stages(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)
        plan = subprocess.run(["bash", str(SCRIPT), "--plan"], env=self.env(),
                              capture_output=True, text=True, check=True)
        self.assertIn("no_guest_cycles=1", plan.stdout)
        self.assertIn("stages=9432,9452,9472,9492,9512; input=K-9412M", plan.stdout)
        self.assertIn("full K-9412M real RTL upload", plan.stdout)

    def test_unreviewed_execution_never_reaches_simulator(self):
        for stage in TARGETS:
            result = self.call(stage, AETHERCORE_SD1_V4_CATCHUP_EXECUTE="",
                               AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA="")
            self.assertEqual(result.returncode, 16, result.stderr)
            self.assertIn("unreviewed v4 model or timing configuration", result.stderr)

    def test_invalid_stage_and_source_rejected_even_with_all_review_flags(self):
        opts = self.reviewed_flags()
        for stage in (9312, 9412, 9532, 10012):
            result = self.call(stage, **opts)
            self.assertEqual(result.returncode, 16, result.stderr)
            self.assertIn("only five exact 20M L stages", result.stderr)
        first = self.call(9432, **opts)
        self.assertEqual(first.returncode, 16)
        self.assertIn("must start from full authenticated K-9412M", first.stderr)

    def test_l_source_must_be_future_verified_k_checkpoint(self):
        script = SCRIPT.read_text()
        for token in (
            'L_FIRST="$ROOT/build/product-sd1-v4-l-source/9412m"',
            'OUT="$ROOT/build/product-sd1-migrated-v4-10b-postcross-l"',
            "9432|9452|9472|9492|9512",
            "AETHERCORE_SD1_V4_L_9412_HOST_SHA",
            "AETHERCORE_SD1_V4_L_9412_RTL_SHA",
            "AETHERCORE_SD1_V4_L_9412_CARD_SHA",
            '"$expected" =~ ^[a-f0-9]{64}$',
            '"$(sha "$SOURCE/persistent.pre.host")" == "$SOURCE_HOST_SHA"',
            '"$(sha "$SOURCE/persistent.pre.rtl")" == "$SOURCE_RTL_SHA"',
            '"$(sha "$SOURCE/card-progress.json")" == "$SOURCE_CARD_SHA"',
            '"$SOURCE_M" -eq "$FIRST_M"',
            '! -e "$OUT/${TARGET_M}m"',
            'mv -T -- "$stage" "$OUT/${TARGET_M}m"',
            '"$TARGET" -le 10000000000',
            "debian_login_claim=0",
        ):
            self.assertIn(token, script)


if __name__ == "__main__":
    unittest.main()
