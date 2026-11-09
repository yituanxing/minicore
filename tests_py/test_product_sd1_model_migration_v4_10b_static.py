"""Fail-closed static contract for isolated v4 Host-only migration.

No test executes guest RTL. 20M replay is an opt-in reviewed run, not Debian PASS.
"""
import os
from pathlib import Path
import subprocess
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "sim/product_rv64_checkpoint_sd1_main.cpp"
SCRIPT = ROOT / "tools/ci/product_sd1_model_migration_v4_10b_qualify.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-8272-to-8292-INERT-DRAFT.yml"


class HostV4MigrationStatic(unittest.TestCase):
    def test_default_is_zero_clock_and_execute_is_locked(self):
        env = dict(os.environ)
        for key in (
            "AETHERCORE_SD1_V4_QUALIFICATION_EXECUTE",
            "AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM",
            "AETHERCORE_SD1_V4_SOURCE_8272_DIR",
            "AETHERCORE_SD1_V4_SOURCE_8292_DIR",
        ):
            env.pop(key, None)
        plan = subprocess.run(["bash", str(SCRIPT), "--plan"], cwd=ROOT,
                              env=env, text=True, capture_output=True)
        self.assertEqual(plan.returncode, 0, plan.stderr)
        self.assertIn("no_guest_cycles=1", plan.stdout)
        out = ROOT / "build/product-sd1-model-migration-v4-10b-draft"
        existed = out.exists()
        denied = subprocess.run(["bash", str(SCRIPT), "--execute-qualified-diff"],
                                cwd=ROOT, env=env, text=True, capture_output=True)
        self.assertEqual(denied.returncode, 16)
        self.assertIn("requires explicit independent", denied.stderr)
        self.assertEqual(existed, out.exists())

    def test_new_mode_is_bounded_and_old_profiles_preserved(self):
        src = CPP.read_text()
        for needle in (
            "constexpr std::uint64_t kV3MigratedMaxCycles = 8500000000ULL;",
            "constexpr std::uint64_t kV4MigratedMaxCycles = 10000000000ULL;",
            "constexpr std::uint64_t kV4MigrationSourceCycles = 8272000000ULL;",
            "04e671f2dfae8aa6d7c5b17a93c688cb7b30005de9a2a329f983b909f4fa1be6",
            "mode==\"migrate-v4-10b\"",
            "SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_10B",
            "FROZEN_SIGNED_SD1_MODEL_MIGRATE_V4_10B_8272M",
            "pollingReadyField(ready,\"source_cycles\")!=\"8252000000\"",
            "previous_model_sha256",
            "requireExactMigrationBytes(",
            "pos>=24 && pos<88",
            "kFrozenMigratedV2Model",
        ):
            self.assertIn(needle, src)
        self.assertIn("migratingV4?kV4MigratedMaxCycles", src)
        self.assertIn("migratedRelayV4?kV4MigratedMaxCycles", src)

    def test_exact_authentic_8272_8292_and_replay(self):
        src = SCRIPT.read_text()
        for needle in (
            "readonly FROM=8272000000 TO=8292000000 STEP=20000000",
            "SOURCE_SHA=04e671f2dfae8aa6d7c5b17a93c688cb7b30005de9a2a329f983b909f4fa1be6",
            "AUTHENTIC_8272_HOST_SHA=74ab6999381fd71963c55e4ceff9ab233fa1428481e4670eb5d29500f4d42983",
            "AUTHENTIC_8272_RTL_SHA=5fd39e72ef38c04a307923944df40a8286d8a0501dc8572abdffb88c4bf37220",
            "AUTHENTIC_8272_CARD_SHA=84d82da0246259917a700166e0e75e53d6a7931cf869538c357d5c926772045d",
            "AUTHENTIC_8292_HOST_SHA=a7adbadb114bd3d183d8cd2059d5f28bf46017fe4be6befad340eb2c1495aed6",
            "AUTHENTIC_8292_RTL_SHA=71b8083cb692db41759ade061b256a8107765d99fd476b7a42e96685830142f1",
            "AUTHENTIC_8292_CARD_SHA=4b41bae42c7d858aa17a5b3379a45b32faff1edec51ed22605e949b6a273b0ae",
            "migrate-v4-10b",
            "replay_cycles=20000000",
            "full_rtl_identical=1",
            "full_host_except_model_sha_identical=1",
            "SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_10B",
            "cmp -s -i 88:88",
            "source_host_sha256",
            "card_progress_sha256",
        ):
            self.assertIn(needle, src)

    def test_media_and_generated_rtl_unchanged(self):
        src = SCRIPT.read_text()
        for needle in (
            "generated_rtl_sha256",
            "generated_rtl_objects_sha256",
            "generated_object_hash",
            "product_rv64_checkpoint_sd1_main.o",
            "verilator-5.054",
            "dataMemoryWaitCycles",
        ):
            if needle == "dataMemoryWaitCycles":
                self.assertIn(needle, CPP.read_text())
            else:
                self.assertIn(needle, src)
        self.assertIn("python3 tools/ci/product_sd1_official_seed.py verify", src)
        self.assertIn("sha \"$sd\"", src)
        self.assertIn("debian_login_claim=0", src)

    def test_inert_workflow_exact_early_f_artifact_and_no_false_pass(self):
        src = WORKFLOW.read_text()
        doc = yaml.load(src, Loader=yaml.BaseLoader)
        self.assertEqual(
            doc["jobs"]["qualify_v4_host_only_8272_to_8292"]["env"]["V4_SOURCE_SHA"],
            "UNREVIEWED_FAIL_CLOSED")
        for needle in (
            "F_RUN: '42542974143'",
            "F_HEAD: d07660bbaff04f19bdd57909ff27b1ab03c7dde7",
            "F_8292_ID: '13642630450'",
            "F_8292_ZIP: 28316adb927a21ade2e23369d4e1388e6159370ec681e5b287eb898a493da955",
            "E_RUN: '42500032369'",
            "E_8272_ID: '13625192349'",
            "E_8272_ZIP: bd3b903eafd58f1df0984470dd43406f9eeca32cb630eb9ce16125fe1844b8d0",
            "F_real_product_rtl_five_stages",
            "Advance genuine RTL Host-v3 8272M to 8292M",
            "Upload complete 8292M before next guest RTL cycle",
            "product-sd1-migrated-v3-f-8292m-",
            "product-sd1-migrated-v3-e-8272m-",
            "source_cycles=8252000000",
            "source_cycles=8272000000",
            "include-hidden-files: true",
            "NOT a 8.5B crossing or Debian login PASS",
        ):
            self.assertIn(needle, src)
        steps = doc["jobs"]["qualify_v4_host_only_8272_to_8292"]["steps"]
        self.assertEqual(len(steps), 15)
        for step in steps:
            if "run" not in step:
                continue
            result = subprocess.run(["bash", "-n"], input=step["run"], text=True,
                                    capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, step["name"] + result.stderr)


if __name__ == "__main__":
    unittest.main()
