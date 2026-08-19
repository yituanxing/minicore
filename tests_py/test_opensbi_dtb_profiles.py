#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "ci" / "make_l32_dtb.py"
spec = importlib.util.spec_from_file_location("aethercore_make_dtb", MODULE_PATH)
assert spec is not None and spec.loader is not None
make_dtb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(make_dtb)

FROZEN_L32_DTB_BYTES = 1536
FROZEN_L32_DTB_SHA256 = "764411e519b2b4423081ec4e637241403e66e112a0199691c740afda81b1391e"


class OpenSbiDtbProfileTest(unittest.TestCase):
    def test_frozen_l32_default_has_independent_historical_anchor(self) -> None:
        blob = make_dtb.build_l32_dtb()
        self.assertEqual(len(blob), FROZEN_L32_DTB_BYTES)
        self.assertEqual(hashlib.sha256(blob).hexdigest(), FROZEN_L32_DTB_SHA256)

    def test_generic_default_is_byte_identical_to_frozen_l32_entry(self) -> None:
        legacy = make_dtb.build_l32_dtb()
        generic = make_dtb.build_profile_dtb(
            isa=make_dtb.DEFAULT_CPU_ISA,
            mmu="sv32",
        )
        self.assertEqual(generic, legacy)

        # Keep the conditional chosen/bootargs property on the same shared path.
        bootargs = "console=ttyS0 earlycon"
        self.assertEqual(
            make_dtb.build_profile_dtb(
                bootargs=bootargs,
                isa=make_dtb.DEFAULT_CPU_ISA,
                mmu="sv32",
            ),
            make_dtb.build_l32_dtb(bootargs=bootargs),
        )

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
