#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "ci" / "make_l32_dtb.py"
spec = importlib.util.spec_from_file_location("aethercore_make_dtb", MODULE_PATH)
assert spec is not None and spec.loader is not None
make_dtb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(make_dtb)


class OpenSbiDtbProfileTest(unittest.TestCase):
    def test_generic_default_is_byte_identical_to_frozen_l32_entry(self) -> None:
        legacy = make_dtb.build_l32_dtb()
        generic = make_dtb.build_profile_dtb(
            isa=make_dtb.DEFAULT_CPU_ISA,
            mmu="sv32",
        )
        self.assertEqual(generic, legacy)

    def test_rv64_sv39_profile_reuses_the_same_board_description(self) -> None:
        blob = make_dtb.build_profile_dtb(
            isa="rv64ima_zicsr_zifencei",
            mmu="sv39",
        )
        self.assertIn(b"rv64ima_zicsr_zifencei\0", blob)
        self.assertIn(b"riscv,sv39\0", blob)
        self.assertIn(b"aethercore,rv64\0", blob)
        self.assertIn(b"AetherCore RV64 Sv39\0", blob)
        self.assertIn(b"sifive,plic-1.0.0\0", blob)
        self.assertIn(b"ns16550a\0", blob)
        self.assertIn(b"riscv,aclint-mtimer\0", blob)

    def test_cross_xlen_mmu_pairs_fail_closed(self) -> None:
        with self.assertRaises(ValueError):
            make_dtb.build_profile_dtb(isa="rv32ima_zicsr", mmu="sv39")
        with self.assertRaises(ValueError):
            make_dtb.build_profile_dtb(isa="rv64ima_zicsr", mmu="sv32")
        with self.assertRaises(ValueError):
            make_dtb.build_profile_dtb(isa="rv64ima_zicsr", mmu="sv48")


if __name__ == "__main__":
    unittest.main()
