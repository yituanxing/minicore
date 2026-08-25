import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
PAGED = ROOT / "src/main/scala/aethercore/core/v2/TinyPagedCore.scala"
BACKEND = ROOT / "src/main/scala/aethercore/core/v2/TinyMemoryBackend.scala"
FETCH = ROOT / "src/main/scala/aethercore/core/InstructionFetchAdapter.scala"
ARBITER = ROOT / "src/main/scala/aethercore/core/PtwArbiter.scala"
TRANSLATION = ROOT / "src/main/scala/aethercore/core/TranslationUnit.scala"


class V2PtwOwnershipSourceContract(unittest.TestCase):
    def test_data_ptw_is_protected_before_backend_export(self):
        source = BACKEND.read_text(encoding="utf-8")

        self.assertIn("val ptwPmp = Module(new PmpChecker", source)
        self.assertIn("ptwPmp.io.privilege := PrivilegeMode.Supervisor.U", source)
        self.assertIn("private val ptwPmpFault = lsu.io.pteValid", source)
        self.assertIn("io.pteValid := lsu.io.pteValid && !ptwPmpFault", source)
        self.assertIn("lsu.io.pteReady := Mux(ptwPmpFault, true.B, io.pteReady)", source)
        self.assertIn("lsu.io.pteFault := ptwPmpFault ||", source)

    def test_fetch_ptw_is_the_only_parent_side_pmp_check(self):
        source = PAGED.read_text(encoding="utf-8")

        self.assertIn("private val selectedFetchPtw =", source)
        self.assertIn("ptwArbiter.io.memoryValid && !ptwArbiter.io.dataValid", source)
        self.assertIn("private val fetchPtwPmpFault = selectedFetchPtw", source)
        self.assertIn("io.ptw.valid := ptwArbiter.io.memoryValid && !fetchPtwPmpFault", source)
        self.assertIn("ptwArbiter.io.memoryReady := Mux(fetchPtwPmpFault, true.B, io.ptw.ready)", source)
        self.assertNotIn("private val ptwPmpFault = ptwArbiter.io.memoryValid", source)

    def test_shared_arbiter_keeps_data_priority_without_owning_pmp(self):
        source = ARBITER.read_text(encoding="utf-8")

        self.assertIn("val chooseData = io.dataValid", source)
        self.assertIn("val chooseFetch = !chooseData && io.fetchValid", source)
        self.assertNotIn("PmpChecker", source)
        self.assertNotIn("PrivilegeMode", source)

    def test_translation_and_fetch_adapter_do_not_own_pmp_policy(self):
        translation = TRANSLATION.read_text(encoding="utf-8")
        fetch = FETCH.read_text(encoding="utf-8")

        self.assertIn("class TranslationUnit(", translation)
        self.assertIn("val walker = Module(new PageTableWalker(geometry))", translation)
        self.assertNotIn("PmpChecker", translation)
        self.assertNotIn("PmpChecker", fetch)


if __name__ == "__main__":
    unittest.main()
