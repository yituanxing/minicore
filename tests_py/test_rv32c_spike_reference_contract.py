from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Rv32cSpikeReferenceContract(unittest.TestCase):
    def test_reference_is_pinned_and_rebuilt_from_scratch(self):
        helper = (ROOT / "tools/ensure_rv32c_spike_reference.sh").read_text()
        self.assertIn('REVISION="530af85d83781a3dae31a4ace84a573ec255fefa"', helper)
        self.assertIn('EXPECTED_SHA256="85b02befce3e98383080c28f33f18bb4d08282cf97e2e6b7f2f7e334a223c85f"', helper)
        self.assertIn('rm -rf "$WORK_DIR"', helper)
        self.assertIn('SOURCE_DATE_EPOCH=', helper)
        self.assertIn('-ffile-prefix-map=', helper)
        self.assertIn('-fdebug-prefix-map=', helper)
        self.assertIn('-Wl,--build-id=none', helper)
        self.assertIn('libdisasm.a', helper)
        self.assertIn('dtc_runtime_dependency=none', helper)

    def test_reference_exports_existing_small_difftest_abi(self):
        helper = (ROOT / "tools/ensure_rv32c_spike_reference.sh").read_text()
        shim = (ROOT / "tools/spike_rv32_reference_shim.cpp").read_text()
        for symbol in (
            "difftest_init",
            "difftest_memcpy",
            "difftest_regcpy",
            "difftest_exec",
            "new_space",
            "add_mmio_map",
        ):
            self.assertIn(symbol, helper)
            self.assertIn(symbol, shim)
        self.assertIn('"RV32IMC_Zicsr"', shim)
        self.assertIn("gProcessor->step", shim)
        self.assertIn("set_pmp_num(0)", shim)
        self.assertIn("callback != nullptr", shim)

    def test_provider_has_a_standalone_compressed_semantic_smoke(self):
        helper = (ROOT / "tools/ensure_rv32c_spike_reference.sh").read_text()
        self.assertIn("0x41, 0x11", helper)
        self.assertIn("initial[2] = 0x80100000", helper)
        self.assertIn("initial[32] = 0x80000000", helper)
        self.assertIn("result[32] == 0x80000002", helper)
        self.assertIn("result[2] == 0x800FFFF0", helper)
        self.assertIn("semantic_smoke=PASS", helper)

    def test_dedicated_workflow_rebuilds_reference_twice(self):
        workflow = (ROOT / ".github/workflows/rv32c-spike-reference.yml").read_text()
        self.assertIn("rv32c-spike-reference-a", workflow)
        self.assertIn("rv32c-spike-reference-b", workflow)
        self.assertGreaterEqual(workflow.count("ensure_rv32c_spike_reference.sh"), 2)
        self.assertIn('test "$SPIKE_SHA_A" = "$SPIKE_SHA_B"', workflow)
        self.assertIn("85b02befce3e98383080c28f33f18bb4d08282cf97e2e6b7f2f7e334a223c85f", workflow)
        self.assertNotIn("CoreMark", workflow)
        self.assertNotIn("Makefile.rv32imc-coremark", workflow)


if __name__ == "__main__":
    unittest.main()
