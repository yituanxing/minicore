"""Bounded, real-RAM-compatible Sv39 source walk tests; never an RTL PASS."""

import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / 'tools/ci/product_c1_arch_relay/sv39_source_walk.py'
SPEC = importlib.util.spec_from_file_location('product_c1_sv39_source_walk', MODULE)
import sys
sys.path.insert(0, str(MODULE.parent))
walk = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(walk)
import preflight


BASE = 0x80000000
VA = 0xffffffff80002000
ROOT_PA = BASE
L1_PA = BASE + 0x1000
L0_PA = BASE + 0x2000
CODE_PA = BASE + 0x3000
SATP = (8 << 60) | (ROOT_PA >> 12)
INSN = bytes.fromhex('13051000')  # addi a0, zero, 1


def pointer(pa, global_flag=False):
    return ((pa >> 12) << 10) | 1 | (32 if global_flag else 0)


def execute_leaf(pa, accessed=True):
    return ((pa >> 12) << 10) | 1 | 2 | 8 | (64 if accessed else 0)


def write_pte(memory, table_pa, va, level, pte):
    idx = (va >> (12 + 9 * level)) & 0x1ff
    offset = table_pa - BASE + idx * 8
    memory[offset:offset + 8] = pte.to_bytes(8, 'little')


def table_ram(va=VA):
    memory = bytearray(0x7000)
    write_pte(memory, ROOT_PA, va, 2, pointer(L1_PA, global_flag=True))
    write_pte(memory, L1_PA, va, 1, pointer(L0_PA))
    write_pte(memory, L0_PA, va, 0, execute_leaf(CODE_PA))
    memory[CODE_PA - BASE:CODE_PA - BASE + 4] = INSN
    return memory


class Sv39ReadOnlyPageWalkTest(unittest.TestCase):
    def test_three_level_kernel_translation_and_no_writes(self):
        memory = table_ram()
        original = bytes(memory)
        result = walk.walk_exec(io.BytesIO(memory), BASE, len(memory), SATP, VA)
        self.assertEqual(result['physical_pc'], CODE_PA)
        self.assertEqual(result['leaf_level'], 0)
        self.assertEqual([s['level'] for s in result['pte_path']], [2, 1, 0])
        self.assertEqual(bytes(memory), original)

    def test_two_mib_superpage_translation(self):
        va = 0xffffffff80000000
        root = BASE + 0x1000
        second = BASE + 0x2000
        memory = bytearray(0x4000)
        write_pte(memory, root, va, 2, pointer(second))
        write_pte(memory, second, va, 1, execute_leaf(BASE))
        result = walk.walk_exec(io.BytesIO(memory), BASE, len(memory),
                                (8 << 60) | (root >> 12), va)
        self.assertEqual(result['physical_pc'], BASE)
        self.assertEqual(result['leaf_level'], 1)

    def test_reject_superpage_alignment_invalid_pte_and_unaccessed(self):
        memory = table_ram()
        write_pte(memory, L1_PA, VA, 1, execute_leaf(BASE + 0x1000))
        with self.assertRaisesRegex(walk.WalkError, 'misaligned Sv39 superpage'):
            walk.walk_exec(io.BytesIO(memory), BASE, len(memory), SATP, VA)
        write_pte(memory, L1_PA, VA, 1, pointer(L0_PA))
        write_pte(memory, L0_PA, VA, 0, execute_leaf(CODE_PA, accessed=False))
        with self.assertRaisesRegex(walk.WalkError, 'not executable/accessed'):
            walk.walk_exec(io.BytesIO(memory), BASE, len(memory), SATP, VA)
        write_pte(memory, L0_PA, VA, 0, 0x5)  # V=1, W=1, R=0
        with self.assertRaisesRegex(walk.WalkError, 'invalid Sv39 PTE'):
            walk.walk_exec(io.BytesIO(memory), BASE, len(memory), SATP, VA)

    def test_reject_noncanonical_va_wrong_mode_and_out_of_ram_pt(self):
        memory = table_ram()
        with self.assertRaisesRegex(walk.WalkError, 'not Sv39 canonical'):
            walk.walk_exec(io.BytesIO(memory), BASE, len(memory), SATP,
                           0x0000008000000000)
        with self.assertRaisesRegex(walk.WalkError, 'satp.MODE=10'):
            walk.walk_exec(io.BytesIO(memory), BASE, len(memory),
                           (10 << 60) | (ROOT_PA >> 12), VA)
        with self.assertRaisesRegex(walk.WalkError, 'escapes sealed DDR'):
            walk.walk_exec(io.BytesIO(memory), BASE, len(memory),
                           (8 << 60) | (0x90000000 >> 12), VA)

    def test_reject_nonleaf_reserved_flags_but_accept_global_bit(self):
        memory = table_ram()
        # table_ram sets G in the level-2 pointer: this must be valid.
        self.assertEqual(walk.walk_exec(io.BytesIO(memory), BASE, len(memory),
                                        SATP, VA)['physical_pc'], CODE_PA)
        write_pte(memory, ROOT_PA, VA, 2, pointer(L1_PA) | 64)
        with self.assertRaisesRegex(walk.WalkError, 'non-leaf.*leaf-only'):
            walk.walk_exec(io.BytesIO(memory), BASE, len(memory), SATP, VA)

    def test_sha_bound_instruction_payload_report(self):
        memory = table_ram()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'ram.bin'
            path.write_bytes(memory)
            manifest = {
                'qualification': preflight.QUALIFICATION,
                'arch_restore_ready': False,
                'rtl_cpu_injected': False,
                'breakpoint_pc': f'0x{VA:x}',
                'qemu_gdb_registers': {
                    'pc': f'0x{VA:x}', 'priv': '0x1', 'satp': f'0x{SATP:x}'},
                'memory': {'start': f'0x{BASE:x}', 'bytes': len(memory),
                           'sha256': preflight.file_sha(path)},
            }
            report = walk.inspect(manifest, path, expected_ram_bytes=len(memory))
            self.assertEqual(report['instruction_bytes_le'], INSN.hex())
            self.assertEqual(report['instruction_width_bytes'], 4)
            self.assertEqual(report['translated_physical_pc'], f'0x{CODE_PA:x}')
            self.assertFalse(report['rtl_handoff_pass'])
            self.assertFalse(report['rtl_warm_resume_permitted'])
            path.write_bytes(b'\xff' * len(memory))
            with self.assertRaisesRegex(ValueError, 'source bytes differ'):
                walk.inspect(manifest, path, expected_ram_bytes=len(memory))

    def test_instruction_spanning_two_noncontiguous_physical_pages(self):
        va = 0xffffffff80001ffe
        memory = table_ram(va)
        next_va = va + 2
        next_page = BASE + 0x4000
        write_pte(memory, L0_PA, next_va, 0, execute_leaf(next_page))
        memory[CODE_PA - BASE + 0xffe:CODE_PA - BASE + 0x1000] = INSN[:2]
        memory[next_page - BASE:next_page - BASE + 2] = INSN[2:]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'ram.bin'
            path.write_bytes(memory)
            manifest = {
                'qualification': preflight.QUALIFICATION,
                'arch_restore_ready': False, 'rtl_cpu_injected': False,
                'breakpoint_pc': f'0x{va:x}',
                'qemu_gdb_registers': {'pc': f'0x{va:x}', 'priv': '0x1',
                                       'satp': f'0x{SATP:x}'},
                'memory': {'start': f'0x{BASE:x}', 'bytes': len(memory),
                           'sha256': preflight.file_sha(path)},
            }
            result = walk.inspect(manifest, path, expected_ram_bytes=len(memory))
            self.assertEqual(result['instruction_bytes_le'], INSN.hex())
            self.assertEqual(result['second_page_walk']['physical_pc'], next_page)


if __name__ == '__main__':
    unittest.main()
