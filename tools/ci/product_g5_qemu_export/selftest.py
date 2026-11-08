#!/usr/bin/env python3
"""Fast no-QEMU contract tests for the optional diagnostic exporter."""

import importlib.util
import json
import tempfile
from pathlib import Path
import unittest


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("qemu_arch_export", HERE / "qemu_arch_export.py")
exp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exp)


class StubRsp:
    def __init__(self, pc, register_values):
        self.pc = pc
        self.register_values = register_values
        self.names = list(register_values)
        self.sock = self
        self.timeout = 0

    def settimeout(self, seconds):
        self.timeout = seconds

    def request(self, text):
        if text == "?":
            return "S05"
        if text.startswith("Z1,"):
            return "OK"
        if text == "c":
            return "T05"
        if text == "qqemu.PhyMemMode":
            return "0"
        if text.startswith("Qqemu.PhyMemMode:"):
            return "OK"
        raise AssertionError("unexpected RSP request: " + text)

    def xml(self, annex):
        assert annex == "target.xml"
        body = ''.join(f'<reg name="{name}" bitsize="64" regnum="{index}"/>'
                       for index, name in enumerate(self.names))
        return f'<target><feature name="org.gnu.gdb.riscv.cpu">{body}</feature></target>'

    def reg(self, number):
        return self.register_values[self.names[number]]

    def read_phys64(self, address):
        return {exp.MTIME_ADDR: 12345, exp.MTIMECMP_ADDR: 12355}[address]


class StubQmp:
    def __init__(self, ram_size):
        self.ram_size = ram_size

    def command(self, op, args=None):
        if op == "query-status":
            return {"running": False, "status": "paused"}
        if op == "query-cpus-fast":
            return [{"cpu-index": 0, "qom-path": "/machine/unattached/device[0]"}]
        assert op == "pmemsave"
        assert args["val"] == exp.RAM_BASE
        assert args["size"] == self.ram_size
        path = Path(args["filename"])
        path.write_bytes(bytes(self.ram_size))
        return ""


class ArchExporterContractTest(unittest.TestCase):
    def test_native_qmp_physical_dump_failure_is_not_silenced(self):
        class DeniedQmp(StubQmp):
            def command(self, op, args=None):
                if op == "pmemsave":
                    raise exp.ProtocolError(
                        "QMP pmemsave failed: Permission denied")
                return super().command(op, args)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old_size = exp.RAM_SIZE
            exp.RAM_SIZE = 1024
            try:
                inputs = {}
                for name in ("firmware", "dtb", "linux_image", "system_map"):
                    path = root / name
                    path.write_bytes(name.encode())
                    inputs[name] = path
                pc = 0xFFFFFFFF80001140
                rsp = StubRsp(pc, {"pc": pc, "x0": 0})
                out = root / "out"
                out.mkdir()
                with self.assertRaisesRegex(exp.ProtocolError, "Permission denied"):
                    exp.collect(rsp, DeniedQmp(1024), out, pc, inputs, False, 5)
                self.assertFalse((out / "manifest.json").exists())
            finally:
                exp.RAM_SIZE = old_size

    def test_xml_annex_and_explicit_numbering(self):
        xml = {
            "target.xml": '<target xmlns:xi="http://www.w3.org/2001/XInclude">'
                          '<xi:include href="core.xml"/>'
                          '<xi:include href="csrs.xml"/></target>',
            "core.xml": '<feature><reg name="x0" regnum="0"/>'
                        '<reg name="pc"/></feature>',
            "csrs.xml": '<feature><reg name="mstatus" regnum="0x100"/>'
                        '<reg name="satp"/></feature>',
        }
        self.assertEqual(exp.registers_from_annexes(xml.__getitem__),
                         {"x0": 0, "pc": 1, "mstatus": 256, "satp": 257})

    def test_undeclared_xi_namespace_from_historic_qemu_stub(self):
        xml = {
            "target.xml": '<target version="1.0"><xi:include href="core.xml"/>'
                          '<xi:include href="csrs.xml"/></target>',
            "core.xml": '<feature><reg name="x0" regnum="0"/>'
                        '<reg name="pc"/></feature>',
            "csrs.xml": '<feature><reg name="mstatus" regnum="0x100"/>'
                        '<reg name="satp"/></feature>',
        }
        self.assertEqual(exp.registers_from_annexes(xml.__getitem__),
                         {"x0": 0, "pc": 1, "mstatus": 256, "satp": 257})

    def test_safe_nested_xml_include(self):
        xml = {
            "target.xml": '<target><xi:include href="first.xml"/></target>',
            "first.xml": '<feature xmlns:xi="http://www.w3.org/2001/XInclude">'
                         '<reg name="zero" regnum="0"/>'
                         '<xi:include href="second.xml"/></feature>',
            "second.xml": '<feature><reg name="ra"/><reg name="sp"/></feature>',
        }
        self.assertEqual(exp.registers_from_annexes(xml.__getitem__),
                         {"zero": 0, "x0": 0, "ra": 1, "x1": 1, "sp": 2, "x2": 2})

    def test_xml_rejects_unsafe_href_and_cycles(self):
        for href in ("../secret.xml", "a..b.xml", "/tmp/foo.xml",
                     "https://host/file.xml", "subdir/foo.xml", ".hidden.xml"):
            with self.subTest(href=href):
                xml = '<target><xi:include href="' + href + '"/></target>'
                with self.assertRaisesRegex(exp.ProtocolError, "unsafe.*href"):
                    exp.registers_from_annexes(lambda _: xml)
        cyclic = {
            "target.xml": '<target><xi:include href="one.xml"/></target>',
            "one.xml": '<feature xmlns:xi="http://www.w3.org/2001/XInclude">'
                       '<xi:include href="target.xml"/></feature>',
        }
        with self.assertRaisesRegex(exp.ProtocolError, "cyclic"):
            exp.registers_from_annexes(cyclic.__getitem__)

    def test_xml_rejects_duplicate_regnums_and_bad_namespaces(self):
        duplicate = '<target><reg name="x0" regnum="0"/>' \
                    '<reg name="x1" regnum="0"/></target>'
        with self.assertRaisesRegex(exp.ProtocolError, "duplicate.*number"):
            exp.registers_from_annexes(lambda _: duplicate)
        wrong = '<target xmlns:xi="http://example.invalid">'
        wrong += '<xi:include href="core.xml"/></target>'
        with self.assertRaisesRegex(exp.ProtocolError, "unsupported.*namespace"):
            exp.registers_from_annexes(lambda _: wrong)
        with self.assertRaisesRegex(exp.ProtocolError, "invalid GDB XML"):
            exp.registers_from_annexes(lambda _: '<target><reg')

    def test_xml_rejects_unbounded_includes(self):
        xml = {"target.xml": '<target><xi:include href="f0.xml"/></target>'}
        for i in range(18):
            xml[f"f{i}.xml"] = (
                '<feature xmlns:xi="http://www.w3.org/2001/XInclude">'
                f'<xi:include href="f{i + 1}.xml"/></feature>')
        with self.assertRaisesRegex(exp.ProtocolError, "depth/count exceeded"):
            exp.registers_from_annexes(xml.__getitem__)

    def test_abi_register_names_normalize_to_architecture_numbers(self):
        xml = '<target><feature><reg name="zero" regnum="0"/>' \
              '<reg name="ra"/><reg name="sp"/>' \
              '<reg name="ft0" regnum="33"/><reg name="fa0"/>' \
              '</feature></target>'
        reg = exp.registers_from_annexes(lambda _: xml)
        self.assertEqual((reg["x0"], reg["x1"], reg["x2"], reg["f0"], reg["f10"]),
                         (0, 1, 2, 33, 34))

    def test_target_endian_and_unavailable(self):
        self.assertEqual(exp.decode_riscv_le("0100000000000000"), 1)
        self.assertEqual(exp.decode_riscv_le("ffffffffffffffff"), (1 << 64) - 1)
        self.assertIsNone(exp.decode_riscv_le("xxxxxxxxxxxxxxxx"))
        self.assertIsNone(exp.decode_riscv_le("E02"))

    def test_capture_always_diagnostic_not_rtl_and_reports_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old_size = exp.RAM_SIZE
            exp.RAM_SIZE = 1024
            try:
                inputs = {}
                for name in ("firmware", "dtb", "linux_image", "system_map"):
                    path = root / name
                    path.write_bytes(name.encode())
                    inputs[name] = path
                pc = 0xFFFFFFFF80001140
                rsp = StubRsp(pc, {"pc": pc, "x0": 0, "x1": 123,
                                    "f0": 0, "mstatus": 0, "satp": 0})
                out = root / "output"
                out.mkdir()
                result = exp.collect(rsp, StubQmp(exp.RAM_SIZE), out, pc,
                                     inputs, True, 5)
                self.assertFalse(result["arch_restore_ready"])
                self.assertFalse(result["rtl_cpu_injected"])
                self.assertIn("uart.configuration_and_fifo_state_unrestored",
                              result["unexported_device_and_rtl_state"])
                self.assertIn("fcsr", result["missing_gdb_registers"])
                self.assertEqual(result["read_only_clint"]["mtime"], "0x3039")
                self.assertEqual((out / "ram-0x80000000-0x10000000.bin").stat().st_size,
                                 1024)
                self.assertEqual(json.loads((out / "manifest.json").read_text()), result)
                with self.assertRaisesRegex(exp.ProtocolError, "not checkpoint"):
                    exp.collect(rsp, StubQmp(exp.RAM_SIZE), out, pc + 2,
                                inputs, False, 5)
            finally:
                exp.RAM_SIZE = old_size


if __name__ == "__main__":
    unittest.main(verbosity=2)
