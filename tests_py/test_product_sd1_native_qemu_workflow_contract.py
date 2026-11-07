"""No-guest, source-only checks for the independent exact-media QEMU gate."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-native-qemu-oracle.yml"
SEED = ROOT / ".github/workflows/product-sd1-official-media-seed.yml"
ORACLE = ROOT / "tools/ci/product_sd1_qemu_init_oracle.sh"


class Sd1NativeQemuWorkflowContract(unittest.TestCase):
    def test_first_party_label_and_default_branch_explicit_only(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("  workflow_dispatch:", workflow)
        self.assertIn("  pull_request:\n    types: [labeled]", workflow)
        self.assertNotIn("  push:", workflow)
        self.assertNotIn("  schedule:", workflow)
        self.assertIn("product-sd1-native-qemu-oracle'", workflow)
        self.assertIn("github.event.pull_request.number == 379", workflow)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", workflow)
        self.assertIn("github.event.pull_request.base.ref == github.event.repository.default_branch", workflow)
        self.assertIn("github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'", workflow)
        self.assertIn("RUN_SD1_QEMU_NATIVE_INIT_ORACLE", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn('test "$(git rev-parse HEAD)" = "$TARGET_SHA"', workflow)

    def test_cache_is_bit_exact_to_approved_seed_not_partial_downloads(self):
        workflow = WORKFLOW.read_text()
        seed = SEED.read_text()
        seed_key = next(line.strip() for line in seed.splitlines()
                        if line.strip().startswith("key: aethercore-")
                        and "product-sd1-official-seed-v2" in line)
        qemu_key = next(line.strip() for line in workflow.splitlines()
                        if line.strip().startswith("key: aethercore-")
                        and "product-sd1-official-seed-v2" in line)
        self.assertEqual(qemu_key, seed_key)
        expected = [
            "build/rv64-debian-rootfs/result.txt",
            "build/rv64-debian-linux/result.txt",
            "build/rv64-debian-linux/obj/.config",
            "build/rv64-debian-linux/obj/arch/riscv/boot/Image",
            "build/rv64-debian-boot",
            "build/rv64-debian-sd1-sd",
            "build/product-sd1-official-seed",
        ]
        block = workflow.split("- name: Restore ONLY the exact official signed Debian SD1 media cache", 1)[1]
        block = block.split("      - name:", 1)[0]
        for path in expected:
            self.assertIn(path, block)
        self.assertIn("steps.media_cache.outputs.cache-hit", workflow)
        self.assertIn("python3 tools/ci/product_sd1_official_seed.py verify", workflow)
        self.assertIn("product-sd1-official-seed-v1-327da1449dc81566d524cd0d159dba0c5c6ad83d001b00e00717e1b9ede26cf0", block)
        self.assertIn("outputs.cache-matched-key", workflow)

    def test_virtio_is_qualified_separately_from_product_spi_and_rtl(self):
        workflow = WORKFLOW.read_text()
        oracle = ORACLE.read_text()
        self.assertIn("timeout-minutes: 70", workflow)
        self.assertIn("product_sd1_qemu_init_oracle.sh prepare", workflow)
        self.assertIn("product_sd1_qemu_init_oracle.sh run", workflow)
        self.assertIn("PRODUCT_SD1_QEMU_NATIVE_DEBIAN_INIT_LOGIN_PROMPT_PASS_NOT_RTL", workflow)
        self.assertIn("RV64_SD1_QEMU_TIMEOUT_SECONDS: '540'", workflow)
        self.assertIn("-e VIRTIO -e VIRTIO_MMIO -e VIRTIO_BLK", oracle)
        self.assertNotIn("product_sim_sd1_manual.sh longrun", workflow)
        self.assertNotIn("product_sd1_checkpoint_relay.sh", workflow)
        self.assertNotIn("build/rv64-debian-sd1-sd/sd-rootfs.img\n          retention", workflow)
        self.assertIn("Re-key individually SHA-verified official Debian media", workflow)

    def test_gcc_path_is_consumed_only_after_github_env_step_boundary(self):
        workflow = WORKFLOW.read_text()
        bootstrap = workflow.split("- name: Validate exact GCC and pinned upstream source", 1)[1]
        bootstrap, prepare = bootstrap.split("- name: Prepare independent QEMU virt kernel", 1)
        self.assertIn('grep -Eq \'^AETHERCORE_RV64_LINUX_CROSS_COMPILE=/', bootstrap)
        self.assertNotIn('test -n "$AETHERCORE_RV64_LINUX_CROSS_COMPILE"', bootstrap)
        self.assertIn('[[ "${AETHERCORE_RV64_LINUX_CROSS_COMPILE:-}" == /* ]]', prepare)

    def test_qemu_prerequisites_never_pull_unneeded_gui_or_discard_deb_cache(self):
        workflow = WORKFLOW.read_text()
        install = workflow.split("- name: Install QEMU virt and native host build prerequisites", 1)[1]
        install = install.split("      - name:", 1)[0]
        self.assertIn("--no-install-recommends", install)
        self.assertIn('command -v qemu-system-riscv64', install)
        self.assertIn('"Dir::Cache::archives=$apt_archives"', install)
        self.assertIn("APT::Keep-Downloaded-Packages=true", install)
        self.assertIn("Reuse exact Ubuntu Noble QEMU prerequisite .deb downloads", workflow)
        self.assertIn("actions/cache@v4", workflow)


if __name__ == "__main__":
    unittest.main()
