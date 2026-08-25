import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
V2 = ROOT / "src/main/scala/aethercore/core/v2"
BRIDGE = V2 / "TinySemanticDecode.scala"
FOUNDATION = V2 / "FoundationTypes.scala"

# Frontend shells may legitimately manipulate fetched instruction bits before
# architectural decode. TinySemanticDecode is the one deliberate legacy-decode
# adapter. Every other v2 file is downstream of the semantic boundary.
FRONTEND_OR_BRIDGE = {
    "TinyBareCore.scala",
    "TinyPagedCore.scala",
    "TinySemanticDecode.scala",
}

LEGACY_PIPELINE_SYMBOLS = (
    "ControlSignals",
    "OpASel",
    "OpBSel",
    "WbSel",
    "ImmSel",
)


class V2SemanticBoundaryContractTest(unittest.TestCase):
    def test_foundation_keeps_architectural_decode_separate_from_backend_uop(self) -> None:
        text = FOUNDATION.read_text()
        self.assertIn("class DecodedInstruction", text)
        self.assertIn("class BackendUop", text)
        self.assertRegex(text, r"class BackendUop[\s\S]*val decoded = new DecodedInstruction")

    def test_tiny_semantic_decode_is_the_only_legacy_decoder_adapter(self) -> None:
        bridge = BRIDGE.read_text()
        self.assertRegex(bridge, r"new\s+Decoder\s*\(isa\)")
        self.assertIn("val dispatch = Output(new RobDispatch(xlen))", bridge)

        offenders = []
        for path in sorted(V2.glob("*.scala")):
            if path.name == BRIDGE.name:
                continue
            text = path.read_text()
            if re.search(r"\bnew\s+Decoder\s*\(", text) or "aethercore.core.{Decoder" in text:
                offenders.append(path.name)
        self.assertEqual(offenders, [], f"legacy Decoder escaped semantic bridge: {offenders}")

    def test_legacy_pipeline_selectors_do_not_escape_into_v2_backend(self) -> None:
        offenders = []
        for path in sorted(V2.glob("*.scala")):
            if path.name in FRONTEND_OR_BRIDGE:
                continue
            text = path.read_text()
            leaked = [symbol for symbol in LEGACY_PIPELINE_SYMBOLS if re.search(rf"\b{symbol}\b", text)]
            if leaked:
                offenders.append((path.name, leaked))
        self.assertEqual(offenders, [], f"legacy pipeline decode controls leaked into backend: {offenders}")

    def test_backend_does_not_redecode_decoded_instruction_bits(self) -> None:
        # Whole rawInst/inst values may cross the backend as architectural trace
        # or illegal-instruction trap provenance. What is forbidden is slicing
        # or masking them to reconstruct opcode/funct semantics after decode.
        offenders = []
        bit_slice = re.compile(r"decoded\.(?:inst|rawInst)\s*\(")
        bit_mask = re.compile(r"decoded\.(?:inst|rawInst)\s*(?:&|\||\^)\s*")
        for path in sorted(V2.glob("*.scala")):
            if path.name in FRONTEND_OR_BRIDGE:
                continue
            text = path.read_text()
            if bit_slice.search(text) or bit_mask.search(text):
                offenders.append(path.name)
        self.assertEqual(offenders, [], f"backend re-decodes raw instruction bits: {offenders}")


if __name__ == "__main__":
    unittest.main()
