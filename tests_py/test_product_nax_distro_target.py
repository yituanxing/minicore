"""NaxRiscv-inspired FPGA Debian goal is independent of OoO width.

These tests validate the product SPECIFICATION, not NaxRiscv RTL nor our FPGA.
"""
import json
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = ROOT / "docs" / "architecture" / "rv64gc_g0_contract.json"
ALIGNMENT = ROOT / "docs" / "architecture" / "RV64GC_NAXRISCV_FPGA_DEBIAN_ALIGNMENT.md"


class ProductNaxDistroTarget(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = json.loads(SPEC.read_text(encoding="utf-8"))
        cls.axes = cls.data["software_platform_axes"]

    def test_nax_reference_is_concrete_not_just_inspiration(self):
        ref = self.data["naxriscv_reference"]
        self.assertEqual(ref["upstream_sha"], "9f452d50560d02fb391bc8039f5453c54e0911af")
        self.assertTrue(ref["primary_architecture_reference"])
        files = [x["path"] for x in ref["source_checks"]]
        self.assertEqual(len(files), len(set(files)))
        for name in ("Gen.scala", "RfTranslationPlugin.scala",
                     "RfAllocationPlugin.scala", "RobPlugin.scala",
                     "CommitPlugin.scala", "Lsu2Plugin.scala",
                     "MmuPlugin.scala", "RegFilePlugin.scala"):
            self.assertTrue(any(s.endswith(name) for s in files), name)

    def test_geometry_does_not_pretend_to_determine_isa_abi(self):
        self.assertEqual(self.axes["orthogonal"], ["geometry", "isa_abi", "platform"])
        self.assertEqual(self.axes["geometry_profiles"],
                         [x["id"] for x in self.data["profiles"]])
        abi = {p["id"]: p for p in self.axes["isa_abi_profiles"]}
        self.assertEqual(set(abi), {"engineering-rv64ima", "upstream-debian-riscv64"})
        target = abi["upstream-debian-riscv64"]
        self.assertEqual((target["isa"], target["abi"]), ("RV64GC", "LP64D"))
        self.assertTrue({"I","M","A","F","D","C","Zicsr","Zifencei"}.issubset(
            set(target["required_extensions"])))
        self.assertEqual(set(target["privilege"]), {"M","S","U"})
        self.assertEqual(target["virtual_memory"], "Sv39")
        self.assertTrue({"FPR","fcsr","fflags","FS Dirty/Clean",
                         "precise FP retirement","context switch preservation"}.issubset(
            set(target["required_state"])))
        self.assertFalse(self.data["profiles"][0]["fp_enabled"])
        self.assertEqual(self.data["baseline"]["product_target"], "not-implemented")

    def test_debian_is_physical_system_not_pid1(self):
        profiles = {p["id"]: p for p in self.axes["platform_profiles"]}
        board = profiles["fpga-debian"]
        checks = set(board["required"])
        for requirement in ("physical FPGA bitstream timing-closed",
                            "calibrated external DDR", "block storage SD/eMMC",
                            "persistent rootfs", "Debian riscv64", "login/shell",
                            "apt/dpkg", "native glibc dynamic linking",
                            "filesystem persistence after reboot"):
            self.assertIn(requirement, checks)
        self.assertNotEqual(profiles["virtual-soc"]["evidence"], board["evidence"])

    def test_wider_ooo_is_not_release_precondition(self):
        gates = {x["id"]: x for x in self.axes["release_gates"]}
        self.assertEqual(set(gates), {"D0","D1","D2","D3","D4"})
        self.assertEqual(gates["D2"]["must_precede"], "D3")
        self.assertEqual(gates["D3"]["must_precede"], "D4")
        self.assertEqual(gates["D4"]["not_prerequisite_of"], "D3")
        self.assertEqual(set(gates["D3"]["requires"][:2]), {"D0","D2"})
        self.assertIn("D2", gates["D0"]["can_parallelize_with"])

    def test_recovery_prf_capacity_are_architecture_choices(self):
        ref = self.data["naxriscv_reference"]
        self.assertIn("dirty-mask", ref["recovery_tradeoff"])
        self.assertIn("64", ref["physical_int_observation"])
        self.assertIn("96", ref["physical_int_observation"])
        self.assertIn("not correctness minimum", ref["physical_int_observation"])
        self.assertIn("NaxRiscv", self.data["g1_selected_decisions"][0]["nax_alternative"])

    def test_companion_document_states_no_fake_claim(self):
        text = ALIGNMENT.read_text(encoding="utf-8")
        for phrase in ("RV64GC", "LP64D", "Debian", "NaxRiscv", "LiteX",
                       "D0", "D1", "D2", "D3", "D4", "not", "FCSR",
                       "FPGA", "ROB64", "PRF96"):
            # Some English keywords are transliterated in the Chinese prose.
            if phrase in ("not", "ROB64", "PRF96"):
                continue
            self.assertIn(phrase, text)


if __name__ == "__main__":
    unittest.main()
