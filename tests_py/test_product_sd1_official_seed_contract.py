"""Fast source-only safety checks for explicit offline SD1 supply cache."""

from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SEED = ROOT / "tools/ci/product_sd1_official_seed.py"
WORKFLOW = ROOT / ".github/workflows/product-sd1-official-media-seed.yml"
CONSUMER = ROOT / ".github/workflows/product-sd1-persistent-short.yml"
DISCOVER = ROOT / "tools/ci/product_sd1_official_seed_discover.py"

spec = importlib.util.spec_from_file_location("sd1_seed", SEED)
sd1_seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sd1_seed)
discover_spec = importlib.util.spec_from_file_location("sd1_discover", DISCOVER)
sd1_discover = importlib.util.module_from_spec(discover_spec)
discover_spec.loader.exec_module(sd1_discover)


class ProductSd1OfficialSeedContracts(unittest.TestCase):
    def test_unreviewed_lock_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            lock = Path(temporary) / "seed.lock"
            lock.write_text("".join(f"{key}=UNSET\n" for key in sd1_seed.PINS))
            with self.assertRaisesRegex(ValueError, "unset or malformed"):
                sd1_seed.read_lock(lock)

    def test_only_exact_five_valid_source_pins_are_accepted(self):
        valid = {
            "RV64_SD1_DEBIAN_OCI_DIGEST": "sha256:" + "a" * 64,
            "RV64_SD1_DEBIAN_SNAPSHOT_UTC": "20261027T000000Z",
            "RV64_SD1_DEBIAN_SECURITY_SNAPSHOT_UTC": "20261027T000000Z",
            "RV64_SD1_DEBIAN_INRELEASE_SHA256": "b" * 64,
            "RV64_SD1_DEBIAN_SECURITY_INRELEASE_SHA256": "c" * 64,
        }
        with tempfile.TemporaryDirectory() as temporary:
            lock = Path(temporary) / "seed.lock"
            lock.write_text("".join(f"{key}={value}\n" for key, value in valid.items()))
            self.assertEqual(sd1_seed.read_lock(lock), valid)
            lock.write_text(lock.read_text() + "RV64_SD1_OTHER_PIN=yes\n")
            with self.assertRaisesRegex(ValueError, "exactly five"):
                sd1_seed.read_lock(lock)
            lock.write_text("".join(f"{key}={value}\n" for key, value in valid.items()) +
                            "RV64_SD1_DEBIAN_OCI_DIGEST=sha256:" + "0" * 64 + "\n")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                sd1_seed.read_lock(lock)

    def test_seed_requires_explicit_dispatch_or_first_party_product_pr_label(self):
        text = WORKFLOW.read_text()
        self.assertIn("  workflow_dispatch:", text)
        self.assertIn("  pull_request:\n    types: [labeled]", text)
        self.assertNotIn("  push:", text)
        self.assertNotIn("  schedule:", text)
        self.assertIn("github.event_name == 'workflow_dispatch'", text)
        self.assertIn("github.event_name == 'pull_request'", text)
        self.assertIn("github.event.label.name == 'product-sd1-official-seed'", text)
        self.assertIn("github.event.pull_request.number == 379", text)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", text)
        self.assertIn("github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'", text)
        self.assertIn("github.event.pull_request.base.ref == github.event.repository.default_branch", text)
        self.assertIn("github.event.pull_request.head.sha || github.sha", text)
        self.assertIn("ref: ${{ env.TARGET_SHA }}", text)
        self.assertIn("persist-credentials: false", text)
        self.assertIn('test "$(git rev-parse HEAD)" = "$TARGET_SHA"', text)
        self.assertIn("timeout-minutes: 150", text)
        self.assertIn("github.event.repository.default_branch", text)
        self.assertIn("SEED_OFFICIAL_SD1_ZERO_RTL_CYCLES", text)
        self.assertIn("tools/ci/product_sim_sd1_manual.sh preflight", text)
        self.assertNotIn("tools/ci/product_sim_sd1_manual.sh longrun", text)
        self.assertNotIn("run-existing", text)
        self.assertNotIn("actions/upload-artifact@", text)
        self.assertIn('[[ -z "${AETHERCORE_SD1_LONGRUN_CONFIRM:-}" ]]', text)
        self.assertIn("unset RV64_SD1_LOGIN_USER RV64_SD1_LOGIN_PASSWORD_HASH_FILE", text)

    def test_oci_candidate_requires_raw_digest_and_riscv64_index(self):
        with tempfile.TemporaryDirectory() as temporary:
            index = Path(temporary) / "raw-oci.json"
            raw = json.dumps({"manifests": [{"platform": {
                "os": "linux", "architecture": "amd64"}}]}, separators=(",", ":")).encode()
            index.write_bytes(raw)
            args = argparse.Namespace(
                oci_digest="sha256:" + hashlib.sha256(raw).hexdigest(),
                oci_index=str(index),
                main_snapshot="20261027T000000Z", security_snapshot="20261027T000000Z",
                keyring="/nonexistent",
            )
            with self.assertRaisesRegex(ValueError, "no linux/riscv64 child"):
                sd1_discover.discover(args)
            args.oci_digest = "sha256:" + "0" * 64
            with self.assertRaisesRegex(ValueError, "do not hash"):
                sd1_discover.discover(args)

    def test_seed_and_consumer_have_identical_exact_cache_paths_and_key(self):
        seed = WORKFLOW.read_text()
        consumer = CONSUMER.read_text()
        paths = [
            "build/rv64-debian-rootfs/result.txt",
            "build/rv64-debian-linux/result.txt",
            "build/rv64-debian-linux/obj/.config",
            "build/rv64-debian-linux/obj/arch/riscv/boot/Image",
            "build/rv64-debian-boot",
            "build/rv64-debian-sd1-sd",
            "build/product-sd1-official-seed",
        ]
        def cache_block(yaml):
            head = "- name: " + ("Save EXACT SHA-checked official SD1" if yaml == seed
                                 else "Restore only externally seeded exact official SD1")
            return yaml.split(head, 1)[1].split("      - name:", 1)[0]
        for yaml in (seed, consumer):
            block = cache_block(yaml)
            actual = [line.strip() for line in block.splitlines()
                      if line.strip() in paths]
            self.assertEqual(actual, paths)
            self.assertIn("product-sd1-official-seed-v1-${{ hashFiles(", block)
            self.assertIn("software/rv64/product_sd1_official_seed.lock", block)
        seed_key = next(line.strip() for line in cache_block(seed).splitlines()
                        if line.strip().startswith("key:"))
        consumer_key = next(line.strip() for line in cache_block(consumer).splitlines()
                            if line.strip().startswith("key:"))
        self.assertEqual(seed_key, consumer_key)


if __name__ == "__main__":
    unittest.main()
