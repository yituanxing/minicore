"""Cheap fail-closed source gates for optional public C1 state transfer.

Never executes a Linux boot, mutates SD1 seed, or uploads guest memory.
"""

from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_c1_artifact_relay.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-c1-checkpoint-relay.yml"
QEMU = ROOT / ".github/workflows/product-sd1-c1-qemu-oracle.yml"
SOURCE_SEED = ROOT / ".github/workflows/product-sd1-persistent-short.yml"
SEED_CACHE_KEY = SOURCE_SEED.read_text().split("key: aethercore-${{ runner.os }}-${{ runner.arch }}-product-sd1-official-seed-v2-", 1)[1].split("\n", 1)[0]


class ProductC1PublicArtifactContract(unittest.TestCase):
    def test_shell_parses_and_rejects_unsigned_or_missing_explicit_opt_in(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], cwd=ROOT, check=True)
        env = dict(os.environ)
        for name in (
            "AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT",
            "AETHERCORE_SD1_C1_CONFIRM",
            "AETHERCORE_SD1_RELAY_CONFIRM",
        ):
            env.pop(name, None)
        result = subprocess.run(
            ["bash", str(SCRIPT), "restore", "/definitely/not/a/real/C1/artifact"],
            cwd=ROOT, env=env, capture_output=True, text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("explicit C1 portable first-party profile required", result.stderr)

    def test_workflow_is_deliberate_first_party_only_and_bounded(self):
        workflow = WORKFLOW.read_text()
        for required in (
            "types: [labeled]",
            "product-sd1-c1-public-artifact-relay",
            "head.repo.full_name == github.repository",
            "github.event.pull_request.number == 379",
            "PUBLIC_C1_FULL_DDR_ARTIFACT_RELAY",
            "permissions:",
            "actions: read",
            "contents: read",
            "persist-credentials: false",
            "timeout-minutes: 160",
            "WINDOWS" ,
            "(( next <= 4000000000 ))",
            "+ 20000000",
            "include-hidden-files: true",
            "retention-days: 14",
        ):
            self.assertIn(required, workflow)
        self.assertNotIn("  push:", workflow)
        self.assertNotIn("  schedule:", workflow)
        self.assertNotIn("  write", workflow)
        self.assertIn("Re-key individually SHA-verified official Debian media", workflow)
        self.assertNotIn("build/product-sd1-c1-checkpoint-relay/publish/\n          key:", workflow)
        self.assertNotIn("product_sim_sd1_manual.sh longrun", workflow)

    def test_reuses_identical_official_seed_and_never_rebuilds_on_advance(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("key: aethercore-${{ runner.os }}-${{ runner.arch }}-product-sd1-official-seed-v2-" + SEED_CACHE_KEY, workflow)
        self.assertIn("key: aethercore-${{ runner.os }}-${{ runner.arch }}-scala-v1-${{ hashFiles('build.mill', 'mill') }}", workflow)
        self.assertIn("key: aethercore-${{ runner.os }}-${{ runner.arch }}-verilator-product-gsim-g3-v1-${{ hashFiles('tools/ensure_verilator_5_024.sh', 'tools/ensure_verilator_5_054.sh') }}", workflow)
        self.assertIn("steps.sealed_media.outputs.cache-hit", workflow)
        self.assertIn("product_sd1_official_seed.py verify", workflow)
        self.assertIn("steps.source.outputs.mode == 'seed'", workflow)
        self.assertIn("steps.source.outputs.mode == 'advance'", workflow)
        self.assertIn("product_sd1_c1_initramfs_build.sh prepare", workflow)
        self.assertIn("product_sd1_c1_kernel_build.sh prepare", workflow)
        self.assertIn("product_sd1_c1_firmware_build.sh prepare", workflow)
        self.assertIn("product_checkpoint_sd1_short.sh build", workflow)
        self.assertIn("product_sd1_c1_checkpoint.sh advance", workflow)
        self.assertIn("product_sd1_c1_artifact_relay.sh restore", workflow)
        self.assertIn('steps.source.outputs.mode == \'seed\'', workflow)
        self.assertIn('product-sd1-native-qemu-oracle.yml product-sd1-c1-qemu-oracle.yml', workflow)
        self.assertIn("C1_RTL_LONGRUN_QEMU_ORACLE_VERIFIED", workflow)
        self.assertIn('status=success -f branch="$GITHUB_HEAD_REF"', workflow)
        self.assertIn('candidate_lines="$(gh api', workflow)
        self.assertIn("C1_AUTO_SOURCE_QUERY_FAILED", workflow)
        self.assertIn("C1_AUTO_RESEED_DENIED", workflow)
        self.assertIn('elif [[ -n "$candidate_lines" ]]', workflow)
        self.assertIn('mode=advance', workflow)
        self.assertNotIn("product_sd1_c1_checkpoint.sh release", workflow)
        self.assertNotEqual(QEMU, WORKFLOW)

    def test_compatibility_reuses_rootfs_only_commit_but_rejects_rtl_or_c1_change(self):
        """Construct a tiny disposable checkout, no mutations of actual sources."""
        with tempfile.TemporaryDirectory(prefix="c1-public-compat-") as temp:
            root = Path(temp)
            copy = root / "tools/ci/product_sd1_c1_artifact_relay.sh"
            copy.parent.mkdir(parents=True)
            shutil.copy2(SCRIPT, copy)
            pinned = (
                "build.mill", "mill", "software/rv64/linux_early.env",
                "software/rv64/opensbi_first_exec.env",
                "software/rv64/product_sd1_official_seed.lock",
                "software/rv64_userspace/product_sd1_c1_init.c",
                "tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch",
                "tools/ci/product_checkpoint_sd1_short.sh",
                "tools/ci/product_sim_cache.sh", "tools/ci/product_sim_sd1_manual.sh",
                "tools/ci/product_sd1_official_seed.py",
                "tools/ci/rv64_minimal_init_payload_build.sh",
                "tools/ci/rv64_linux_early_build.sh",
                "tools/ensure_riscv64_linux_gcc_13_3.sh",
                "tools/ensure_verilator_5_024.sh", "tools/ensure_verilator_5_054.sh",
                "tools/ci/product_sd1_c1_kernel_build.sh",
                "tools/ci/product_sd1_c1_firmware_build.sh",
                "tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch",
                "sim/l32_sd_spi_card.h", "sim/l32_sd_spi_snapshot_codec.h",
                "sim/product_rv64_checkpoint_sd1_main.cpp",
                "src/main/scala/aethercore/AetherCore.scala",
            )
            for relative in pinned:
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text("frozen source\n")

            def fingerprint() -> str:
                result = subprocess.run(
                    ["bash", str(copy), "fingerprint"], cwd=root,
                    check=True, capture_output=True, text=True,
                )
                match = re.fullmatch(r"PRODUCT_SD1_C1_COMPATIBILITY_SHA256=([a-f0-9]{64})\n", result.stdout)
                self.assertIsNotNone(match)
                return match.group(1)

            original = fingerprint()
            # This is precisely the intended cross-commit replacement: a
            # different Debian/Alpine rootfs, no new kernel or pre-mount code.
            rootfs_only = root / "tools/ci/rv64_debian_rootfs_build.sh"
            rootfs_only.write_text("different rootfs package tree\n")
            self.assertEqual(fingerprint(), original)
            rootfs_only.write_text("rootfs changed again\n")
            self.assertEqual(fingerprint(), original)

            for relative in (
                "sim/l32_sd_spi_card.h",
                "sim/product_rv64_checkpoint_sd1_main.cpp",
                "src/main/scala/aethercore/AetherCore.scala",
                "software/rv64_userspace/product_sd1_c1_init.c",
                "tools/ci/product_sd1_c1_firmware_build.sh",
                "software/rv64/product_sd1_official_seed.lock",
            ):
                file = root / relative
                before = file.read_text()
                file.write_text(before + "semantic ABI modification\n")
                self.assertNotEqual(fingerprint(), original, relative)
                file.write_text(before)
            self.assertEqual(fingerprint(), original)

    def test_public_ddr_requires_credential_free_exact_origin_and_v2_read_ledger(self):
        workflow = WORKFLOW.read_text()
        script = SCRIPT.read_text()
        for required in (
            "console_login_credentials",
            "NOT_PROVISIONED",
            "console_login_user",
            "AETHERCORE_SD1_C1_CONFIRM",
            "AETHERCORE_SD1_RELAY_CONFIRM",
            "read_ledger=SDHC_V2_IN_HOST_PRESTATE",
            "AETHERCORE_C1_READY rootfs_unmounted=1",
            "spi_quiescent=1",
            "phase=armed",
        ):
            self.assertIn(required, script)
        self.assertIn("rootfs_binding=NONE", script)
        self.assertIn("bash tools/ci/product_sd1_c1_checkpoint.sh verify", script)
        self.assertIn("persistent.pre.host", script)
        self.assertIn("persistent.pre.rtl", script)
        self.assertIn(".savable-source-key", script)
        self.assertIn('chmod 0755 "$ROOT/$model"', script)
        self.assertLess(script.index('sha256sums_sha256'), script.index('chmod 0755 "$ROOT/$model"'))
        self.assertLess(script.index('chmod 0755 "$ROOT/$model"'), script.rindex('bash tools/ci/product_sd1_c1_checkpoint.sh verify'))
        self.assertIn("sha256sums_sha256", script)
        self.assertIn("head_sha", script)
        self.assertIn("seed_sha256", script)
        self.assertIn("no_cold_replay=1", script)
        self.assertIn("retention-days: 14", workflow)


if __name__ == "__main__":
    unittest.main()
