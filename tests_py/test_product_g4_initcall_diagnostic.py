"""Opt-in G4 diagnosis must preserve the frozen normal Linux PID1 payload."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-g4-linux-pid1.yml"
PAYLOAD = ROOT / "tools/ci/rv64_minimal_init_payload_build.sh"


class ProductG4InitcallDiagnostic(unittest.TestCase):
    def test_only_explicit_diagnostic_uses_initcall_bootargs(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("diagnostic_initcalls:", workflow)
        self.assertIn("type: boolean", workflow)
        self.assertIn("default: false", workflow)
        self.assertIn("if [[ '${{ inputs.diagnostic_initcalls }}' == 'true' ]]", workflow)
        self.assertIn("github.event.label.name == 'product-g4-longrun'", workflow)
        self.assertNotIn("github.event.pull_request.draft ||", workflow)
        # initcall_debug uses KERN_DEBUG; the console must expose level 7
        # rather than silently hiding the calls we need to diagnose.
        self.assertIn('RV64_PAYLOAD_BOOTARGS="${RV64_LINUX_BOOTARGS} rdinit=/init initcall_debug loglevel=8"', workflow)
        self.assertIn("PRODUCT_G4_INITCALL_DIAGNOSTIC_ONLY:", workflow)
        self.assertIn("G4 diagnostic only: DTB bootargs include initcall_debug loglevel=8", workflow)

        # The normal G4 recipe is unchanged; the existing payload builder
        # consumes RV64_PAYLOAD_BOOTARGS only in a diagnostic workflow run.
        script = PAYLOAD.read_text()
        self.assertIn('BOOTARGS="${RV64_PAYLOAD_BOOTARGS:-${RV64_LINUX_BOOTARGS} rdinit=/init}"', script)
        self.assertIn('echo "bootargs=${BOOTARGS}"', script)
        self.assertNotIn("loglevel=8", script)

    def test_timeout_always_symbols_pcs_without_relaxing_pid1_gate(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("max_cycles=1200000000", workflow)
        self.assertIn("max_cycles=1800000000", workflow)
        self.assertIn('MAX_CYCLES="$max_cycles"', workflow)
        self.assertIn("PRODUCT_G4_CYCLE_BUDGET:", workflow)
        self.assertIn("MILESTONE='RV64 USER UART IRQ OK'", workflow)
        self.assertIn("MIN_INTERRUPTS=1 MIN_STIP=1 MIN_SEIP=1", workflow)
        self.assertIn("PRODUCT_G4_LINUX_PID1_DIAGNOSTIC_PASS:", workflow)
        self.assertIn("NOT final image identity/signoff", workflow)
        self.assertIn("PRODUCT_G4_LINUX_PID1_PASS:", workflow)
        self.assertIn("- name: Symbolize the last kernel and SBI PCs on all outcomes\n        if: always()", workflow)
        self.assertIn("kernel-pc-symbolization.log", workflow)
        self.assertIn("${AETHERCORE_RV64_LINUX_CROSS_COMPILE:-}addr2line", workflow)
        self.assertIn("owner=linux", workflow)
        self.assertIn("owner=opensbi", workflow)
        self.assertIn("grep '^L32_SIM_PROGRESS '", workflow)
        self.assertIn("L32_(OPENSBI_TIMEOUT|RUNTIME_MILESTONE_PASS", workflow)
        self.assertIn("- name: Preserve Product Linux PID1 and interrupt evidence", workflow)


if __name__ == "__main__":
    unittest.main()
