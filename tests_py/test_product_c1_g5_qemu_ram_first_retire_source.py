"""Static guards for independently sealed real QEMU DDR / partial seed P1.

Actual full source SHA, Chisel compilation and genuine RTL retirement are
checked by the isolated workflow, not by this static test module.
"""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from tools.ci.product_c1_arch_relay import qemu_ram_first_retire_source as proof

ROOT = Path(__file__).resolve().parents[1]


class QemuRamFirstRetireStaticContract(unittest.TestCase):
    def test_exact_h_off_qemu_input_and_real_pmp_entries(self):
        self.assertEqual(proof.SOURCE_REGS["misa"], "0x800000000014112d")
        self.assertEqual(proof.SOURCE_REGS["mstatus"], "0xa000000a2")
        self.assertEqual(proof.SOURCE_REGS["satp"], "0x80000000000856fe")
        self.assertEqual(proof.SOURCE_REGS["priv"], "0x1")
        self.assertEqual(proof.SOURCE_REGS["x2"], "0xffffffc80000bec0")
        self.assertEqual(proof.SOURCE_REGS["pmpcfg0"], "0x1f1b18181b1818")
        self.assertEqual(proof.SOURCE_REGS["pmpcfg2"], "0x0")
        self.assertEqual(len(proof.PMP_ADDRS), 16)
        self.assertEqual(proof.PMP_ADDRS[6], (1 << 64) - 1)
        self.assertEqual(proof.PMP_ADDRS[7:], [0] * 9)

    def test_real_source_pagewalk_and_first_instruction_oracle(self):
        self.assertEqual(proof.PC, 0xffffffff808c3490)
        self.assertEqual(proof.INSTRUCTION_PA, 0x80ac3490)
        self.assertEqual(proof.PTE_ORACLE,
                         ((0x856feff0, 0x23fff401),
                          (0x8fffd020, 0x202800eb)))
        self.assertEqual(0xffffffc80000bec0 - 48, 0xffffffc80000be90)

    def test_source_sha_pins_complete(self):
        for digest in (proof.SOURCE_RAM_SHA, proof.SOURCE_MANIFEST_SHA):
            self.assertEqual(len(digest), 64)
            self.assertEqual(int(digest, 16) >= 0, True)

    def test_fail_closed_input_guard(self):
        with self.assertRaisesRegex(ValueError, "closed"):
            proof.require(False, "closed")

    def test_full_xpr_header_built_only_from_verified_values(self):
        with TemporaryDirectory() as work:
            path = Path(work) / "qemu_captured_gprs.h"
            regs = [0] * 32
            regs[2] = int(proof.SOURCE_REGS["x2"], 16)
            proof.emit_header(path, regs)
            self.assertIn("kCapturedGprs", path.read_text())
            self.assertIn("0xffffffc80000bec0ULL", path.read_text())
            with self.assertRaisesRegex(ValueError, "invalid captured"):
                proof.emit_header(path, [1] * 32)

    def test_synthetic_mret_and_debian_claims_absent(self):
        host = (ROOT / "sim/product_c1_g5_qemu_ram_first_retire_main.cpp").read_text()
        self.assertIn("REAL_QEMU_RAM_FIRST_RETIRE_PARTIAL_ARCH_PASS", host)
        self.assertIn("kSourceSatp = 0x80000000000856feULL", host)
        self.assertIn("kSourceMstatus = 0x0000000a000000a2ULL", host)
        self.assertIn("csr_device_fp_lrsc_incomplete=1", host)
        self.assertIn("qemu_all_32_xpr=1", host)
        self.assertNotIn("top.io_warmSeed_csr_pmpConfig_0 = 0x1f;", host)
        self.assertNotIn("mret ", host.lower())

    def test_workflow_run_zip_and_inner_sha_pinned(self):
        workflow = (ROOT / ".github/workflows/product-c1-g5-real-qemu-ram-first-retire.yml").read_text()
        for value in (
            "42489471408", "13621196662",
            "2d7be8a7f4440868961c82039fb33be7dc4808655130b6db13de4f5c3e499afb",
            proof.SOURCE_RAM_SHA, "source-artifact.json", "source-run.json",
            "source-jobs.json", "qemu_ram_first_retire_source.py",
            "--gpr-header", "qemu_captured_gprs.h",
        ):
            self.assertIn(value, workflow)
        self.assertNotIn("design/rv64gc-product-architecture-20261103", workflow)


if __name__ == "__main__":
    unittest.main()
