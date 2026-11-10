import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
V2 = ROOT / "src/main/scala/aethercore/core/v2"
SEMANTIC = ROOT / "src/main/scala/aethercore/core/semantic"


class V2DecodeBoundaryContract(unittest.TestCase):
    """Freeze the semantic boundary before later scheduler/LSU/frontend work."""

    def test_tiny_semantic_decode_is_the_only_v2_decoder_bridge(self):
        decoder_users = []
        for path in sorted(V2.glob("*.scala")):
            text = path.read_text(encoding="utf-8")
            if re.search(r"\bnew\s+Decoder\s*\(", text):
                decoder_users.append(path.name)

        self.assertEqual(
            decoder_users,
            [],
            "v2 must only use the shared SemanticDecode implementation",
        )
        source = (SEMANTIC / "SemanticDecode.scala").read_text(encoding="utf-8")
        self.assertIn("val decoder = Module(new Decoder(isa))", source)
        self.assertEqual(source.count("new Decoder(isa)"), 1)

    def test_backend_never_redecodes_retained_instruction_bits(self):
        # Keeping canonical/raw encodings in DecodedInstruction is intentional:
        # CommitTrace and precise illegal-instruction trap values need them.
        # What is forbidden after TinySemanticDecode is extracting opcode/funct
        # fields from those retained bits to rediscover architectural semantics.
        violations = []
        bit_redecode = re.compile(r"\b(?:decoded|\.decoded)\.(?:inst|rawInst)\s*\(")
        named_redecode = re.compile(
            r"\b(?:opcode|funct3|funct5|funct6|funct7)\b[^\n=]*="
            r"[^\n]*(?:decoded|\.decoded)\.(?:inst|rawInst)"
        )

        for path in sorted(V2.glob("*.scala")):
            if path.name == "TinySemanticDecode.scala":
                continue
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if bit_redecode.search(line) or named_redecode.search(line):
                    violations.append(f"{path.name}:{number}: {line.strip()}")

        self.assertEqual(
            violations,
            [],
            "backend must consume typed v2 semantic fields, not re-decode inst/rawInst:\n"
            + "\n".join(violations),
        )

    def test_canonical_v2_semantic_types_remain_explicit(self):
        foundation = (V2 / "FoundationTypes.scala").read_text(encoding="utf-8")
        bridge = (V2 / "TinySemanticDecode.scala").read_text(encoding="utf-8")

        shared = (SEMANTIC / "SemanticTypes.scala").read_text(encoding="utf-8")
        implementation = (SEMANTIC / "SemanticDecode.scala").read_text(encoding="utf-8")
        compatibility = (V2 / "package.scala").read_text(encoding="utf-8")
        self.assertIn("class DecodedInstruction", shared)
        self.assertNotIn("class DecodedInstruction", foundation)
        self.assertIn("class BackendUop", foundation)
        self.assertIn("val decoded = new DecodedInstruction", foundation)
        self.assertIn("type DecodedInstruction = aethercore.core.semantic.DecodedInstruction", compatibility)
        self.assertIn("new SemanticDecode(isa)", bridge)
        self.assertIn("io.dispatch.decoded := semantic.io.decoded", bridge)
        self.assertNotIn("RobDispatch", implementation)
        self.assertNotIn("ExecutionClass", implementation)


if __name__ == "__main__":
    unittest.main()
