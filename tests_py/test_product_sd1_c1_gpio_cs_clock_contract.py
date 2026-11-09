"""C1's real RTL SD must see the same reviewed GPIO-CS clock fix as Host-v4.

These source-level gates deliberately do not relabel old C1 checkpoints as
compatible, run new guest cycles, or alter the signed Debian kernel/media.
"""

from pathlib import Path
import hashlib
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
BUILDER = ROOT / "tools/ci/product_sd1_c1_kernel_build.sh"
PATCH = ROOT / "tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch"
ARTIFACT = ROOT / "tools/ci/product_sd1_c1_artifact_relay.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-c1-checkpoint-relay.yml"
EXPECTED_PATCH_SHA = "ff408c203b461d504f48152840698b077f8ea948a07ad5235f4afb8436282638"


class ProductC1PhysicalSdStartupContract(unittest.TestCase):
    def test_reviewed_gpio_cs_fix_is_pinned_and_real_cs_off_clocks(self):
        self.assertEqual(hashlib.sha256(PATCH.read_bytes()).hexdigest(), EXPECTED_PATCH_SHA)
        source = PATCH.read_text()
        self.assertIn("spi_get_csgpiod(host->spi, 0)", source)
        self.assertIn("host->status.cs_off = 1", source)
        self.assertIn("mmc_spi_readbytes(host, 18)", source)
        self.assertIn("host->status.cs_off = 0", source)

    def test_builder_only_patches_isolated_clone_and_rebuilds_mmc_spi(self):
        script = BUILDER.read_text()
        subprocess.run(["bash", "-n", str(BUILDER)], check=True, cwd=ROOT)
        self.assertIn(EXPECTED_PATCH_SHA, script)
        for required in (
            '[[ "$RV64_LINUX_VERSION" == 6.6.143',
            'cp -a --reflink=auto "$KERNEL_SOURCE" "$C1_SOURCE"',
            'patch --dry-run --batch --forward -p1 -d "$C1_SOURCE"',
            'patch --batch --forward -p1 -d "$C1_SOURCE"',
            '"$(sha256sum "$original_driver" | cut -d\' \' -f1)" == "$upstream_driver_sha"',
            'make -C "$C1_SOURCE"',
            'cmp -s "$OBJ/drivers/mmc/host/mmc_spi.o" "$SEED/obj/drivers/mmc/host/mmc_spi.o"',
            'gpio_cs_patch_sha256=',
            'patched_mmc_spi_sha256=',
            "rootfs_binding=NONE",
        ):
            self.assertIn(required, script)
        self.assertNotIn('make -C "$KERNEL_SOURCE"', script)

    def test_patch_changes_c1_compatibility_and_old_312m_is_not_reusable(self):
        script = ARTIFACT.read_text()
        self.assertIn('tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch', script)
        self.assertIn("-name 'product_sd1_c1_*.sh'", script)
        self.assertIn('compatibility_sha256=', script)
        # A new pinned C1 kernel must invalidate the sealed old firmware/image
        # and fail closed before a simulator consumes stale DDR/CPU state.
        checkpoint = (ROOT / "tools/ci/product_sd1_c1_checkpoint.sh").read_text()
        self.assertIn('C1 checkpoint stale: $key identity differs', checkpoint)

    def test_one_time_reseed_is_explicit_and_review_pinned(self):
        script = WORKFLOW.read_text()
        self.assertIn("product-sd1-c1-reviewed-gpio-cs-reseed-20261109", script)
        self.assertIn("C1_REVIEWED_RESEED_IDENTITY_MISMATCH", script)
        self.assertIn("dd236539b4f9bea942353cec7e435fb84ffb6c79c1eeb02f683b0caabd91e13c", script)
        self.assertIn("C1_AUTO_RESEED_DENIED", script)
        self.assertIn("C1_RTL_LONGRUN_QEMU_ORACLE_VERIFIED", script)
        self.assertIn("github.event.label.name == 'product-sd1-c1-public-artifact-relay'", script)
        # Every code/profile change requires a newly calculated compatibility
        # SHA before the one-time PR label can spend guest cold cycles.
        calculated = subprocess.run(
            ["bash", str(ARTIFACT), "fingerprint"], cwd=ROOT,
            text=True, capture_output=True, check=True,
        ).stdout.strip().split("=", 1)[1]
        self.assertEqual(calculated, "dd236539b4f9bea942353cec7e435fb84ffb6c79c1eeb02f683b0caabd91e13c")


if __name__ == "__main__":
    unittest.main()
