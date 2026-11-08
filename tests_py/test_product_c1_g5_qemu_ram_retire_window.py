"""Fail-closed static contract for unilateral true-G5 retirement window.

These guards do NOT attest RTL execution. The isolated workflow must build
genuine Verilator, authenticate the existing QEMU 256MiB artifact, and archive
the exact retire stream / failure point. Only first retire has QEMU oracle.
"""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / "sim/product_c1_g5_qemu_ram_retire_window_main.cpp"
FIRST = ROOT / "sim/product_c1_g5_qemu_ram_first_retire_main.cpp"
WORKFLOW = ROOT / ".github/workflows/product-c1-g5-real-qemu-ram-retire-window.yml"


class G5RealQemuRamWindowContract(unittest.TestCase):
    def test_source_identity_kept_and_original_first_retirement_unchanged(self):
        host = HOST.read_text()
        first = FIRST.read_text()
        self.assertIn("#define AETHERCORE_QEMU_RAM_TRACE_EMBED", host)
        self.assertIn("#ifndef AETHERCORE_QEMU_RAM_TRACE_EMBED", first)
        self.assertIn("REAL_QEMU_RAM_FIRST_RETIRE_PARTIAL_ARCH_PASS", first)
        self.assertIn('"product_c1_g5_qemu_ram_first_retire_main.cpp"', host)
        for oracle in ("kFirstOpcode", "kGuestPc", "kPhysicalPc", "kSpAfter",
                       "firstWalk.root", "firstWalk.leaf", "kCapturedGprs"):
            self.assertIn(oracle, host)

    def test_separate_ram_and_static_source_sanity_not_fake_qemu_difftest(self):
        host = HOST.read_text()
        self.assertIn("Memory original;", host)
        self.assertIn("Memory live;", host)
        self.assertIn("live.restore(original.snapshot());", host)
        self.assertIn("original.fingerprint()", host)
        self.assertIn("originalInstructionPa(original, top.io_commit_pc)", host)
        self.assertIn("original.readInstruction(*pa, opBytes)", host)
        self.assertIn("if (retired == 0) step(top, ctx, live, firstWalk);", host)
        self.assertIn("l32sim::step(top, ctx, live, transport, false, 0)", host)
        self.assertIn("retirements=", host)
        self.assertIn("remaining_qemu_oracle_absent=1", host)
        self.assertIn("not_difftest=1 not_sd1=1", host)
        self.assertIn("DUT_ONLY_STOP_FIRST_EXCEPTION_OR_INTERRUPT", host)
        self.assertIn("DUT_ONLY_STOP_UNRESTORED_DEVICE", host)
        self.assertIn("FIRST_SOURCE_STATIC_OPCODE_MISMATCH", host)
        self.assertIn("CSR_UNVERIFIED", host)
        self.assertNotIn("QEMU_DIFFTEST_PASS", host)

    def test_bounded_and_complete_commit_fields(self):
        host = HOST.read_text()
        self.assertIn("count > 256", host)
        for field in ("rawInst", "instBytes", "rdWrite", "rdData",
                      "memValid", "memWrite", "memAddr", "memWdata",
                      "memWmask", "exceptionCause", "exceptionValue",
                      "interruptCause"):
            self.assertIn("top.io_commit_" + field, host)

    def test_fully_sealed_source_and_genuine_build_workflow(self):
        workflow = WORKFLOW.read_text()
        for needle in (
            "42489471408", "13621196662", "source-run.json",
            "source-jobs.json", "source-artifact.json",
            "2d7be8a7f4440868961c82039fb33be7dc4808655130b6db13de4f5c3e499afb",
            "42129f32725e43c1db5bb38b400bb7d593bd92936271d0250e0d5273547810f2",
            "qemu_ram_first_retire_source.py", "qemu_captured_gprs.h",
            "AetherCoreProductDebianWarmSeedRV64SimTop", "verilator --cc --exe --build",
            "product_c1_g5_qemu_ram_retire_window_main.cpp", "100000 64",
            "G5_REAL_QEMU_RAM_DUT_ONLY_RETIRE_WINDOW_OBSERVED_NOT_DIFFTEST",
            "if: always()", "NOT DiffTest",
        ):
            self.assertIn(needle, workflow)
        self.assertNotIn("design/rv64gc-product-architecture-20261103", workflow)
        self.assertNotIn("ram-0x80000000-0x10000000.bin\n          path:", workflow)


if __name__ == "__main__":
    unittest.main()
