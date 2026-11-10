"""C1's real RTL SD must see the same reviewed GPIO-CS clock fix as Host-v4.

These source-level gates deliberately do not relabel old C1 checkpoints as
compatible, run new guest cycles, or alter the signed Debian kernel/media.
"""

from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
BUILDER = ROOT / "tools/ci/product_sd1_c1_kernel_build.sh"
PATCH = ROOT / "tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch"
ARTIFACT = ROOT / "tools/ci/product_sd1_c1_artifact_relay.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-c1-checkpoint-relay.yml"
EXPECTED_PATCH_SHA = "ff408c203b461d504f48152840698b077f8ea948a07ad5235f4afb8436282638"
HISTORICAL_RESEED_COMPAT_SHA = "207e79b9f198111e61c891d9a4e93169808b4f84fb651778b154d0e3eb10546f"


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
            'verify_mmc_spi_object_provenance',
            'grep -Fq "$C1_SOURCE/drivers/mmc/host/mmc_spi.c" "$new_cmd"',
            'cmp -s "$new_obj" "$old_obj"',
            'gpio_cs_patch_sha256=',
            'patched_mmc_spi_sha256=',
            "rootfs_binding=NONE",
        ):
            self.assertIn(required, script)
        self.assertNotIn('make -C "$KERNEL_SOURCE"', script)

    def test_mmc_spi_object_provenance_missing_equal_and_different_baselines(self):
        script = BUILDER.read_text()
        start = script.index('verify_mmc_spi_object_provenance() {')
        end = script.index('\nverify_mmc_spi_object_provenance\n', start)
        check = script[start:end] + '\nverify_mmc_spi_object_provenance\n'
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            obj = root / 'c1-obj'
            seed = root / 'sealed-seed'
            source = root / 'isolated-source'
            new_dir = obj / 'drivers/mmc/host'
            old_dir = seed / 'obj/drivers/mmc/host'
            new_dir.mkdir(parents=True)
            old_dir.mkdir(parents=True)
            (source / 'drivers/mmc/host').mkdir(parents=True)
            new_obj = new_dir / 'mmc_spi.o'
            old_obj = old_dir / 'mmc_spi.o'
            cmd_file = new_dir / '.mmc_spi.o.cmd'
            new_obj.write_bytes(b'new patched mmc_spi build')
            cmd_file.write_text(
                'cmd_drivers/mmc/host/mmc_spi.o := -c '
                + str(source / 'drivers/mmc/host/mmc_spi.c') + '\n'
            )
            env = os.environ.copy()
            env.update(OBJ=str(obj), SEED=str(seed), C1_SOURCE=str(source))

            def gate():
                return subprocess.run(
                    ['bash', '-c', 'fail() { echo "GATE:$*" >&2; exit 3; }\n' + check],
                    cwd=ROOT, env=env, text=True, capture_output=True,
                )

            # The signed Debian baseline may have no mmc_spi.o at all.
            first = gate()
            self.assertEqual(first.returncode, 0, first.stderr)
            old_obj.write_bytes(b'new patched mmc_spi build')
            rejected = gate()
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn('unpatched mmc_spi object', rejected.stderr)
            old_obj.write_bytes(b'original Debian kernel object')
            self.assertEqual(gate().returncode, 0)
            cmd_file.write_text('cmd := -c /wrong/linux/mmc_spi.c\n')
            self.assertIn('isolated patched source', gate().stderr)
            cmd_file.write_text(str(source / 'drivers/mmc/host/mmc_spi.c'))
            new_obj.unlink()
            self.assertIn('object or Kbuild source provenance missing', gate().stderr)

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
        # This historical pin intentionally does not follow later V5/V6 host
        # changes. A changed fingerprint must fail closed until separately
        # reviewed; merely updating the expected test value must not arm it.
        self.assertIn(f'"$compatibility" == {HISTORICAL_RESEED_COMPAT_SHA} &&', script)
        seed_gate = script.split(
            'if [[ "$GITHUB_EVENT_NAME" == pull_request && "$mode" == seed ]]; then', 1
        )[1].split('\n          fi', 1)[0]
        self.assertIn('"$RESEED_LABEL" == product-sd1-c1-reviewed-gpio-cs-reseed-20261109', seed_gate)
        self.assertIn('C1_REVIEWED_RESEED_IDENTITY_MISMATCH', seed_gate)
        self.assertIn('exit 19', seed_gate)
        self.assertIn("C1_AUTO_RESEED_DENIED", script)
        self.assertIn("C1_RTL_LONGRUN_QEMU_ORACLE_VERIFIED", script)
        self.assertIn("github.event.label.name == 'product-sd1-c1-public-artifact-relay'", script)
        # Every code/profile change requires a newly calculated compatibility
        # SHA before the one-time PR label can spend guest cold cycles.
        calculated = subprocess.run(
            ["bash", str(ARTIFACT), "fingerprint"], cwd=ROOT,
            text=True, capture_output=True, check=True,
        ).stdout.strip().split("=", 1)[1]
        self.assertRegex(calculated, r'^[a-f0-9]{64}$')
        self.assertNotEqual(calculated, HISTORICAL_RESEED_COMPAT_SHA,
                            'new V5/V6 host identity must not inherit old reseed authorization')


if __name__ == "__main__":
    unittest.main()
