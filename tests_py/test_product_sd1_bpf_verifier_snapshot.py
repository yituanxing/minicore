#!/usr/bin/env python3
"""Small, offline tests for the read-only SD1 verifier evidence decoder."""

import io
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools" / "ci"))
import product_sd1_bpf_verifier_snapshot as bpf


class BpfVerifierSnapshotTest(unittest.TestCase):
    def test_sv39_translation_and_kernel_direct_alias(self):
        with tempfile.TemporaryFile() as stream:
            stream.truncate(bpf.DDR_SIZE)
            memory = bpf.DDR.__new__(bpf.DDR)
            memory.stream = stream
            memory.base = 0
            va = 0x3FF56EDA00
            root, second, third, page = (
                0x80001000, 0x80002000, 0x80003000, 0x80004000
            )
            for table, next_table, level in (
                (root, second, 2), (second, third, 1), (third, page, 0)
            ):
                slot = (va >> (12 + 9 * level)) & 511
                flags = 0x13 if level == 0 else 1
                entry = ((next_table >> 12) << 10) | flags
                stream.seek(table - bpf.DDR_BASE + 8 * slot)
                stream.write(entry.to_bytes(8, "little"))
            stream.seek(page - bpf.DDR_BASE + (va & 4095))
            stream.write(b"sd_bind6")
            root_va = bpf.LINEAR_VIRT + root - bpf.DDR_BASE
            self.assertEqual(memory.virt(root_va, va, 8), b"sd_bind6")
            self.assertEqual(memory.direct(root_va, 8),
                             memory.phys(root, 8))

    def test_manifest_requires_exact_image_and_no_duplicate_keys(self):
        good = "\n".join((
            "schema=AETSDCP1", "qualification=" + bpf.QUALIFICATION,
            "linux_image_sha256=" + bpf.IMAGE_SHA,
        )) + "\n"
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / ".migration-ready"
            path.write_text(good)
            self.assertEqual(bpf.manifest(path)["schema"], "AETSDCP1")
            path.write_text(good + "schema=AETSDCP1\n")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                bpf.manifest(path)
            path.write_text(good.replace(bpf.IMAGE_SHA, "0" * 64))
            with self.assertRaisesRegex(ValueError, "different Linux Image"):
                bpf.manifest(path)

    def test_same_call_progress_and_program_switch(self):
        first = {
            "boot_cycles": 4512000000, "linux_image_sha256": bpf.IMAGE_SHA,
            "pid1_task": "pid1", "syscall_epc": "pc",
            "bpf_attr_addr": "attr", "prog_name": "sd_bind6",
            "prog_type": 18, "insn_cnt": 82, "expected_attach_type": 9,
            "env_addr": "env", "pass_cnt": 1, "insn_processed": 5295,
            "total_states": 133, "jmps_processed": 2262,
        }
        second = {**first, "boot_cycles": 4552000000,
                  "insn_processed": 7438, "total_states": 178,
                  "jmps_processed": 3148}
        result = bpf.compare([second, first])
        self.assertTrue(result[1]["same_candidate_syscall_as_previous"])
        self.assertEqual(result[1]["insn_processed_delta"], 2143)
        other = {**second, "prog_name": "sd_bind4"}
        self.assertFalse(bpf.compare([first, other])[1]
                         ["same_candidate_syscall_as_previous"])


if __name__ == "__main__":
    unittest.main()
