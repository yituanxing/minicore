"""Fail-closed, non-RTL contracts for the future signed Debian Host-v5 handoff.

No test starts a guest, crosses the reviewed V4 10B ceiling, or mutates artifacts.
The real qualification requires independently attested O9972/O9992 checkpoints.
"""
import os
from pathlib import Path
import subprocess
import unittest
import yaml


ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "sim/product_rv64_checkpoint_sd1_main.cpp"
SCRIPT = ROOT / "tools/ci/product_sd1_model_migration_v5_15b_qualify.sh"
V4 = ROOT / "tools/ci/product_sd1_model_migration_v4_10b_qualify.sh"
POSTCROSS_V4 = ROOT / "tools/ci/product_sd1_migrated_v4_postcross_20m.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-v5-9972-to-9992-INERT-DRAFT.yml"
V4_SHA = "ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a"
V3_SHA = "04e671f2dfae8aa6d7c5b17a93c688cb7b30005de9a2a329f983b909f4fa1be6"


class HostV5MigrationStatic(unittest.TestCase):
    def test_inert_official_o_qualification_workflow(self):
        src = WORKFLOW.read_text()
        doc = yaml.load(src, Loader=yaml.BaseLoader)
        self.assertEqual(list(doc["on"]), ["pull_request"])
        self.assertEqual(doc["on"]["pull_request"]["types"], ["labeled"])
        job = doc["jobs"]["qualify_v5_host_only_9972_to_9992"]
        self.assertIn("github.event.label.name == 'enhancement'", job["if"])
        self.assertIn("github.event.pull_request.number == 379", job["if"])
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", job["if"])
        self.assertEqual(job["env"]["O_RUN"], "42593929942")
        self.assertEqual(job["env"]["O_HEAD"], "ea42929063907b8c9a5a5c27c972f16bbdb28ff1")
        self.assertEqual(job["env"]["V4_QUAL_RUN"], "42545549282")
        self.assertEqual(job["env"]["V4_MODEL_SHA"], V4_SHA)
        self.assertEqual(job["env"]["O_9972_ID"], "13664630847")
        self.assertEqual(job["env"]["O_9992_ID"], "13663374296")
        for stage in ("9972", "9992"):
            self.assertRegex(job["env"][f"O_{stage}_ZIP"], r"^[a-f0-9]{64}$")
            for part in ("HOST", "RTL", "CARD"):
                self.assertRegex(job["env"][f"AETHERCORE_SD1_V5_{stage}_{part}_SHA"],
                                 r"^[a-f0-9]{64}$")
        for needle in [
            ".github/workflows/product-sd1-v4-o-9712-to-9992.yml",
            "v4_postcross_fourteen_true_rtl_stages_o",
            "Upload complete v4 9972M before any further guest RTL cycle",
            "Upload complete v4 9992M before any further guest RTL cycle",
            "product-sd1-migrated-v4-o-9972m-$O_RUN",
            "product-sd1-migrated-v4-o-9992m-$O_RUN",
            ".digest==$digest",
            ".run_attempt==1",
            "github.event.pull_request.head.sha",
            "repos/$GITHUB_REPOSITORY/pulls/379",
            "aethercore-Linux-X64-verilator-product-gsim-g3-v1",
            "aethercore-Linux-X64-product-sd1-savable-portable-v1",
            "product_sd1_official_seed.py verify",
            "product_sd1_model_migration_v5_15b_qualify.sh --execute-qualified-diff",
            "two_path_9992_sd_cow_body_identical=1",
            "terminal_migration_guest_cycles=0",
            "cross_10b_executed=0",
            "qualified/9992m",
            "include-hidden-files: true",
        ]:
            self.assertIn(needle, src, needle)
        self.assertNotIn("10012000000", src)
        self.assertNotIn("9992M to 10012M", src)
        self.assertNotIn("workflow_dispatch:", src)
        self.assertNotIn("push:", src)
        self.assertEqual(len(job["steps"]), 15)
        for step in job["steps"]:
            if "run" in step:
                result = subprocess.run(["bash", "-n"], input=step["run"],
                                        text=True, capture_output=True)
                self.assertEqual(result.returncode, 0, step["name"] + result.stderr)

    def test_plan_is_inert_and_qualification_rejects_unreviewed_launch(self):
        env = dict(os.environ)
        for key in list(env):
            if key.startswith("AETHERCORE_SD1_V5_"):
                env.pop(key)
        env.pop("AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM", None)
        out = ROOT / "build/product-sd1-model-migration-v5-15b-draft"
        before = out.exists()
        plan = subprocess.run(["bash", str(SCRIPT), "--plan"], cwd=ROOT,
                              env=env, capture_output=True, text=True)
        self.assertEqual(plan.returncode, 0, plan.stderr)
        self.assertIn("no_guest_cycles=1", plan.stdout)
        self.assertIn("O9972/O9992", plan.stdout)
        denied = subprocess.run(["bash", str(SCRIPT), "--execute-qualified-diff"],
                                cwd=ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(denied.returncode, 16, denied.stdout + denied.stderr)
        self.assertIn("independent O source review", denied.stderr)
        self.assertEqual(before, out.exists())

    def test_shell_syntax_and_existing_v4_cap_not_weakened(self):
        for shell in [SCRIPT, V4, POSTCROSS_V4]:
            result = subprocess.run(["bash", "-n", str(shell)], cwd=ROOT,
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        old_v4 = V4.read_text()
        self.assertIn("FROZEN_SIGNED_SD1_MODEL_MIGRATE_V4_10B_8272M", old_v4)
        old_relay = POSTCROSS_V4.read_text()
        self.assertIn('"$TARGET" -le 10000000000', old_relay)
        self.assertIn("SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_10B", old_relay)

    def test_reviewed_switch_alone_cannot_start_without_source_artifacts(self):
        env = dict(os.environ)
        for key in list(env):
            if key.startswith("AETHERCORE_SD1_V5_"):
                env.pop(key)
        env.update({
            "AETHERCORE_SD1_V5_QUALIFICATION_EXECUTE": "HOST_ONLY_9972_TO_9992_REAL_RTL_REVIEWED",
            "AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM": "FROZEN_SIGNED_SD1_MODEL_MIGRATE_V5_15B_DIFF_9972M",
            "AETHERCORE_VERILATOR_VERSION": "5.054",
            "SIM_SETTLE_MODE": "reference",
            "VERILATOR_THREADS": "1",
            "AETHERCORE_DATA_MEM_WAIT_CYCLES": "0",
        })
        out = ROOT / "build/product-sd1-model-migration-v5-15b-draft"
        before = out.exists()
        denied = subprocess.run(["bash", str(SCRIPT), "--execute-qualified-diff"],
                                cwd=ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(denied.returncode, 16, denied.stdout + denied.stderr)
        self.assertIn("source stage roots must be explicit", denied.stderr)
        self.assertEqual(before, out.exists())

    def test_new_host_profile_retains_all_prior_guards(self):
        src = CPP.read_text()
        for needle in [
            "constexpr std::uint64_t kV4MigratedMaxCycles = 10000000000ULL;",
            "constexpr std::uint64_t kV5MigratedMaxCycles = 15000000000ULL;",
            "constexpr std::uint64_t kV5QualificationSourceCycles = 9972000000ULL;",
            "constexpr std::uint64_t kV5MigrationSourceCycles = 9992000000ULL;",
            V4_SHA, V3_SHA,
            'mode=="migrate-v5-15b"',
            "migratedRelayV5?kV5MigratedMaxCycles",
            "migratingV5?kV5MigratedMaxCycles",
            "SD1_FULL_DEBIAN_SIGNED_RELAY_V6_MIGRATED_15B",
            "FROZEN_SIGNED_SD1_MODEL_MIGRATE_V5_15B_DIFF_9972M",
            "FROZEN_SIGNED_SD1_MODEL_MIGRATE_V5_15B_9992M",
            'sourcePrefix.parent_path().filename()=="9972m"',
            'sourcePrefix.parent_path().filename()=="9992m"',
            'pollingReadyField(ready,"source_cycles")=="9952000000"',
            'pollingReadyField(ready,"source_cycles")=="9972000000"',
            'pollingReadyField(ready,"previous_model_sha256")!=kFrozenMigratedV3Model',
            "PRODUCT_SD1_MODEL_MIGRATE_V5_15B_ZERO_STEP_PASS",
            "requireExactMigrationBytes(",
            "pos>=24 && pos<88",
            "shaFile(\"/proc/self/exe\")",
            "readHost(prefix+\".pre.host\",id,policy,ctx,mem,transport,card,obs)",
            "readRtl(prefix+\".pre.rtl\",top)",
        ]:
            self.assertIn(needle, src, needle)
        self.assertIn('std::filesystem::rename(stagingDir,targetDir);', src)

    def test_qualified_source_sha_and_complete_state_oracle(self):
        src = SCRIPT.read_text()
        for needle in [
            f"SOURCE_SHA={V4_SHA}",
            f"PREVIOUS_SHA={V3_SHA}",
            "readonly FROM=9972000000 TO=9992000000 STEP=20000000",
            "AETHERCORE_SD1_V5_SOURCE_9972_DIR",
            "AETHERCORE_SD1_V5_SOURCE_9992_DIR",
            "AETHERCORE_SD1_V5_9972_HOST_SHA",
            "AETHERCORE_SD1_V5_9972_RTL_SHA",
            "AETHERCORE_SD1_V5_9972_CARD_SHA",
            "AETHERCORE_SD1_V5_9992_HOST_SHA",
            "AETHERCORE_SD1_V5_9992_RTL_SHA",
            "AETHERCORE_SD1_V5_9992_CARD_SHA",
            "build/product-sd1-model-migration-v4-10b-qualified",
            "PRODUCT_SD1_MODEL_MIGRATION_V4: status=QUALIFIED_HOST_ONLY_8272_TO_8292_20M_DIFF_NOT_8P5B_CROSS",
            "generated_rtl_sha256",
            "generated_rtl_objects_sha256",
            "product_rv64_checkpoint_sd1_main.o",
            "verilator-5.054",
            "source_host_sha256",
            "source_rtl_sha256",
            "card_progress_sha256",
            "python3 tools/ci/product_sd1_official_seed.py verify",
            "FROZEN_SIGNED_SD1_MODEL_MIGRATE_V5_15B_9992M",
            "FROZEN_SIGNED_SD1_MODEL_MIGRATE_V5_15B_DIFF_9972M",
            "SD1_FULL_DEBIAN_SIGNED_RELAY_V6_MIGRATED_15B",
            "cmp -s -n 24",
            "cmp -s -i 88:88",
            "two_path_9992_host_rtl_identical=1",
            "product_sd1_card_snapshot import card_state_from_host",
            "sd_card_body_sha256",
            "sd_cow_count",
            'state != states[0]',
            '"$OUT/qualified-differential/9992m/card-progress.json"',
            '"$AUTHENTIC_9992_CARD_SHA"',
            'cmp -s "$SOURCE_9992/card-progress.json"',
            "two_path_9992_sd_cow_body_identical=1",
            "differential_card_progress_official_bytes_identical=1",
            "terminal_migration_guest_cycles=0",
            "cross_10b_executed=0",
            "debian_login_claim=0",
        ]:
            self.assertIn(needle, src, needle)
        # The new binary is never asked to advance past 9992M in qualification.
        self.assertNotIn('"$TO" 20000000 ', src)
        self.assertNotIn('AETHERCORE_SD1_RELAY_CONFIRM=SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_15B', src)
        # Historical F8292 (different checkpoint) must never authenticate O9992.
        self.assertNotIn("AUTHENTIC_9992_HOST_SHA=a7adbadb", src)


if __name__ == "__main__":
    unittest.main()
