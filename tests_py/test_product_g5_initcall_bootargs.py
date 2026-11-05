"""Guard an opt-in, provenance-bound late-initcall diagnostic Debian boot."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ProductG5InitcallBootargs(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.payload = (ROOT / "tools/ci/rv64_debian_payload_build.sh").read_text()
        cls.workflow = (
            ROOT / ".github/workflows/product-g5-debian-rv64gc.yml"
        ).read_text()

    def test_kernel_initcall_diagnostics_are_explicitly_opt_in(self):
        self.assertIn('case "${RV64_DEBIAN_INITCALL_TRACE:-0}" in', self.payload)
        self.assertIn('initcall_debug loglevel=8', self.payload)
        self.assertIn('RV64_DEBIAN_INITCALL_TRACE must be 0 or 1', self.payload)

    def test_both_embedded_and_sd_bootargs_preserve_root_selection(self):
        self.assertIn(
            'RV64_PAYLOAD_BOOTARGS="${RV64_PAYLOAD_BOOTARGS} initcall_debug loglevel=8"',
            self.payload,
        )
        self.assertIn(
            'RV64_PAYLOAD_BOOTARGS="${RV64_LINUX_BOOTARGS} rdinit=/init initcall_debug loglevel=8"',
            self.payload,
        )

    def test_full_run_records_and_checks_diagnostic_bootargs(self):
        self.assertIn(
            "github.event.label.name == 'product-g5-longrun' || inputs.run_mode == 'full'",
            self.workflow,
        )
        self.assertIn(
            "grep -Fq 'initcall_debug loglevel=8' build/rv64-debian-boot/result.txt",
            self.workflow,
        )


if __name__ == "__main__":
    unittest.main()
