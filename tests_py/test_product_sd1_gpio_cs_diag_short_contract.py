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
        self.assertIn('product-sd1-gpio-cs-diag-advance-52m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-72m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-92m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-112m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-132m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-152m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-172m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-192m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-212m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-232m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-252m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-272m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-292m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-312m', workflow)
        self.assertIn('product-sd1-gpio-cs-diag-advance-332m', workflow)
        self.assertIn("'42242315003'", workflow)
        self.assertIn("'42245421838'", workflow)
        self.assertIn("'42246225589'", workflow)
        self.assertIn("'42246826509'", workflow)
        self.assertIn("'42324435535'", workflow)
        self.assertIn("'42324761002'", workflow)
        self.assertIn("'42324995836'", workflow)
        self.assertIn("'42325261377'", workflow)
        self.assertIn("'42325812471'", workflow)
        self.assertIn("'42326027714'", workflow)
        self.assertIn("'42326262239'", workflow)
        self.assertIn("'42326535567'", workflow)
        self.assertIn("'42326763349'", workflow)
        self.assertIn("'42327058494'", workflow)
        self.assertIn("'42327660700'", workflow)
        self.assertIn("'42327950846'", workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-12m-42242315003', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-32m-42245421838', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-52m-42246225589', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-72m-42246826509', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-92m-42324435535', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-112m-42324761002', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-132m-42324995836', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-152m-42325261377', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-172m-42325812471', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-192m-42326027714', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-212m-42326262239', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-232m-42326535567', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-252m-42326763349', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-272m-42327058494', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-292m-42327660700', workflow)
        self.assertIn('product-sd1-gpio-cs-diagnostic-312m-42327950846', workflow)
        self.assertIn('github-token: ${{ github.token }}', workflow)
        self.assertIn('actions: read', workflow)
        self.assertIn('steps.model-cache.outputs.cache-hit', workflow)
        self.assertIn('expected exactly one imported checkpoint manifest', workflow)
        self.assertIn("if: github.event.label.name == 'product-sd1-gpio-cs-diag-short'", workflow)
        self.assertIn('cached simulator bytes differ from its own sealed identity', workflow)
        self.assertIn('cached model bytes differ from 12M snapshot model', workflow)
        self.assertIn('cached model source key differs from 12M checkpoint', workflow)
        self.assertIn('product_sd1_gpio_cs_diag_short.sh advance', workflow)
        self.assertIn("'32000000'", workflow)
        self.assertIn("'52000000'", workflow)
        self.assertIn("'72000000'", workflow)
        self.assertIn("'92000000'", workflow)
        self.assertIn("'112000000'", workflow)
        self.assertIn("'132000000'", workflow)
        self.assertIn("'152000000'", workflow)
        self.assertIn("'172000000'", workflow)
        self.assertIn("'192000000'", workflow)
        self.assertIn("'212000000'", workflow)
        self.assertIn("'232000000'", workflow)
        self.assertIn("'252000000'", workflow)
        self.assertIn("'272000000'", workflow)
        self.assertIn("'292000000'", workflow)
        self.assertIn("'312000000'", workflow)
        self.assertIn("'332000000'", workflow)
        self.assertIn('product_sd1_gpio_cs_boot_profile.py', workflow)
        self.assertIn('"$source/phase-timing.txt"', workflow)
        self.assertLess(workflow.index('Retain immutable patched full Product RTL checkpoint'),
                        workflow.index('Analyze persisted Linux boot phase profile'))
        script = self.script
        self.assertIn('source_model_key', script)
        self.assertIn('duplicate source checkpoint manifest fields', script)
        self.assertIn('source contains symlinks', script)


if __name__ == '__main__':
    unittest.main()
