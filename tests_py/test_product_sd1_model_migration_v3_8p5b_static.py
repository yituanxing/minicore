"""Static fail-closed contract for FUTURE Host-only 8.5B SD1 migration.

This suite has no authority to run the 20M real RTL differential. It only
checks that the candidate cannot execute without separately attested C 8072M
and 8092M artifacts, an exact Host-v2 model, and a distinct explicit token.
"""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / "sim/product_rv64_checkpoint_sd1_main.cpp"
SCRIPT = ROOT / "tools/ci/product_sd1_model_migration_v3_8p5b_qualify.sh"
WORKFLOW = (
    ROOT / ".github/workflows/product-sd1-host-v3-8p5b-qualification-DRAFT.yml"
)


class V3MigrationDraftStatic(unittest.TestCase):
    def test_plan_only_and_execute_rejected_before_any_guest_cycle(self):
        env = dict(os.environ)
        for key in ("AETHERCORE_SD1_V3_QUALIFICATION_EXECUTE",
                    "AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM",
                    "AETHERCORE_SD1_V3_SOURCE_8072_DIR",
                    "AETHERCORE_SD1_V3_SOURCE_8092_DIR"):
            env.pop(key, None)
        output = ROOT / "build/product-sd1-model-migration-v3-8p5b-draft"
        preexisting = output.exists()
        plan = subprocess.run(
            ["bash", str(SCRIPT), "--plan"], env=env, cwd=ROOT,
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(plan.returncode, 0, plan.stderr)
        self.assertIn("no_guest_cycles=1", plan.stdout)
        self.assertIn("8072M->8092M", plan.stdout)
        denied = subprocess.run(
            ["bash", str(SCRIPT), "--execute-qualified-diff"], env=env,
            cwd=ROOT, capture_output=True, text=True, check=False,
        )
        self.assertEqual(denied.returncode, 16)
        self.assertIn("independent C reviewer approval", denied.stderr)
        self.assertEqual(output.exists(), preexisting)

    def test_even_explicit_approval_rejects_unreviewed_C_pins(self):
        with tempfile.TemporaryDirectory() as scratch:
            a, b = Path(scratch) / "8072m", Path(scratch) / "8092m"
            a.mkdir()
            b.mkdir()
            env = dict(os.environ)
            env.update({
                "AETHERCORE_SD1_V3_QUALIFICATION_EXECUTE":
                    "HOST_ONLY_8072_TO_8092_REAL_RTL_REVIEWED",
                "AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM":
                    "FROZEN_SIGNED_SD1_MODEL_MIGRATE_V3_8P5B_8072M",
                "AETHERCORE_VERILATOR_VERSION": "5.054",
                "SIM_SETTLE_MODE": "reference",
                "VERILATOR_THREADS": "1",
                "AETHERCORE_DATA_MEM_WAIT_CYCLES": "0",
                "AETHERCORE_SD1_V3_SOURCE_8072_DIR": str(a),
                "AETHERCORE_SD1_V3_SOURCE_8092_DIR": str(b),
            })
            result = subprocess.run(
                ["bash", str(SCRIPT), "--execute-qualified-diff"],
                env=env, cwd=ROOT, capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 16)
            self.assertIn("SHA not independently authenticated", result.stderr)

    def test_host_v3_profile_separate_from_existing_v1_v2(self):
        src = HOST.read_text()
        for clause in (
            "migrate-v1", "migrate-v2-8b", "migrate-v3-8p5b",
            "kV2MigrationSourceCycles = 7892000000ULL",
            "kV2MigratedMaxCycles = 8100000000ULL",
            "kV3MigrationSourceCycles = 8072000000ULL",
            "kV3MigratedMaxCycles = 8500000000ULL",
            "SD1_FULL_DEBIAN_SIGNED_RELAY_V3_MIGRATED_8P1B",
            "SD1_FULL_DEBIAN_SIGNED_RELAY_V4_MIGRATED_8P5B",
            "FROZEN_SIGNED_SD1_MODEL_MIGRATE_V3_8P5B_8072M",
            "c47f6d16aab24bf83df9f4baab2b3cbbe19ced7bc3f42c1ffa96c5f35530aa70",
            "source_cycles\")!=\"8052000000\"",
            "debian_login_claim\")!=\"0\"",
            "requireExactMigrationBytes",
            "PRODUCT_SD1_MODEL_MIGRATE_V3_8P5B_ZERO_STEP_PASS",
        ):
            self.assertIn(clause, src)
        self.assertIn("migratedRelay?8000000000ULL", src)

    def test_two_real_C_states_are_sha_locked_and_differential_is_20m(self):
        src = SCRIPT.read_text()
        for needle in (
            "FROM=8072000000 TO=8092000000 STEP=20000000",
            "SOURCE_SHA=c47f6d16aab24bf83df9f4baab2b3cbbe19ced7bc3f42c1ffa96c5f35530aa70",
            "PREVIOUS_SHA=b9a651a3ba3aa50c7e0b15405d24a3d3ab56dd25fa2df7fb20f5654cdda80461",
            "verify_stage \"$SOURCE_8072\" \"$FROM\"",
            "verify_stage \"$SOURCE_8092\" \"$TO\"",
            "source_cycles \"$SOURCE_8092/.migration-ready\"",
            "migrate-v3-8p5b",
            "SD1_FULL_DEBIAN_SIGNED_RELAY_V4_MIGRATED_8P5B",
            "cmp -s -n 24", "cmp -s -i 88:88",
            "full_rtl_identical=1",
            "full_host_except_model_sha_identical=1",
            "cross_8p1b_executed=0",
            "generated_object_hash",
            "product_sd1_official_seed.py verify",
        ):
            self.assertIn(needle, src)
        for m in (8072, 8092):
            for kind in ("HOST", "RTL", "CARD"):
                self.assertIn(
                    f"AUTHENTIC_{m}_{kind}_SHA=UNREVIEWED_FAIL_CLOSED", src
                )

    def test_workflow_inert_until_eleven_pins_and_C_complete(self):
        src = WORKFLOW.read_text()
        self.assertIn("on:\n  pull_request:\n    types: [labeled]", src)
        self.assertIn("github.event.pull_request.number == 379", src)
        self.assertIn("product-sd1-v3-reviewed-8072-to-8092-qualify", src)
        self.assertIn("EXPERIMENT_SOURCE_SHA: UNREVIEWED_FAIL_CLOSED", src)
        self.assertIn("C_SOURCE_RUN: '42496075997'", src)
        self.assertIn("C_SOURCE_HEAD: fdd8a3867877d19a8a7b9e7cc2920b2868b323af", src)
        for m in (8072, 8092):
            for kind in ("HOST", "RTL", "CARD", "ZIP"):
                self.assertIn(f"C_{m}_{kind}_SHA256: UNREVIEWED_FAIL_CLOSED", src)
            self.assertIn(f"C_{m}_ARTIFACT_ID: UNREVIEWED_FAIL_CLOSED", src)
        for needle in (
            "42493512441", "13622134809",
            "b629ad30a84e933e75372dd7a15c9cffb976d10d7391130b7d84fee0626ac2b4",
            "followon_four_real_product_rtl_stages",
            "length == 4", "run_attempt == 1",
            "size_in_bytes > 1000000", "sha256sum", "unzip -qq",
            "candidate-savable-model", "PRODUCT_SD1_MODEL_MIGRATION_V3",
            "NOT_DEBIAN_PASS",
        ):
            if needle != "NOT_DEBIAN_PASS":
                self.assertIn(needle, src)
        self.assertIn("not Debian service/login PASS", src)


if __name__ == "__main__":
    unittest.main()
