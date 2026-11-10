"""Cheap, zero-RTL contracts for the opt-in Product C1 checkpoint runner."""

from pathlib import Path
import os
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "tools/ci/product_sd1_c1_checkpoint.sh"


class C1CheckpointRunnerContract(unittest.TestCase):
    def test_shell_is_valid(self):
        subprocess.run(["bash", "-n", str(RUNNER)], cwd=ROOT, check=True)

    def test_rejects_no_explicit_opt_in_before_media(self):
        env = dict(os.environ)
        for name in (
            "AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT",
            "AETHERCORE_SD1_C1_CONFIRM",
            "AETHERCORE_SD1_RELAY_CONFIRM",
        ):
            env.pop(name, None)
        proc = subprocess.run(
            ["bash", str(RUNNER), "save"], cwd=ROOT, env=env,
            capture_output=True, text=True,
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("explicit C1+SD1 opt-in", proc.stderr)

    def test_strict_sd1_firmware_not_accepted_as_c1(self):
        source = RUNNER.read_text()
        self.assertIn("PRODUCT_SD1_C1_FIRMWARE_RESULT: status=READY_UNVERIFIED", source)
        self.assertIn("separately verified C1 OpenSBI+Linux firmware is REQUIRED", source)
        self.assertIn('"$(field rootfs_binding "$FW_RESULT")" == NONE', source)
        self.assertIn('"$(field linux_image_sha256 "$FW_RESULT")" == "$(sha "$KERNEL_IMAGE")"', source)

    def test_reuse_is_explicit_and_release_is_not_login_pass(self):
        source = RUNNER.read_text()
        for mode in ("c1-save", "c1-advance", "c1-release"):
            self.assertIn(f'"$SIM" {mode} ', source)
        self.assertIn('"$SIM" advance ', source)
        self.assertIn("AETHERCORE_C1_GO", source)
        self.assertIn("PRODUCT_SD1_C1_ROOTFS_RELEASED", source)
        self.assertIn("not_native_init_pass=1", source)
        self.assertIn("cold_cycles=0", source)

    def test_only_hash_checked_atomic_state_can_resume(self):
        source = RUNNER.read_text()
        self.assertIn('pin_file "$dir/persistent.pre.rtl"', source)
        self.assertIn('pin_file "$dir/persistent.pre.host"', source)
        self.assertIn('"$(head -c8 "$dir/persistent.pre.host")" == AETSDCP1', source)
        self.assertIn('sync -f "$dir/persistent.pre.rtl"', source)
        self.assertIn('mv "$dir/.c1-ready.tmp" "$dir/.c1-ready"', source)
        self.assertIn("C1 replacement must be an ext4 MBR-rootfs image", source)
        self.assertIn('pin_file "$NEW_SD" "$NEW_SD_SHA"', source)


if __name__ == "__main__":
    unittest.main()
