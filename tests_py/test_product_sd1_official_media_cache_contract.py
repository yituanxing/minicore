"""Fail-closed official Debian SD1 seed cache reuse across RTL-only edits."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
DIR = ROOT / ".github/workflows"
SEED = "product-sd1-official-media-seed.yml"
CONSUMERS = (
    "product-sd1-native-qemu-oracle.yml",
    "product-sd1-c1-qemu-oracle.yml",
    "product-sd1-persistent-short.yml",
    "product-sd1-c1-checkpoint-relay.yml",
    "product-sd1-official-artifact-relay.yml",
)
LEGACY_SHA = "327da1449dc81566d524cd0d159dba0c5c6ad83d001b00e00717e1b9ede26cf0"
LEGACY_KEY = f"product-sd1-official-seed-v1-{LEGACY_SHA}"
PREFIX = "product-sd1-official-seed-v2-"
REQUIRED_SOURCES = (
    "software/rv64/product_sd1_official_seed.lock",
    "build.mill",
    "rv64_debian_sd1_official_full_rootfs_prepare.sh",
    "rv64_debian_sd1_sd_image_build.sh",
    "rv64_debian_payload_build.sh",
    "rv64_debian_linux_build.sh",
    "rv64_debian_rootfs_build.sh",
    "rv64_debian_sd1_tree_digest.py",
    "rv64_linux_early_build.sh",
    "rv64_minimal_init_payload_build.sh",
    "ensure_riscv64_linux_gcc_13_3.sh",
    "software/rv64/linux_early.env",
    "software/rv64/opensbi_first_exec.env",
)


class Sd1OfficialMediaCacheContract(unittest.TestCase):
    def test_all_six_workflows_share_exact_media_sources_not_hardware_rtl(self):
        keys = []
        for name in (SEED, *CONSUMERS):
            source = (DIR / name).read_text()
            matches = re.findall(
                r"^\s+key: .+product-sd1-official-seed-v2-.+$",
                source, flags=re.MULTILINE,
            )
            self.assertEqual(len(matches), 1, name)
            key = matches[0].strip()
            self.assertIn(PREFIX, key)
            self.assertNotIn("src/main/scala", key, name)
            for required in REQUIRED_SOURCES:
                self.assertIn(required, key, (name, required))
            keys.append(key)
        self.assertEqual(len(set(keys)), 1, "one seed key for all consumers")

    def test_only_one_known_legacy_cache_key_can_migrate(self):
        for name in CONSUMERS:
            source = (DIR / name).read_text()
            self.assertEqual(source.count(LEGACY_KEY), 2, name)
            self.assertRegex(
                source, rf"restore-keys: \|\n\s+aethercore-.*{LEGACY_SHA}",
                name,
            )
            # The restoration output is examined, not merely a truthy
            # "cache-hit" false result that any restore-key might produce.
            self.assertIn("outputs.cache-matched-key", source, name)
            self.assertIn(f"== false &&", source, name)
            self.assertNotIn(
                "product-sd1-official-seed-v1-\n",
                source,
                "broad legacy restore prefix would select unverified media",
            )

    def test_never_rekey_before_byte_sha_and_manifest_verification(self):
        for name in CONSUMERS:
            source = (DIR / name).read_text()
            verify = source.find("python3 tools/ci/product_sd1_official_seed.py verify")
            save = source.find("Re-key individually SHA-verified official Debian media")
            self.assertGreaterEqual(verify, 0, name)
            self.assertGreater(save, verify, name)
            self.assertIn("actions/cache/save@v4", source[save:save+600], name)
            self.assertIn("outputs.cache-primary-key", source[save:save+600], name)
            self.assertIn("outputs.cache-hit == 'false'", source[save:save+600], name)
        producer = (DIR / SEED).read_text()
        self.assertIn("actions/cache/save@v4", producer)
        self.assertNotIn("Re-key individually", producer)


if __name__ == "__main__":
    unittest.main()
