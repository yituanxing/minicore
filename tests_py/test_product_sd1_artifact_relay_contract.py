"""No-RTL public SD1 Actions-artifact relay safety/source contract checks."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-official-artifact-relay.yml"
HELPER = ROOT / "tools/ci/product_sd1_artifact_relay.sh"


class PublicSD1ArtifactRelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text()
        cls.helper = HELPER.read_text()

    def test_shell_parses(self):
        result = subprocess.run(["bash", "-n", str(HELPER)], check=False,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_no_automatic_or_fork_trigger(self):
        self.assertIn("types: [labeled]", self.workflow)
        self.assertNotIn("  push:", self.workflow)
        self.assertNotIn("  schedule:", self.workflow)
        self.assertNotIn("  workflow_run:", self.workflow)
        self.assertNotIn("  workflow_dispatch:", self.workflow)
        self.assertIn("product-sd1-public-artifact-relay", self.workflow)
        self.assertIn("github.event.pull_request.number == 379", self.workflow)
        self.assertIn("head.repo.full_name == github.repository", self.workflow)
        self.assertIn("persist-credentials: false", self.workflow)

    def test_only_signed_seed_exact_hit_before_expensive_model(self):
        self.assertIn("product-sd1-native-qemu-oracle.yml", self.workflow)
        self.assertIn("PUBLIC_SD1_RELAY_QEMU_GATE_PENDING", self.workflow)
        self.assertIn("product-sd1-qemu-native-init-", self.workflow)
        self.assertIn("native_pid1=/sbin/init", self.workflow)
        self.assertIn("login_prompt=OBSERVED_NOT_AUTHENTICATED", self.workflow)
        self.assertIn("PUBLIC_SD1_RELAY_QEMU_MEDIA_IDENTITY_MISMATCH", self.workflow)
        self.assertIn("actions/cache/restore@v4", self.workflow)
        self.assertIn("steps.seed-cache.outputs.cache-hit", self.workflow)
        self.assertIn("python3 tools/ci/product_sd1_official_seed.py verify", self.workflow)
        self.assertIn("product_sim_sd1_manual.sh preflight", self.workflow)
        self.assertLess(self.workflow.index("Verify signed official Debian/rootfs/image"),
                        self.workflow.index("First-run Java 17"))
        seed_restore = self.workflow.split("id: seed-cache", 1)[1].split("- name:", 1)[0]
        self.assertIn("product-sd1-official-seed-v2-", seed_restore)
        self.assertIn("product-sd1-official-seed-v1-327da1449dc81566d524cd0d159dba0c5c6ad83d001b00e00717e1b9ede26cf0", seed_restore)
        self.assertIn("steps.seed-cache.outputs.cache-matched-key", self.workflow)

    def test_explicit_public_test_and_no_credentials(self):
        self.assertIn("PUBLIC_CREDENTIAL_FREE_SD1_TEST", self.helper)
        self.assertIn("console_login_credentials=NOT_PROVISIONED", self.helper)
        self.assertIn("console_login_user=NONE", self.helper)
        self.assertIn("RV64_SD1_LOGIN_PASSWORD_HASH", self.helper)
        self.assertIn("AETHERCORE_PRODUCT_SD_IMAGE", self.helper)
        self.assertIn("source_commit", self.helper)
        self.assertIn("official_sd_sha256", self.helper)

    def test_full_state_original_model_and_sha_checks(self):
        self.assertIn("persistent.pre.host", self.helper)
        self.assertIn("persistent.pre.rtl", self.helper)
        self.assertIn("simulator", self.helper)
        self.assertIn("ARTIFACT_COMPLETE", self.helper)
        self.assertIn("sha256sum --check --status ARTIFACT_COMPLETE", self.helper)
        self.assertIn("product_sd1_relay_state.py", self.helper)
        self.assertIn("product_sd1_checkpoint_relay.sh verify", self.helper)
        self.assertIn("original simulator binary SHA mismatch", self.helper)
        self.assertIn("chmod 0755", self.helper)
        self.assertIn("source artifact remains intact", self.helper)

    def test_limited_explicit_cycle_windows_and_no_false_pass(self):
        self.assertIn("next - old <= 20000000", self.helper)
        self.assertIn("initial + 100000000", self.workflow)
        self.assertIn("for step in 1 2 3 4 5", self.workflow)
        self.assertIn("next <= 4000000000", self.helper)
        self.assertIn("ext4_root_mounted", self.helper)
        self.assertIn("native_init_started", self.helper)
        self.assertIn("read_flag \"$state\" login", self.helper)
        self.assertIn("NOT YET NATIVE LOGIN PASS", self.workflow)

    def test_artifact_scope_and_expiry(self):
        self.assertIn("actions/download-artifact@v4", self.workflow)
        self.assertIn("actions/upload-artifact@v4", self.workflow)
        self.assertIn("include-hidden-files: true", self.workflow)
        self.assertIn("retention-days: 14", self.workflow)
        self.assertIn("path: build/product-sd1-public-artifact-relay/publish/", self.workflow)
        self.assertNotIn("path: build/rv64-debian-sd1-sd/", self.workflow)

    def test_no_authorization_means_no_guest_cycles(self):
        result = subprocess.run(["bash", str(HELPER), "bootstrap", "no-such-artifact"],
                                cwd=ROOT, check=False, capture_output=True, text=True,
                                env={"PATH": "/usr/bin:/bin"})
        self.assertEqual(result.returncode, 3)
        self.assertIn("explicit public credential-free", result.stderr)
        self.assertFalse((ROOT / "no-such-artifact").exists())

    def test_cross_commit_fingerprint_reuses_fpga_only_but_rejects_rtl_abi_or_seed_change(self):
        """No production files change: exercise a disposable tiny source tree."""
        with tempfile.TemporaryDirectory(prefix="sd1-public-compat-") as temp:
            root = Path(temp)
            helper = root / "tools/ci/product_sd1_artifact_relay.sh"
            helper.parent.mkdir(parents=True)
            shutil.copy2(HELPER, helper)
            pinned = (
                "build.mill", "mill",
                "software/rv64/product_sd1_official_seed.lock",
                "software/rv64/linux_early.env",
                "software/rv64/opensbi_first_exec.env",
                "tools/ensure_riscv64_linux_gcc_13_3.sh",
                "tools/ensure_verilator_5_024.sh",
                "tools/ensure_verilator_5_054.sh",
                "tools/ci/product_sd1_checkpoint_relay.sh",
                "tools/ci/product_checkpoint_sd1_short.sh",
                "tools/ci/product_sim_sd1_manual.sh",
                "tools/ci/product_sim_ab.sh",
                "tools/ci/product_model_identity.sh",
                "tools/ci/product_sim_sd1_card_probe.cpp",
                "tools/ci/product_sim_cache.sh",
                "tools/ci/product_sd1_relay_state.py",
                "tools/ci/product_sd1_official_seed.py",
                "tools/ci/rv64_debian_payload_build.sh",
                "tools/ci/rv64_debian_sd1_sd_image_build.sh",
                "tools/ci/rv64_debian_sd1_official_full_rootfs_prepare.sh",
                "tools/ci/rv64_debian_linux_build.sh",
                "tools/ci/rv64_debian_rootfs_build.sh",
                "tools/ci/rv64_debian_sd1_tree_digest.py",
                "tools/ci/rv64_linux_early_build.sh",
                "tools/ci/rv64_minimal_init_payload_build.sh",
                "sim/l32_sd_spi_card.h",
                "sim/l32_sd_spi_snapshot_codec.h",
                "sim/product_rv64_checkpoint_sd1_main.cpp",
                "src/main/scala/aethercore/AetherCore.scala",
                "software/rv64_userspace/debian_d3_init.sh",
            )
            for relative in pinned:
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text("frozen compatibility source\n")

            def fingerprint() -> str:
                result = subprocess.run(
                    ["bash", str(helper), "fingerprint"], cwd=root,
                    capture_output=True, text=True, check=True,
                )
                match = re.fullmatch(
                    r"PRODUCT_SD1_PUBLIC_ARTIFACT_COMPATIBILITY_SHA256=([a-f0-9]{64})\n",
                    result.stdout,
                )
                self.assertIsNotNone(match)
                return match.group(1)

            original = fingerprint()
            # Real afbb6b89 vs bc37c290 changed only XC7 mapping metadata,
            # tests and helper; none affected an executed SD1 RTL prefix.
            for relative in (
                "docs/architecture/PRODUCT_FPGA_XC7_FIRST_MAPPED_RESULTS.md",
                "tests_py/test_product_fpga_xc7_unplaced.py",
                "tools/ci/product_fpga_xc7_unplaced.sh",
            ):
                item = root / relative
                item.parent.mkdir(parents=True, exist_ok=True)
                item.write_text("different unrelated FPGA mapping commit\n")
                self.assertEqual(fingerprint(), original, relative)
            for relative in (
                "sim/l32_sd_spi_card.h",
                "sim/product_rv64_checkpoint_sd1_main.cpp",
                "src/main/scala/aethercore/AetherCore.scala",
                "tools/ci/product_checkpoint_sd1_short.sh",
                "tools/ci/rv64_debian_rootfs_build.sh",
                "software/rv64_userspace/debian_d3_init.sh",
                "software/rv64/product_sd1_official_seed.lock",
            ):
                item = root / relative
                original_bytes = item.read_text()
                item.write_text(original_bytes + "unsafe hardware or media modification\n")
                self.assertNotEqual(fingerprint(), original, relative)
                item.write_text(original_bytes)
            self.assertEqual(fingerprint(), original)

    def test_original_commit_is_audit_only_but_compatibility_is_verified(self):
        self.assertIn('compatibility_sha256', self.helper)
        self.assertIn('PRODUCT_SD1_PUBLIC_ARTIFACT_COMPATIBILITY_SHA256', self.helper)
        self.assertIn('"$(field "$manifest" compatibility_sha256)" == "$(compatibility_sha)"', self.helper)
        self.assertNotIn('"$(field "$manifest" source_commit)" == "$commit"', self.helper)


if __name__ == "__main__":
    unittest.main()
