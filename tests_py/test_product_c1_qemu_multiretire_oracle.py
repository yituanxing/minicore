"""QEMU-only live single-step oracle guard tests; no CPU RTL is executed."""

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
MOD_DIR = ROOT / "tools/ci/product_g5_qemu_export"
sys.path.insert(0, str(MOD_DIR))
SPEC = importlib.util.spec_from_file_location("qemu_multiretire_oracle", MOD_DIR / "qemu_multiretire_oracle.py")
ORACLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ORACLE)


def state(pc, sp, mip="0x0"):
    values = {f"x{i}": "0x0" for i in range(32)}
    values.update({"pc": hex(pc), "priv": "0x1", "x2": hex(sp),
                   "mstatus": "0xa000000a2", "satp": "0x80000000000856fe", "mip": mip})
    return values


class FakeRsp:
    def __init__(self, snapshots, mode="0"):
        self.snapshots = snapshots
        self.index = 0
        self.calls = []
        self.mode = mode
        self.names = ["pc", "priv", "mstatus", "satp", "mip"] + [f"x{i}" for i in range(32)]

    def xml(self, name):
        assert name == "target.xml"
        return "<target><feature name='org.gnu.gdb.riscv.cpu'>" + "".join(
            f"<reg name='{name}' regnum='{i}' bitsize='64'/>"
            for i, name in enumerate(self.names)) + "</feature></target>"

    def reg(self, index):
        return int(self.snapshots[self.index][self.names[index]], 16)

    def request(self, packet):
        self.calls.append(packet)
        if packet.startswith("z1,"):
            return "OK"
        if packet == "qqemu.PhyMemMode":
            return self.mode
        if packet == "Qqemu.PhyMemMode:0":
            self.mode = "0"
            return "OK"
        if packet.startswith("m"):
            if self.index == 0:
                return "7971"
            return "1300" if packet.endswith(",2") else "13000000"
        if packet == "s":
            self.index += 1
            return "T05thread:1;"
        raise AssertionError(f"unexpected packet {packet}")


class FakeQmp:
    def command(self, op):
        if op == "query-status":
            return {"running": False, "status": "debug"}
        if op == "query-cpus-fast":
            return [{"cpu-index": 0}]
        raise AssertionError(op)


class MultiretireOracleTests(unittest.TestCase):
    def setUp(self):
        self.initial = state(ORACLE.SOURCE_PC, 0xffffffc80000bec0)
        self.one = state(ORACLE.SOURCE_PC + 2, ORACLE.EXPECTED_SP_AFTER)
        self.two = state(ORACLE.SOURCE_PC + 6, ORACLE.EXPECTED_SP_AFTER)
        self.source = {"qemu_gdb_registers": self.initial}

    def test_two_real_instruction_shapes_from_mock_gdb(self):
        rsp = FakeRsp([self.initial, self.one, self.two])
        result = ORACLE.record(rsp, FakeQmp(), self.source, 2)
        entries = result["entries"]
        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0]["instruction"]["opcode"], "0x7179")
        self.assertEqual(entries[0]["instruction"]["instruction_length"], 2)
        self.assertEqual(entries[0]["gpr_changes"], {"x2": hex(ORACLE.EXPECTED_SP_AFTER)})
        self.assertEqual(entries[1]["instruction"]["opcode"], "0x00000013")
        self.assertEqual(entries[1]["instruction"]["instruction_length"], 4)
        self.assertEqual(entries[1]["pc_after"], hex(ORACLE.SOURCE_PC + 6))
        self.assertEqual(rsp.calls.count("s"), 2)

    def test_unpaired_live_qemu_source_is_rejected(self):
        source = {"qemu_gdb_registers": dict(self.initial, x2="0x8")}
        with self.assertRaisesRegex(ORACLE.ProtocolError, "paired RAM export"):
            ORACLE.record(FakeRsp([self.initial]), FakeQmp(), source, 1)

    def test_qemu_physical_debug_memory_mode_is_switched_to_virtual(self):
        rsp = FakeRsp([self.initial, self.one], mode="1")
        ORACLE.record(rsp, FakeQmp(), self.source, 1)
        self.assertIn("Qqemu.PhyMemMode:0", rsp.calls)
        self.assertEqual(rsp.mode, "0")

    def test_interrupt_boundary_rejected_early(self):
        interrupt = state(ORACLE.SOURCE_PC + 2, ORACLE.EXPECTED_SP_AFTER, "0x20")
        with self.assertRaisesRegex(ORACLE.ProtocolError, "pending interrupt"):
            ORACLE.record(FakeRsp([self.initial, interrupt]), FakeQmp(), self.source, 1)

    def test_count_and_one_hart_guards(self):
        with self.assertRaisesRegex(ORACLE.ProtocolError, "1..256"):
            ORACLE.record(FakeRsp([self.initial]), FakeQmp(), self.source, 257)
        class TwoHarts(FakeQmp):
            def command(self, op):
                if op == "query-cpus-fast":
                    return [{"cpu-index": 0}, {"cpu-index": 1}]
                return super().command(op)
        with self.assertRaisesRegex(ORACLE.ProtocolError, "one hart"):
            ORACLE.record(FakeRsp([self.initial]), TwoHarts(), self.source, 1)

    def test_end_of_window_ddr_patch_never_overwrites_source(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            before = bytes(ORACLE.PAGE_SIZE * 2)
            changed = bytearray(before)
            changed[4:7] = b"abc"
            original = root / "snapshot.bin"
            temp = root / "after.tmp"
            original.write_bytes(before)
            class MemoryQmp:
                def command(self, op, args=None):
                    assert op == "pmemsave"
                    assert args["size"] == len(before)
                    Path(args["filename"]).write_bytes(changed)
            with mock.patch.object(ORACLE, "RAM_SIZE", len(before)):
                result = ORACLE.memory_delta(MemoryQmp(), original, temp)
            self.assertFalse(temp.exists())
            self.assertEqual(original.read_bytes(), before)
            self.assertEqual(result["modified_bytes"], 3)
            self.assertEqual(result["modified_pages"][0]["physical_page"], "0x80000000")
            self.assertEqual(result["modified_pages"][0]["ranges"],
                             [{"offset": 4, "old_hex": "000000", "new_hex": "616263"}])

    def test_opt_in_only_and_no_rtl_claim(self):
        shell = (MOD_DIR / "fast_replay_from_stage.sh").read_text()
        workflow = (ROOT / ".github/workflows/product-c1-qemu-multiretire-oracle.yml").read_text()
        source = (MOD_DIR / "qemu_multiretire_oracle.py").read_text()
        self.assertIn('if [[ -n "${AETHERCORE_C1_FAST_QEMU_ORACLE_RETIRE_COUNT:-}" ]]', shell)
        self.assertIn("PRODUCT_C1_QEMU_SINGLESTEP_ORACLE_CAPTURED_NOT_RTL_DIFFTEST", source)
        self.assertIn("real_product_rtl_retire_count", source)
        self.assertIn("full_device_and_memory_diff_test", source)
        self.assertIn("experiment/c1-arch-handoff-20261108", workflow)
        self.assertIn("AETHERCORE_C1_FAST_QEMU_CPU: 'rv64,sv48=off,sv57=off,h=off'", workflow)
        self.assertNotIn("SD1_FULL_DEBIAN_SIGNED_RELAY", workflow)


if __name__ == "__main__":
    unittest.main()
