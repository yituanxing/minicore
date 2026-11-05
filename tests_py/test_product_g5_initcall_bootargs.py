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

    def test_g5_dual_toolchain_gate_provisions_reference_despite_candidate_env(self):
        setup = self.workflow.split(
            '- name: Provision selected pinned Verilator and frozen GCC', 1
        )[1].split('- name: Save selected Verilator toolchain', 1)[0]
        # The job selects 5.054 for short A/B, but the first invocation of
        # ensure_verilator_5_024.sh dynamically reads that same environment.
        # An explicit 5.024 override is required to preserve a real A/B.
        self.assertIn(
            'AETHERCORE_VERILATOR_VERSION=5.024 bash tools/ensure_verilator_5_024.sh',
            setup,
        )
        self.assertIn('bash tools/ensure_verilator_5_054.sh', setup)


if __name__ == "__main__":
    unittest.main()
