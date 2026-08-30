import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
PAGED = ROOT / "src/main/scala/aethercore/core/v2/TinyPagedCore.scala"
BACKEND = ROOT / "src/main/scala/aethercore/core/v2/TinyMemoryBackend.scala"
FETCH = ROOT / "src/main/scala/aethercore/core/InstructionFetchAdapter.scala"
ARBITER = ROOT / "src/main/scala/aethercore/core/PtwArbiter.scala"
TRANSLATION = ROOT / "src/main/scala/aethercore/core/TranslationUnit.scala"


class V2PtwSfenceOwnershipSourceContract(unittest.TestCase):
    def test_backend_exports_raw_data_ptw_for_parent_owned_pmp(self):
        source = BACKEND.read_text(encoding="utf-8")
        self.assertNotIn("val ptwPmp = Module(new PmpChecker", source)
        self.assertIn("io.pteValid := lsu.io.pteValid", source)
        self.assertIn("lsu.io.pteReady := io.pteReady", source)
        self.assertIn("lsu.io.pteFault := io.pteValid && io.pteFault", source)

    def test_one_parent_side_pmp_checks_the_arbiter_selected_ptw_request(self):
        source = PAGED.read_text(encoding="utf-8")
        self.assertIn("val ptwPmp = Module(new PmpChecker", source)
        self.assertIn("ptwPmp.io.address := ptwArbiter.io.memoryAddress", source)
        self.assertIn("private val ptwPmpFault =", source)
        self.assertIn("ptwArbiter.io.memoryValid && isa.hasPmp.B", source)
        self.assertIn("io.ptw.valid := ptwArbiter.io.memoryValid && !ptwPmpFault", source)
        self.assertIn("ptwArbiter.io.memoryReady := Mux(ptwPmpFault, true.B, io.ptw.ready)", source)
        self.assertNotIn("private val fetchPtwPmpFault", source)

    def test_shared_arbiter_owns_selection_and_exports_routing_fact(self):
        source = ARBITER.read_text(encoding="utf-8")
        self.assertIn("val chooseData = io.dataValid", source)
        self.assertIn("val chooseFetch = !chooseData && io.fetchValid", source)
        self.assertIn("val memoryIsFetch = Output(Bool())", source)
        self.assertIn("io.memoryIsFetch := chooseFetch", source)
        self.assertNotIn("PmpChecker", source)
        self.assertNotIn("PrivilegeMode", source)

    def test_translation_and_fetch_adapter_do_not_own_pmp_policy(self):
        translation = TRANSLATION.read_text(encoding="utf-8")
        fetch = FETCH.read_text(encoding="utf-8")
        self.assertIn("val walker = Module(new PageTableWalker(geometry))", translation)
        self.assertNotIn("PmpChecker", translation)
        self.assertNotIn("PmpChecker", fetch)

    def test_sfence_retirement_is_single_flush_origin_for_i_and_d_translation(self):
        backend = BACKEND.read_text(encoding="utf-8")
        paged = PAGED.read_text(encoding="utf-8")
        translation = TRANSLATION.read_text(encoding="utf-8")
        fetch = FETCH.read_text(encoding="utf-8")

        self.assertIn("private val sfenceAtRetire = retiringSystem &&", backend)
        self.assertIn("!retiring.bits.exception.valid &&", backend)
        self.assertIn("SystemOperationKind.SfenceVma", backend)
        self.assertIn("lsu.io.translationFlush := sfenceAtRetire", backend)
        self.assertIn("io.translationFence := sfenceAtRetire", backend)

        self.assertIn("fetch.io.flush := backend.io.translationFence", paged)
        self.assertIn("rvc.io.kill := frontendKill || backend.io.translationFence", paged)
        self.assertIn("translation.io.flush := io.flush", fetch)
        self.assertIn("val abort = io.kill || io.flush", translation)
        self.assertIn("tlb.io.flush := io.flush", translation)
        self.assertIn("walker.io.kill := abort", translation)


if __name__ == "__main__":
    unittest.main()
