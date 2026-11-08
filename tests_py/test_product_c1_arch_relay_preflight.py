"""No-RTL, no-QEMU source-contract tests for the proposed C1 handoff."""

import importlib.util
import json
import tempfile
from pathlib import Path
import unittest
from unittest import mock
import sys


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / 'tools/ci/product_c1_arch_relay/preflight.py'
SPEC = importlib.util.spec_from_file_location('product_c1_preflight', MODULE)
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.ram = self.root / 'ram.bin'
        self.ram.write_bytes(bytes(range(64)))
        self.sources = {}
        self.inputs = {}
        for name in preflight.INPUTS:
            path = self.root / name
            path.write_text('sealed-' + name)
            self.sources[name] = path
            self.inputs[name] = {'sha256': preflight.file_sha(path)}
        self.regs = {'pc': '0x80400000', 'priv': '0x1', 'mstatus': '0x0'}
        self.regs.update({f'x{i}': f'0x{i:x}' for i in range(32)})
        self.regs['x0'] = '0x0'
        self.regs.update({csr: '0x0' for csr in preflight.CSRS if csr != 'mstatus'})
        self.export = {
            'qualification': preflight.QUALIFICATION,
            'arch_restore_ready': False, 'rtl_cpu_injected': False,
            'qemu_cpu_count': 1,
            'memory': {'start': '0x80000000', 'bytes': 64,
                       'sha256': preflight.file_sha(self.ram)},
            'inputs': self.inputs,
            'breakpoint_pc': '0x80400000',
            'qemu_gdb_registers': self.regs,
            'missing_gdb_registers': [],
            'unexported_device_and_rtl_state': ['PLIC state not captured'],
        }

    def inspect(self):
        return preflight.inspect(self.export, self.ram, self.sources,
                                 expected_ram_bytes=64)

    def test_complete_s_integer_state_is_still_not_an_rtl_pass(self):
        report = self.inspect()
        self.assertTrue(report['arch_registers_complete'])
        self.assertFalse(report['rtl_handoff_pass'])
        self.assertFalse(report['rtl_injected'])
        self.assertEqual(report['result'], 'C1_SOURCE_REVIEWED_NOT_RTL_RESUMED')

    def test_missing_priv_or_satp_is_reported_not_zero_filled(self):
        del self.regs['priv']
        del self.regs['satp']
        report = self.inspect()
        self.assertFalse(report['arch_registers_complete'])
        self.assertIn('priv', report['missing_arch_registers'])
        self.assertIn('satp', report['missing_arch_registers'])

    def test_cli_refuses_incomplete_capture_even_with_valid_ram_sha(self):
        del self.regs['priv']
        manifest = self.root / 'manifest.json'
        manifest.write_text(json.dumps(self.export))
        report = self.root / 'review.json'
        argv = ['preflight.py', '--manifest', str(manifest), '--ram', str(self.ram),
                '--report', str(report)]
        for name, path in self.sources.items():
            argv += ['--' + name.replace('_', '-'), str(path)]
        with mock.patch.object(sys, 'argv', argv), mock.patch.object(preflight, 'RAM_BYTES', 64):
            self.assertEqual(preflight.main(), 3)
        self.assertTrue(report.is_file())
        self.assertFalse(json.loads(report.read_text())['rtl_handoff_pass'])

    def test_fp_state_required_when_mstatus_fs_is_enabled(self):
        self.regs['mstatus'] = '0x2000'
        report = self.inspect()
        self.assertIn('fcsr', report['missing_arch_registers'])
        self.assertIn('f31', report['missing_arch_registers'])

    def test_corrupted_ram_and_input_digest_are_rejected(self):
        self.ram.write_bytes(b'0' * 64)
        with self.assertRaisesRegex(ValueError, 'RAM: source bytes differ'):
            self.inspect()
        self.ram.write_bytes(bytes(range(64)))
        self.sources['dtb'].write_text('tampered')
        with self.assertRaisesRegex(ValueError, 'dtb: source bytes differ'):
            self.inspect()

    def test_reject_spoofed_restore_and_wrong_platform_evidence(self):
        self.export['rtl_cpu_injected'] = True
        with self.assertRaisesRegex(ValueError, 'nonexistent RTL import'):
            self.inspect()
        self.export['rtl_cpu_injected'] = False
        self.export['memory']['start'] = '0x90000000'
        with self.assertRaisesRegex(ValueError, 'physical base'):
            self.inspect()

    def test_reject_bad_pc_mode_x0_or_missing_blockers(self):
        self.regs['priv'] = '0x3'
        with self.assertRaisesRegex(ValueError, 'supervisor mode'):
            self.inspect()
        self.regs['priv'] = '0x1'
        self.regs['x0'] = '0x1'
        with self.assertRaisesRegex(ValueError, 'x0 must be zero'):
            self.inspect()
        self.regs['x0'] = '0x0'
        self.export['unexported_device_and_rtl_state'] = []
        with self.assertRaisesRegex(ValueError, 'explicitly recorded'):
            self.inspect()


if __name__ == '__main__':
    unittest.main()
