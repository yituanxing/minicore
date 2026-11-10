"""Static fail-closed V6 Host-only 20B migration contracts.

Never executes RTL guest cycles, downloads signed SD media, or labels PR379.
The 14952M->14972M differential and zero-clock 14992M migration must be
independently run in the original PR379 cache scope before approving 20B.
"""

from pathlib import Path
import re
import subprocess
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / 'sim/product_rv64_checkpoint_sd1_main.cpp'
QUAL = ROOT / 'tools/ci/product_sd1_model_migration_v6_20b_qualify.sh'
POST = ROOT / 'tools/ci/product_sd1_v6_postcross_20m.sh'
PREP = ROOT / 'tools/ci/product_sd1_v6_chain_prepare.sh'
GUARD = ROOT / 'tools/ci/product_sd1_v6_release_guard.sh'
FORMAL = ROOT / '.github/workflows/product-sd1-v6-20b-needs-14992-to-19992-INERT-DRAFT.yml'
BRIDGE = ROOT / '.github/workflows/product-sd1-v6-20m-qualification-PR379-CACHE-BRIDGE-INERT-DRAFT.yml'
V5 = '4f946c321c63528ce789440486c82ef89204fc0905985bb42a97d1ec9fe989be'


class HostV6MigrationStatic(unittest.TestCase):
    def test_host_only_boundaries_are_separate(self):
        cpp = CPP.read_text()
        self.assertIn('kV5MigratedMaxCycles = 15000000000ULL', cpp)
        self.assertIn('kV6QualificationSourceCycles = 14952000000ULL', cpp)
        self.assertIn('kV6MigrationSourceCycles = 14992000000ULL', cpp)
        self.assertIn('kV6TechnicalTrialMaxCycles = 15012000000ULL', cpp)
        self.assertIn('kV6MigratedMaxCycles = 20000000000ULL', cpp)
        self.assertIn('migrate-v6-20b', cpp)
        self.assertIn('SD1_SIGNED_V6_14992_TO_15012_TECH_TRIAL_ONLY', cpp)
        self.assertIn('SD1_FULL_DEBIAN_SIGNED_RELAY_V6_CHECKPOINT_20B', cpp)
        self.assertIn('migratedRelayV6Formal?kV6MigratedMaxCycles:', cpp)
        self.assertIn('migratedRelayV6Technical?kV6TechnicalTrialMaxCycles:', cpp)
        self.assertIn(f'"{V5}"', cpp)

    def test_migration_requires_source_sha_and_exact_two_source_cycles(self):
        cpp = CPP.read_text()
        self.assertIn('migratingV6?\n            kFrozenMigratedV5Model', cpp)
        self.assertIn('FROZEN_SIGNED_SD1_MODEL_MIGRATE_V6_20B_DIFF_14952M', cpp)
        self.assertIn('FROZEN_SIGNED_SD1_MODEL_MIGRATE_V6_20B_14992M', cpp)
        for key in ('14952m', '14992m', '14932000000', '14972000000',
                    'migrate-v6-20b historical signed lineage mismatch',
                    'requireExactMigrationBytes'):
            self.assertIn(key, cpp)

    def test_qualified_source_pins_and_real_guest_difference(self):
        script = QUAL.read_text()
        for marker in ('SOURCE_14952_HOST=aa9f1a371d736279',
                       'SOURCE_14972_HOST=2443bd6cfcd61560',
                       'SOURCE_14992_HOST=ba7e2161c5917f52',
                       'HOST_ONLY_14952_TO_14972_REAL_RTL_REVIEWED',
                       'migrate-v6-20b',
                       'FROZEN_SIGNED_SD1_MODEL_MIGRATE_V6_20B_14992M',
                       'PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles=',
                       'two_path_14972_host_rtl_identical=1',
                       'terminal_migration_guest_cycles=0',
                       'cross_15b_executed=0',
                       'full_host_except_model_sha_identical=1',
                       'generated_rtl_objects_sha256=',
                       'chmod 755 "$old_sim"',
                       'migration_origin_host_sha256=$SOURCE_14992_HOST',
                       'source_host_sha256=$SOURCE_14972_HOST',
                       'cmp -s -i 88:88'):
            self.assertIn(marker, script)
        self.assertIn('"$OUT"', script)
        self.assertIn('[[ ! -e "$OUT" ]]', script)
        result = subprocess.run(['bash', str(QUAL), '--plan'], cwd=ROOT,
                                text=True, capture_output=True, check=True)
        self.assertIn('no_guest_cycles=1', result.stdout)
        for shell in (QUAL, POST, PREP, GUARD):
            subprocess.run(['bash', '-n', str(shell)], check=True, cwd=ROOT)

    def test_original_14992_is_checked_before_new_model_checkpoint(self):
        prep = PREP.read_text()
        post = POST.read_text()
        self.assertIn('old_14992_host_sha256', prep)
        self.assertIn('qualified/14992m', prep)
        self.assertIn('cmp -s -i 88:88', prep)
        self.assertIn('source_dir="$QUAL_DIR/qualified/14992m"', prep)
        self.assertIn('source_host="$(field candidate_14992_host_sha256 "$qual")"', prep)
        self.assertIn('field migration_origin_host_sha256 "$source_dir/.migration-ready"', prep)
        self.assertIn('product-sd1-model-migration-v6-20b-qualified', prep)
        self.assertIn('product-sd1-model-migration-v6-20b-qualified', post)
        self.assertIn('candidate_14992_host_sha256', post)
        self.assertIn('two_path_14972_host_rtl_identical', post)
        self.assertIn('"$(field previous_model_sha256 "$ready")" == "$V5_MODEL"', post)
        self.assertIn('PRODUCT_SD1_PERSISTENT_CHECKPOINT_REJECTED:', post)

    def test_formal_release_remains_inert_and_no_old_v5_digest_accepted(self):
        formal = FORMAL.read_text()
        guard = GUARD.read_text()
        self.assertIn('false &&', formal)
        self.assertIn('PENDING_NEW_20B_MODEL_SHA', formal)
        self.assertIn('PENDING_V6_20B_MODEL_QUAL_MANIFEST_SHA', formal)
        self.assertIn('PENDING_REAL_V6_TECH_QUAL_RUN', formal)
        self.assertIn('sim/product_rv64_checkpoint_sd1_main.cpp', guard)
        self.assertIn('model digest not independently approved', guard)
        self.assertIn('V6_TECH_QUAL_REPORT_VERIFIED=1', guard)

    def test_new_pr379_cache_bridge_is_inert_and_qualifies_model_first(self):
        bridge = yaml.safe_load(BRIDGE.read_text())
        self.assertEqual(bridge['on']['pull_request']['types'], ['labeled'])
        self.assertEqual(bridge['permissions'], {'contents': 'read', 'actions': 'read'})
        env = bridge['env']
        self.assertEqual(env['V6_EVENT_REVIEWED_PARENT_HEAD'],
                         '367ce462c9fcc1a7da80db9ae8f67b642893717c')
        self.assertEqual(env['V6_SIGNED_ORIGIN_HEAD'],
                         '8a40835c470d059448a4871bafead04cc555bee5')
        self.assertEqual(env['V6_REVIEWED_HEAD'],
                         '99dbb5a90849c54b8c61c00ac58765e5414e0f04')
        self.assertTrue(env['V6_MODEL_SHA'].startswith('PENDING_'))
        self.assertEqual(env['AETHERCORE_SD1_V6_20B_APPROVED'],
                         'PENDING_FORMAL_5B_AFTER_TECH_REPORT_REVIEW')
        job = bridge['jobs']['v6_billion_1']
        self.assertTrue(job['if'].strip().startswith('false &&'))
        steps = job['steps']
        names = [step.get('name', '') for step in steps]
        qualifier = next(i for i, name in enumerate(names)
                         if 'Qualify independently rebuilt V6 Host model' in name)
        uploader = next(i for i, name in enumerate(names)
                        if 'Retain newly qualified V6 20B model' in name)
        model_auth = next(i for i, name in enumerate(names)
                          if 'Authenticate uploaded V6 Host qualification' in name)
        advance = next(i for i, name in enumerate(names)
                       if 'Qualify genuine RTL 14992M to 15012M' in name)
        self.assertLess(qualifier, uploader)
        self.assertLess(uploader, model_auth)
        self.assertLess(model_auth, advance)
        text = BRIDGE.read_text()
        for marker in ('V6_14952_ID: \'13739286257\'',
                       'V6_14972_ID: \'13739321621\'',
                       'V6_14992_ID: \'13738787578\'',
                       'PRODUCT_SD1_V6_20B_MODEL_ARCHIVE_PROVENANCE_VERIFIED',
                       'git diff --name-only -z "$V6_SIGNED_ORIGIN_HEAD" HEAD',
                       'product_sd1_model_migration_v6_20b_qualify.sh',
                       'sim/product_rv64_checkpoint_sd1_main.cpp',
                       'candidate_14992_host_sha256',
                       'host_model_diff_14952_to_14972_20m_verified=1'):
            self.assertIn(marker, text)
        for step in steps:
            if 'run' in step:
                subprocess.run(['bash', '-n'], input=step['run'], cwd=ROOT,
                               text=True, check=True)


if __name__ == '__main__':
    unittest.main()
