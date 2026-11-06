"""No guest execution: verify the manual, exact-media C1 QEMU CI gate."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-c1-qemu-oracle.yml"
SEED = ROOT / ".github/workflows/product-sd1-official-media-seed.yml"
C1_ORACLE = ROOT / "tools/ci/product_sd1_c1_qemu_oracle.sh"


class ProductSd1C1QemuWorkflowContract(unittest.TestCase):
    def test_no_implicit_rtl_launch_or_unreviewed_pr(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("  workflow_dispatch:", workflow)
        self.assertIn("  pull_request:\n    types: [labeled]", workflow)
        self.assertNotIn("  push:", workflow)
        self.assertNotIn("  schedule:", workflow)
        self.assertIn("RUN_SD1_C1_QEMU_ROOTFS_HANDOFF", workflow)
        self.assertIn("github.event.label.name == 'product-sd1-c1-qemu-oracle'", workflow)
        self.assertIn("github.event.pull_request.number == 379", workflow)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", workflow)
        self.assertIn("github.event.pull_request.base.ref == github.event.repository.default_branch", workflow)
        self.assertIn("github.event.pull_request.head.ref == 'design/rv64gc-product-architecture-20261103'", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("timeout-minutes: 95", workflow)
        self.assertNotIn("product_sd1_checkpoint_relay.sh", workflow)
        self.assertNotIn("product_sim_sd1_manual.sh longrun", workflow)

    def test_exact_seed_cache_and_no_unsafe_partial_source_fallback(self):
        workflow = WORKFLOW.read_text()
        seed = SEED.read_text()
        seed_key = next(line.strip() for line in seed.splitlines()
                        if "product-sd1-official-seed-v1" in line and line.strip().startswith("key:"))
        qemu_key = next(line.strip() for line in workflow.splitlines()
                        if "product-sd1-official-seed-v1" in line and line.strip().startswith("key:"))
        self.assertEqual(seed_key, qemu_key)
        for required in (
                "build/rv64-debian-rootfs/result.txt",
                "build/rv64-debian-linux/obj/.config",
                "build/rv64-debian-linux/obj/arch/riscv/boot/Image",
                "build/rv64-debian-boot",
                "build/rv64-debian-sd1-sd",
                "build/product-sd1-official-seed"):
            self.assertIn(required, workflow)
        self.assertIn("steps.sealed_media.outputs.cache-hit", workflow)
        self.assertIn("python3 tools/ci/product_sd1_official_seed.py verify", workflow)
        self.assertNotIn("restore-keys:", workflow)
        self.assertNotIn("build/product-sd1-official-apt-cache", workflow)

    def test_explicit_rootfs_agnostic_c1_sequence_and_qualified_bound(self):
        workflow = WORKFLOW.read_text()
        oracle = C1_ORACLE.read_text()
        self.assertIn("product_sd1_c1_initramfs_build.sh prepare", workflow)
        self.assertIn("product_sd1_c1_kernel_build.sh prepare", workflow)
        self.assertIn("product_sd1_c1_qemu_oracle.sh prepare", workflow)
        self.assertIn("product_sd1_c1_qemu_oracle.sh run", workflow)
        self.assertIn("PRODUCT_SD1_C1_QEMU_ROOTFS_HANDOFF_PASS_NOT_RTL", workflow)
        self.assertIn("rootfs_binding=NONE", workflow)
        self.assertIn("RV64_SD1_C1_QEMU_TIMEOUT_SECONDS: '540'", workflow)
        self.assertIn("QEMU_VIRTIO_C1_ONLY_NOT_PRODUCT_SPI_RTL", oracle)
        self.assertNotIn("actions/cache/save@", workflow)
        self.assertIn("retention-days: 7", workflow)


if __name__ == "__main__":
    unittest.main()
