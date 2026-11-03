"""Fail early when fast/baseline firmware paths or identity contracts regress."""
import pathlib
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class Rv64IsaFastBuildContract(unittest.TestCase):
    def test_bash_script_syntax(self):
        for name in ("rv64_minimal_initramfs_build.sh", "rv64_minimal_init_payload_build.sh"):
            subprocess.run(["bash", "-n", str(ROOT / "tools/ci" / name)], check=True)

    def test_explicit_paths_match_ci_and_do_not_use_nested_substitution(self):
        initramfs = (ROOT / "tools/ci/rv64_minimal_initramfs_build.sh").read_text()
        payload = (ROOT / "tools/ci/rv64_minimal_init_payload_build.sh").read_text()
        workflow = (ROOT / ".github/workflows/v2-soc-virtual-fpga-linux-pid1.yml").read_text()
        for expected in ("build/rv64-linux-initramfs", "build/rv64-linux-isa-fast-initramfs", "build/rv64-linux-isa-fast-v2-initramfs"):
            self.assertIn(expected, initramfs)
            self.assertIn(expected, payload)
        for expected in ("build/rv64-minimal-init-boot", "build/rv64-isa-fast-boot", "build/rv64-isa-fast-v2-boot"):
            self.assertIn(expected, payload)
            self.assertIn(expected, workflow)
        self.assertNotIn(']] && echo ', payload)
        self.assertNotIn(']] && echo ', initramfs)
        # Baseline retains the qualified early-console bootargs; v2 deliberately
        # overrides them with a non-early UART console.
        self.assertIn('BOOTARGS="${RV64_LINUX_BOOTARGS} rdinit=/init"', payload)
        self.assertIn('BOOTARGS="console=ttyS0,115200 rdinit=/init quiet loglevel=0"', payload)

    def test_baseline_and_fast_profiles_are_independent(self):
        for path in ("rv64_minimal_initramfs_build.sh", "rv64_minimal_init_payload_build.sh"):
            content = (ROOT / "tools/ci" / path).read_text()
            self.assertIn('RV64_INIT_PROFILE="${RV64_INIT_PROFILE:-baseline}"', content)
            self.assertIn('case "${RV64_INIT_PROFILE}" in baseline|isa-fast|isa-fast-v2)', content)


if __name__ == "__main__":
    unittest.main()
