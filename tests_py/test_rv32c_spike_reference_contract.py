from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = "22dd74006570af19495c6b5449eec908d246c0c6d700f7eabda16001a0ca62df"
REVISION = "530af85d83781a3dae31a4ace84a573ec255fefa"


class Rv32cSpikeReferenceContract(unittest.TestCase):
    def test_cold_builder_is_pinned_reproducible_and_fail_closed(self):
        helper = (ROOT / "tools/ensure_rv32c_spike_reference.sh").read_text()
        self.assertIn(f'REVISION="{REVISION}"', helper)
        self.assertIn(f'EXPECTED_SHA256="{EXPECTED}"', helper)
        self.assertIn('rm -rf "$WORK_DIR"', helper)
        self.assertIn('SOURCE_DATE_EPOCH=', helper)
        self.assertIn('-ffile-prefix-map=', helper)
        self.assertIn('-fdebug-prefix-map=', helper)
        self.assertIn('-Wl,--build-id=none', helper)
        self.assertIn('libdisasm.a', helper)
        self.assertIn('dtc_runtime_dependency=none', helper)
        self.assertIn('probe_rv32_reference_abi.py', helper)

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

    def test_reference_keeps_multiple_passive_mmio_maps(self):
        shim = (ROOT / "tools/spike_rv32_reference_shim.cpp").read_text()
        probe = (ROOT / "tools/probe_rv32_reference_abi.py").read_text()
        self.assertIn("std::vector<MmioMapping> mmio_", shim)
        self.assertIn("findMmio", shim)
        self.assertIn("rangesOverlap", shim)
        self.assertIn("overlapping passive MMIO mapping", shim)
        self.assertIn("0x10000000", probe)
        self.assertIn("0x0200B000", probe)
        self.assertIn("passive_mmio_maps=2", probe)
        self.assertIn("passive_mmio_independent=PASS", probe)

    def test_regcpy_injects_canonical_rv32_register_values(self):
        shim = (ROOT / "tools/spike_rv32_reference_shim.cpp").read_text()
        probe = (ROOT / "tools/probe_rv32_reference_abi.py").read_text()
        self.assertIn("canonicalRv32Register", shim)
        self.assertIn("static_cast<std::int32_t>(value)", shim)
        self.assertIn("canonicalRv32Register(state->gpr[index])", shim)
        self.assertIn("initial.gpr[19] = 0xFFFFFFFF", probe)
        self.assertIn("mixed_provenance_bne=fallthrough", probe)

    def test_provider_has_standalone_compressed_and_abi_smokes(self):
        helper = (ROOT / "tools/ensure_rv32c_spike_reference.sh").read_text()
        self.assertIn("0x41, 0x11", helper)
        self.assertIn("result[32] == 0x80000002", helper)
        self.assertIn("result[2] == 0x800FFFF0", helper)
        self.assertIn("semantic_smoke=PASS", helper)
        self.assertIn("abi_smoke=PASS", helper)

    def test_consumer_resolver_validates_exact_cache_and_delegates_cold_build(self):
        resolver = (ROOT / "tools/resolve_rv32c_spike_reference.sh").read_text()
        self.assertIn(f'EXPECTED_SHA256="{EXPECTED}"', resolver)
        self.assertIn("validate_reference", resolver)
        self.assertIn("aethercore_rv32c_spike_cache=hit", resolver)
        self.assertIn("flock", resolver)
        self.assertIn("mktemp -d", resolver)
        self.assertIn("ensure_rv32c_spike_reference.sh", resolver)
        self.assertIn("cache_format=rv32imc-spike-reference-v2", resolver)

    def test_dedicated_workflow_rebuilds_twice_and_never_uses_cache_resolver(self):
        workflow = (ROOT / ".github/workflows/rv32c-spike-reference.yml").read_text()
        self.assertIn("rv32c-spike-reference-a", workflow)
        self.assertIn("rv32c-spike-reference-b", workflow)
        self.assertGreaterEqual(workflow.count("ensure_rv32c_spike_reference.sh"), 2)
        self.assertNotIn('bash tools/resolve_rv32c_spike_reference.sh', workflow)
        self.assertIn('test "$SPIKE_SHA_A" = "$SPIKE_SHA_B"', workflow)
        self.assertIn(EXPECTED, workflow)
        self.assertIn("mixed_provenance_bne=fallthrough", workflow)
        self.assertIn("passive_mmio_independent=PASS", workflow)
        self.assertNotIn("CoreMark", workflow)


if __name__ == "__main__":
    unittest.main()
