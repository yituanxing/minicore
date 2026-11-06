#!/usr/bin/env python3
"""Offline protocol/phase tests; do not require QEMU, Linux, or RTL."""

import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import types
import unittest


HERE = Path(__file__).resolve().parent
fake_transport = types.ModuleType("qemu_arch_export")


class ProtocolError(RuntimeError):
    pass


fake_transport.ProtocolError = ProtocolError
fake_transport.Rsp = object
fake_transport.Qmp = object
fake_transport.sha256 = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
fake_transport.registers_from_annexes = lambda read_annex: {"pc": 32} if read_annex("target.xml") else {}
fake_transport.system_map_symbol = (
    lambda _path, name: {"populate_rootfs": 0xFFFFFFFF80A02826,
                         "run_init_process": 0xFFFFFFFF808C3490}[name]
)
sys.modules["qemu_arch_export"] = fake_transport
spec = importlib.util.spec_from_file_location("qemu_icount_probe", HERE / "qemu_icount_probe.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class FakeRsp:
    requests = []
    force_bad_pc = False

    def __init__(self, _path, _timeout):
        self.requests = []
        FakeRsp.requests = self.requests
        self.exec_stops = 0

    def request(self, value):
        self.requests.append(value)
        if value == "?":
            return "T05"
        if value == "c":
            self.exec_stops += 1
            return "T05"
        if value.startswith(("Z1,", "z1,")):
            return "OK"
        raise AssertionError(f"unexpected GDB command: {value}")

    def close(self):
        pass

    def xml(self, annex):
        return "<target/>" if annex == "target.xml" else ""

    def reg(self, number):
        assert number == 32
        if self.force_bad_pc:
            return 0xDEADBEEF
        return (0xFFFFFFFF80A02826 if self.exec_stops == 1
                else 0xFFFFFFFF808C3490)


class FakeQmp:
    current_counts = [70000000, 480000000]
    current_status = "prelaunch"

    def __init__(self, _path, _timeout):
        self.counts = iter(self.current_counts)
        self.initial = True

    def command(self, op):
        if op == "query-status":
            if self.initial:
                self.initial = False
                return {"running": False, "status": self.current_status}
            return {"running": False, "status": "debug"}
        if op == "query-cpus-fast":
            return [{"cpu-index": 0}]
        if op == "query-replay":
            return {"mode": "none", "icount": next(self.counts)}
        raise AssertionError(f"unexpected QMP command: {op}")

    def close(self):
        pass


class ProbeTests(unittest.TestCase):
    def setUp(self):
        FakeQmp.current_counts = [70000000, 480000000]
        FakeQmp.current_status = "prelaunch"
        FakeRsp.force_bad_pc = False

    def args(self, root):
        paths = {}
        for name in ("firmware", "dtb", "linux_image", "system_map"):
            path = root / name
            path.write_bytes(name.encode())
            paths[name] = str(path)
        return types.SimpleNamespace(
            qmp_socket="/mock/qmp.sock", gdb_socket="/mock/gdb.sock",
            out_dir=str(root / "output"), icount_shift=6, wall_seconds=60, **paths)

    def test_two_breakpoint_same_process_qmp_counts(self):
        with tempfile.TemporaryDirectory() as d:
            args = self.args(Path(d))
            with contextlib.redirect_stdout(io.StringIO()) as output:
                manifest = probe.run(args, rsp_class=FakeRsp, qmp_class=FakeQmp)
            self.assertEqual(manifest["populate_to_run_init_process_qemu_instructions"], 410000000)
            self.assertEqual([s["qemu_guest_instructions"]
                              for s in manifest["phase_samples"]], [70000000, 480000000])
            self.assertTrue(manifest["same_qemu_process"])
            self.assertFalse(manifest["cycle_accurate"])
            self.assertIn("G5_QEMU_ICOUNT_DIFFERENCE", output.getvalue())
            self.assertTrue((Path(args.out_dir) / "icount-manifest.json").is_file())
            self.assertEqual(len([c for c in FakeRsp.requests if c == "c"]), 2)
            self.assertEqual(len([c for c in FakeRsp.requests if c.startswith("z1,")]), 2)

    def test_nonmonotonic_count_rejected(self):
        FakeQmp.current_counts = [10, 10]
        with tempfile.TemporaryDirectory() as d:
            args = self.args(Path(d))
            with self.assertRaisesRegex(ProtocolError, "not monotonic"):
                probe.run(args, rsp_class=FakeRsp, qmp_class=FakeQmp)

    def test_missing_icount_rejected(self):
        FakeQmp.current_counts = [0, 12]
        with tempfile.TemporaryDirectory() as d:
            args = self.args(Path(d))
            with self.assertRaisesRegex(ProtocolError, "missing/zero"):
                probe.run(args, rsp_class=FakeRsp, qmp_class=FakeQmp)

    def test_qemu_not_frozen_rejected(self):
        FakeQmp.current_status = "running"
        with tempfile.TemporaryDirectory() as d:
            args = self.args(Path(d))
            with self.assertRaisesRegex(ProtocolError, "must start with -S"):
                probe.run(args, rsp_class=FakeRsp, qmp_class=FakeQmp)

    def test_bad_shift_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            args = self.args(Path(d))
            args.icount_shift = 17
            with self.assertRaisesRegex(ProtocolError, "icount-shift"):
                probe.run(args, rsp_class=FakeRsp, qmp_class=FakeQmp)

    def test_unrelated_sigtrap_pc_rejected(self):
        FakeRsp.force_bad_pc = True
        with tempfile.TemporaryDirectory() as d:
            args = self.args(Path(d))
            with self.assertRaisesRegex(ProtocolError, "stopped at"):
                probe.run(args, rsp_class=FakeRsp, qmp_class=FakeQmp)


if __name__ == "__main__":
    unittest.main()
