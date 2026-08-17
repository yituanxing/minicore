from __future__ import annotations

import unittest

from tools.ci.riscv_elf_profile import audit_evidence, executable_load_end, parse_arch


def elf32_riscv(flags: int, *, exec_vaddr: int | None = None, exec_memsz: int = 0x1000) -> bytes:
    size = 84 if exec_vaddr is not None else 64
    data = bytearray(size)
    data[:4] = b"\x7fELF"
    data[4] = 1
    data[5] = 1
    data[18:20] = (243).to_bytes(2, "little")
    data[36:40] = flags.to_bytes(4, "little")
    data[40:42] = (52).to_bytes(2, "little")
    if exec_vaddr is not None:
        data[28:32] = (52).to_bytes(4, "little")
        data[42:44] = (32).to_bytes(2, "little")
        data[44:46] = (1).to_bytes(2, "little")
        ph = 52
        data[ph : ph + 4] = (1).to_bytes(4, "little")
        data[ph + 8 : ph + 12] = exec_vaddr.to_bytes(4, "little")
        data[ph + 20 : ph + 24] = exec_memsz.to_bytes(4, "little")
        data[ph + 24 : ph + 28] = (1).to_bytes(4, "little")
    return bytes(data)


class RiscvElfProfileContract(unittest.TestCase):
    def test_parse_versioned_canonical_extensions(self) -> None:
        xlen, extensions = parse_arch(
            "rv32i2p1_m2p0_a2p1_c2p0_zicsr2p0_zifencei2p0_zmmul1p0"
        )
        self.assertEqual(xlen, 32)
        self.assertTrue({"i", "m", "a", "c", "zicsr", "zifencei"} <= extensions)

    def test_c_profile_requires_flag_attribute_and_real_encoding(self) -> None:
        arch = 'Tag_RISCV_arch: "rv32i2p1_m2p0_a2p1_c2p0_zicsr2p0_zifencei2p0"'
        disassembly = "80000000: 1141 c.addi16sp sp,-16\n80000002: 00000013 nop\n"
        observed, flags, compressed = audit_evidence(
            "busybox",
            elf32_riscv(0x1),
            arch,
            disassembly,
            require_c=True,
        )
        self.assertIn("_c2p0_", observed)
        self.assertEqual(flags, 1)
        self.assertEqual(compressed, 1)

    def test_c_profile_rejects_attribute_only_false_positive(self) -> None:
        arch = 'Tag_RISCV_arch: "rv32i2p1_m2p0_a2p1_c2p0_zicsr2p0_zifencei2p0"'
        with self.assertRaisesRegex(ValueError, "no real 16-bit"):
            audit_evidence(
                "lua",
                elf32_riscv(0x1),
                arch,
                "80000000: 00000013 nop\n",
                require_c=True,
            )

    def test_non_c_profile_rejects_any_rvc_evidence(self) -> None:
        arch = 'Tag_RISCV_arch: "rv32i2p1_m2p0_a2p1_zicsr2p0_zifencei2p0"'
        with self.assertRaisesRegex(ValueError, "RVC ELF flag"):
            audit_evidence(
                "busybox",
                elf32_riscv(0x1),
                arch,
                "80000000: 00000013 nop\n",
                require_c=False,
            )

    def test_generic_profile_does_not_require_actual_atomic_instruction(self) -> None:
        arch = 'Tag_RISCV_arch: "rv32i2p1_m2p0_a2p1_c2p0_zicsr2p0_zifencei2p0"'
        _, _, compressed = audit_evidence(
            "zlib-smoke",
            elf32_riscv(0x1),
            arch,
            "80000000: 0001 c.nop\n",
            require_c=True,
        )
        self.assertEqual(compressed, 1)

    def test_executable_load_end_supports_userspace_address_domain_proof(self) -> None:
        data = elf32_riscv(0x1, exec_vaddr=0x00010000, exec_memsz=0x2340)
        self.assertEqual(executable_load_end(data), 0x00012340)
        self.assertLess(executable_load_end(data), 0x80000000)

    def test_executable_load_end_rejects_images_without_executable_segment(self) -> None:
        with self.assertRaisesRegex(ValueError, "no program headers"):
            executable_load_end(elf32_riscv(0x1))


if __name__ == "__main__":
    unittest.main()
