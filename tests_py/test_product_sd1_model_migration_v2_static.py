"""No-RTL, no-network guard checks for the *draft* SD1 8B Host-only migration.

These tests cannot qualify a new model. Real migration requires authenticated
7892M+7912M published artifacts and an independent 20M byte-differential run.
"""

from pathlib import Path
import ast
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE_MODEL = "b9a651a3ba3aa50c7e0b15405d24a3d3ab56dd25fa2df7fb20f5654cdda80461"
POLLING_MODEL = "5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc"
SOURCE_CYCLES = 7_892_000_000
ORACLE_CYCLES = 7_912_000_000
V2_LIMIT = 8_100_000_000


def compare_host_and_rtl(old_host: bytes, new_host: bytes,
                         old_rtl: bytes, new_rtl: bytes,
                         new_sha: str) -> None:
    """Exactly the Host model-SHA exception; all other bytes must be equal."""
    assert len(new_sha) == 64 and all(c in "0123456789abcdef" for c in new_sha)
    if len(old_host) != len(new_host) or len(old_host) < 88:
        raise ValueError("Host length changed")
    if old_host[:24] != b"AETSDCP1" + (1).to_bytes(8, "little") + (64).to_bytes(8, "little"):
        raise ValueError("Host schema changed")
    if old_host[24:88] != SOURCE_MODEL.encode() or new_host[24:88] != new_sha.encode():
        raise ValueError("wrong source or destination model identity")
    if old_host[:24] != new_host[:24] or old_host[88:] != new_host[88:]:
        raise ValueError("non-model Host state changed")
    if old_rtl != new_rtl:
        raise ValueError("savable RTL state changed")


class MigratedV2StaticContract(unittest.TestCase):
    def setUp(self):
        self.new_sha = "a" * 64
        self.old = (b"AETSDCP1" + (1).to_bytes(8, "little") +
                    (64).to_bytes(8, "little") + SOURCE_MODEL.encode() +
                    bytes(range(256)) * 1025)
        self.new = self.old[:24] + self.new_sha.encode() + self.old[88:]
        self.rtl = b"savable-real-RTL\0" * 101

    def test_only_model_sha_may_change(self):
        compare_host_and_rtl(self.old, self.new, self.rtl, self.rtl, self.new_sha)

    def test_any_ddr_uart_sd_or_observer_byte_change_fails(self):
        changed = bytearray(self.new)
        changed[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "non-model Host"):
            compare_host_and_rtl(self.old, bytes(changed), self.rtl, self.rtl,
                                 self.new_sha)

    def test_rtl_byte_change_fails(self):
        with self.assertRaisesRegex(ValueError, "savable RTL"):
            compare_host_and_rtl(self.old, self.new, self.rtl, self.rtl + b"x",
                                 self.new_sha)

    def test_wrong_source_model_fails(self):
        bad = self.old[:24] + POLLING_MODEL.encode() + self.old[88:]
        with self.assertRaisesRegex(ValueError, "model identity"):
            compare_host_and_rtl(bad, self.new, self.rtl, self.rtl, self.new_sha)

    def test_stage_progression_crosses_8b_but_not_2pow32(self):
        stages = [7_912_000_000 + 20_000_000 * i for i in range(6)]
        self.assertEqual(stages[0], ORACLE_CYCLES)
        self.assertEqual(stages[-1], 8_012_000_000)
        self.assertEqual(stages[-2], 7_992_000_000)
        self.assertTrue(stages[-2] < 8_000_000_000 <= stages[-1])
        self.assertTrue(stages[-1] < V2_LIMIT)
        self.assertGreater(SOURCE_CYCLES, 2**32)

    def test_old_ceiling_guards_remain_exactly_eight_billion(self):
        runner = (ROOT / "sim/product_rv64_checkpoint_sd1_main.cpp").read_text()
        driver = (ROOT / "tools/ci/product_sd1_migrated_autorelay_20m.sh").read_text()
        workflow = (ROOT / ".github/workflows/product-sd1-migrated-autorelay-five-reusable.yml").read_text()
        self.assertIn('migratedRelay?8000000000ULL:', runner)
        self.assertIn('"$((batch_start_m+100))" -le 8000', driver)
        self.assertIn('"$((START+100))" -le 8000', workflow)

    def test_new_migration_is_explicit_and_zero_cycle_only(self):
        src = (ROOT / "sim/product_rv64_checkpoint_sd1_main.cpp").read_text()
        for marker in (
            'mode=="migrate-v2-8b"', SOURCE_MODEL,
            'kV2MigrationSourceCycles = 7892000000ULL',
            'kV2MigratedMaxCycles = 8100000000ULL',
            'FROZEN_SIGNED_SD1_MODEL_MIGRATE_V2_8B_7892M',
            '".migration-ready"', '"7892m"',
            'sourcePrefix.parent_path().filename()',
            'requireExactMigrationBytes(',
            'std::filesystem::rename(stagingDir,targetDir)',
        ):
            self.assertIn(marker, src)
        self.assertIn('(migrating ? replayCycles!=0 :', src)
        self.assertIn('if (!migrating && currentModelSha!=id.model)', src)

    def test_qualification_runner_is_plan_only_without_reviewed_tokens(self):
        script = ROOT / "tools/ci/product_sd1_model_migration_v2_qualify.sh"
        source = script.read_text()
        for marker in (
            '--execute-qualified-diff',
            'HOST_ONLY_7892_TO_7912_REAL_RTL_REVIEWED',
            'FROZEN_SIGNED_SD1_MODEL_MIGRATE_V2_8B_7892M',
            'AUTHENTIC_7912_HOST_SHA', 'AUTHENTIC_7912_RTL_SHA',
            'AUTHENTIC_7892_HOST_SHA', 'AUTHENTIC_7892_RTL_SHA',
            '"$SOURCE_7892" "$FROM"', '"$SOURCE_7912" "$TO"',
            'source_rtl_sha256', 'generated_rtl_objects_sha256',
            '20M RTL state differs from original 7912M',
            '20M Host non-model state differs',
            'cross_8b_executed=0',
        ):
            self.assertIn(marker, source)
        with tempfile.TemporaryDirectory() as scratch:
            env = dict(os.environ)
            env.pop('AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM', None)
            env.pop('AETHERCORE_SD1_V2_QUALIFICATION_EXECUTE', None)
            env['AETHERCORE_SD1_V2_SOURCE_7892_DIR'] = scratch
            env['AETHERCORE_SD1_V2_SOURCE_7912_DIR'] = scratch
            before = list(Path(scratch).iterdir())
            plan = subprocess.run(['bash', str(script), '--plan'], cwd=ROOT,
                                  env=env, capture_output=True, text=True,
                                  check=False)
            self.assertEqual(plan.returncode, 0, plan.stderr)
            self.assertIn('PLAN_ONLY no_guest_cycles=1', plan.stdout)
            rejected = subprocess.run(['bash', str(script), '--execute-qualified-diff'],
                                      cwd=ROOT, env=env, capture_output=True,
                                      text=True, check=False)
            self.assertEqual(rejected.returncode, 16)
            self.assertIn('requires explicit separate reviewer approval', rejected.stderr)
            self.assertEqual(list(Path(scratch).iterdir()), before)

    def test_manual_v2_A_workflow_authentication_cannot_cross_8b(self):
        draft = (ROOT / '.github/workflows/'
                 'product-sd1-migrated-v2-8b-qualification-DRAFT.yml').read_text()
        for marker in (
            'on:\n  pull_request:\n    types: [labeled]',
            'github.event.label.name == \'product-sd1-host-v2-7892-to-7912-qualify\'',
            'github.event.pull_request.number == 379',
            'EXPERIMENT_SOURCE_SHA: UNREVIEWED_FAIL_CLOSED',
            'ref: ${{ env.EXPERIMENT_SOURCE_SHA }}',
            '"$(git rev-parse HEAD)" == "$EXPERIMENT_SOURCE_SHA"',
            '42490269438', 'e7bbd704dea0dd924355599efc35958947ac8062',
            '13620623868', '2e1fd3dc9462c097007039fd9d0cbdcbb0ca610baeca9d8b537d3b5fa4ee5b5f',
            '13621890399', '5b37fe21a1cd82b462efbf034da7152d7dc9203773deb7831f2a06253a05897a',
            'd12da91d3bdeff9f803eb14220a07e9b5e0078bdd02ced4d1f45112885a45b60',
            '8097af2264d1b15c31589b28d1d534547d0b8edb8c754ab78eb2a7dc752a1041',
            '.run_attempt == 1', '.digest == $digest',
            'Restore complete frozen original savable RTL model objects',
            'Reconstruct exact b9 source Host on original frozen generated RTL model',
            'Zero-cycle Host-only v2 and independent real RTL 20M differential',
            'product_sd1_model_migration_v2_qualify.sh --execute-qualified-diff',
            'This A/qualification workflow cannot cross 8B',
        ):
            self.assertIn(marker, draft)
        self.assertNotIn('SD1_FULL_DEBIAN_SIGNED_RELAY_V3_MIGRATED_8P1B', draft)
        self.assertNotIn('product_sd1_migrated_v2_relay_20m.sh --execute-reviewed-20m', draft)

    def test_future_v2_relay_is_isolated_gated_and_readonly_by_default(self):
        script = ROOT / 'tools/ci/product_sd1_migrated_v2_relay_20m.sh'
        source = script.read_text()
        for marker in (
            'if [[ "${1:---plan}" == --plan',
            '--execute-reviewed-20m',
            'REVIEWED_7892_TO_7912_FULL_20M_DIFF',
            'SD1_FULL_DEBIAN_SIGNED_RELAY_V3_MIGRATED_8P1B',
            'AETHERCORE_SD1_V2_REVIEWED_MODEL_SHA',
            'QUALIFIED_HOST_ONLY_7892_TO_7912_20M_DIFF_NOT_8B_CROSS',
            '"$QUAL_DIR/qualified/7912m"',
            '"$OUT/${SOURCE_M}m"',
            '"$TARGET" -le 8100000000',
            'persistent.pre.host', 'persistent.pre.rtl',
            'source_rtl_sha256',
            'PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED',
            'product_sd1_cpu_observer_audit',
            'product_sd1_card_snapshot',
            'not_debian_pass=1',
        ):
            self.assertIn(marker, source)
        # Catch a future syntactically invalid inlined CPU/Card evidence test.
        embedded = source.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        ast.parse(embedded)
        with tempfile.TemporaryDirectory() as scratch:
            env = dict(os.environ)
            env.pop('AETHERCORE_SD1_V2_RELAY_EXECUTE', None)
            env.pop('AETHERCORE_SD1_RELAY_CONFIRM', None)
            before = list(Path(scratch).iterdir())
            plan = subprocess.run(['bash', str(script), '--plan'], cwd=scratch,
                                  env=env, capture_output=True, text=True)
            self.assertEqual(plan.returncode, 0, plan.stderr)
            self.assertIn('PLAN_ONLY no_guest_cycles=1', plan.stdout)
            denied = subprocess.run(['bash', str(script), '--execute-reviewed-20m',
                                     scratch, '7932'], cwd=scratch, env=env,
                                    capture_output=True, text=True)
            self.assertEqual(denied.returncode, 16)
            self.assertIn('requires separate reviewed v2 relay', denied.stderr)
            self.assertEqual(list(Path(scratch).iterdir()), before)


if __name__ == "__main__":
    unittest.main()
