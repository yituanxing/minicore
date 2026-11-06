"""Source contracts for the isolated new-kernel SD1 Product RTL short gate."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools/ci/product_sd1_gpio_cs_diag_short.sh'
WORKFLOW = ROOT / '.github/workflows/product-sd1-gpio-cs-diag-short.yml'


class DiagnosticShortGateContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = SCRIPT.read_text()
        cls.workflow = WORKFLOW.read_text()

    def test_original_media_and_patch_sha_before_guest_cycles(self):
        script = self.script
        self.assertIn('product_sd1_official_seed.py verify', script)
        self.assertIn('AETHERCORE_SD1_GPIO_CS_RTL_DIAG:-0', script)
        self.assertIn('SD1_FULL_DEBIAN_SIGNED_RELAY', script)
        self.assertIn('ff408c203b461d504f48152840698b077f8ea948a07ad5235f4afb8436282638', script)
        self.assertLess(script.index('product_sd1_official_seed.py verify'), script.index('"$sim" save'))
        self.assertIn('READY_FOR_SHORT_RTL_ONLY', script)
        self.assertIn('SD1_GPIO_CS_DIAGNOSTIC_CHECKPOINT_NOT_DEBIAN_PASS', script)

    def test_saved_state_is_independent_and_exact(self):
        script = self.script
        self.assertIn('product-sd1-gpio-cs-rtl-checkpoint', script)
        self.assertIn('[[ ! -e "$out" ]]', script)
        self.assertIn('"$sim" save', script)
        self.assertIn('"$sim" restore', script)
        self.assertIn('"$sim" advance', script)
        self.assertIn('cmp -s "$stage/persistent.continuous.rtl"', script)
        self.assertIn('cmp -s "$stage/persistent.continuous.host"', script)
        self.assertIn('"$((next-start_cycles))" -le 20000000', script)
        self.assertIn('save_or_advance_elapsed_seconds=', script)
        self.assertIn('phase_timing_sha256=', script)
        self.assertNotIn('product_sd1_artifact_relay.sh', script)

    def test_manual_workflow_has_two_cache_gates_and_12m_budget(self):
        workflow = self.workflow
        self.assertIn('types: [labeled]', workflow)
        self.assertNotIn('workflow_dispatch:', workflow)
        self.assertIn("github.event.pull_request.number == 379", workflow)
        self.assertIn("steps.diagnostic.outputs.cache-hit", workflow)
        self.assertIn("steps.seed.outputs.cache-hit", workflow)
        self.assertIn('product_checkpoint_sd1_short.sh build', workflow)
        self.assertIn('product_sd1_gpio_cs_diag_short.sh run', workflow)
        self.assertNotIn('product_checkpoint_sd1_short.sh run', workflow)
        self.assertNotIn('AETHERCORE_SD1_LONGRUN_CONFIRM: ', workflow)
        self.assertIn('retention-days: 90', workflow)
        self.assertIn('include-hidden-files: true', workflow)
        self.assertIn('phase-timing.txt', workflow)
        self.assertIn('product-sd1-savable-portable-v1-', workflow)
        self.assertIn('steps.portable-abi.outputs.signature', workflow)
        self.assertNotIn('steps.native-abi.outputs.signature', workflow)
        self.assertIn('steps.model-cache.outputs.cache-hit', workflow)

    def test_immutable_cross_ci_12m_to_32m_relay(self):
        workflow = self.workflow
        self.assertIn('product-sd1-gpio-cs-diag-advance-32m', workflow)
        self.assertIn('run-id: 42242315003', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-12m-42242315003', workflow)
        self.assertIn('github-token: ${{ github.token }}', workflow)
        self.assertIn('actions: read', workflow)
        self.assertIn('steps.model-cache.outputs.cache-hit', workflow)
        self.assertIn('expected exactly one imported 12M manifest', workflow)
        self.assertIn("if: github.event.label.name == 'product-sd1-gpio-cs-diag-short'", workflow)
        self.assertIn('cached simulator bytes differ from its own sealed identity', workflow)
        self.assertIn('cached model bytes differ from 12M snapshot model', workflow)
        self.assertIn('cached model source key differs from 12M checkpoint', workflow)
        self.assertIn('product_sd1_gpio_cs_diag_short.sh advance', workflow)
        self.assertIn('32000000', workflow)
        self.assertIn('product_sd1_gpio_cs_boot_profile.py', workflow)
        self.assertIn('"$source/phase-timing.txt"', workflow)
        self.assertLess(workflow.index('Retain immutable patched 32M full Product RTL checkpoint'),
                        workflow.index('Analyze persisted 12M to 32M Linux boot profile'))
        script = self.script
        self.assertIn('source_model_key', script)
        self.assertIn('duplicate source checkpoint manifest fields', script)
        self.assertIn('source contains symlinks', script)


if __name__ == '__main__':
    unittest.main()
