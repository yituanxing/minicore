#!/usr/bin/env python3
"""Offline contract tests; no QEMU/PID1/RTL pass is fabricated."""

import hashlib
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools" / "ci"))
import product_sd1_qemu_bpf_icount_probe as probe


ATTR_ADDR = 0x100000
INSNS_ADDR = 0x200000
ENV_ADDR = 0xFFFFFFD80256C000
INSTRUCTIONS = bytes(i & 0xFF for i in range(82 * 8))
DIGEST = hashlib.sha256(INSTRUCTIONS).hexdigest()


class FakeRsp:
    def __init__(self):
        self.registers = {"pc": 0, "ra": 0, "a0": 0, "a1": 0}
        attr = bytearray(0x98)
        struct.pack_into("<IIQ", attr, 0, 18, 82, INSNS_ADDR)
        attr[0x30:0x38] = b"sd_bind6"
        struct.pack_into("<I", attr, 0x44, 9)
        env = bytearray(probe.VERIFIER_ENV_SIZE)
        struct.pack_into("<I", env, 0, 72)
        struct.pack_into("<I", env, 0x20, 1200)
        struct.pack_into("<I", env, 0x2DF8, 1)
        struct.pack_into("<I", env, 0x2E04, 19000)
        struct.pack_into("<I", env, 0x2E0C, 6500)
        struct.pack_into("<I", env, 0x2E1C, 690)
        struct.pack_into("<I", env, 0x2E20, 900)
        self.memory = [(ATTR_ADDR, bytes(attr)), (INSNS_ADDR, INSTRUCTIONS),
                       (ENV_ADDR, bytes(env))]
        self.stops = [
            {"pc": 0x8000, "ra": 0x9000, "a0": 0, "a1": ATTR_ADDR},
            {"pc": 0x8100, "ra": 0x8200, "a0": ENV_ADDR, "a1": 0},
            {"pc": 0x8200, "ra": 0, "a0": 0, "a1": 0},
            {"pc": 0x9000, "ra": 0, "a0": 0, "a1": 0},
        ]
        self.requests = []

    def xml(self, annex):
        return ("<target><feature><reg name='pc' regnum='32'/>"
                "<reg name='ra' regnum='1'/><reg name='a0' regnum='10'/>"
                "<reg name='a1' regnum='11'/></feature></target>")

    def reg(self, number):
        name = {32: "pc", 1: "ra", 10: "a0", 11: "a1"}[number]
        return self.registers[name]

    def request(self, packet):
        self.requests.append(packet)
        if packet == "?":
            return "S05"
        if packet.startswith(("Z1,", "z1,")):
            return "OK"
        if packet in ("c", "s"):
            self.registers.update(self.stops.pop(0))
            return "T05"
        if packet.startswith("m"):
            address, length = (int(part, 16) for part in packet[1:].split(","))
            for base, data in self.memory:
                if base <= address and address + length <= base + len(data):
                    return data[address-base:address-base+length].hex()
            return "E14"
        raise AssertionError(f"unexpected RSP packet {packet}")


class FakeQmp:
    def __init__(self):
        self.counts = iter((1000, 1900, 1999))

    def command(self, name):
        if name == "query-status":
            return {"status": "debug", "running": False}
        if name == "query-cpus-fast":
            return [{"cpu-index": 0}]
        if name == "query-replay":
            return {"mode": "none", "icount": next(self.counts)}
        raise AssertionError(name)


class QemuBpfProbeTest(unittest.TestCase):
    def test_exact_bytecode_guard(self):
        rsp = FakeRsp()
        good = probe.classify_bpf_attr(rsp, ATTR_ADDR, DIGEST)
        self.assertEqual(good["bytecode_sha256"], DIGEST)
        self.assertEqual(good["insn_count"], 82)
        with self.assertRaisesRegex(probe.ProtocolError, "not comparable"):
            probe.classify_bpf_attr(rsp, ATTR_ADDR, "0" * 64)

    def test_target_verifier_icount_mock(self):
        rsp = FakeRsp()
        qmp = FakeQmp()
        result = probe.probe(rsp, qmp,
                             {"bpf_check": 0x8000, "do_check_common": 0x8100},
                             DIGEST)
        self.assertEqual(result["program"]["name"], "sd_bind6")
        self.assertEqual(result["verifier_qemu_guest_instructions"], 999)
        self.assertEqual(result["verifier_counters_at_common_return"]["insn_processed"], 19000)
        self.assertTrue(result["verifier_accepted_program"])
        self.assertNotIn("whole_boot_percentage", result)
        self.assertFalse(any(p.startswith(("M", "X")) for p in rsp.requests))

    def test_bpf_attr_identity_rejects_foreign_program(self):
        rsp = FakeRsp()
        data = bytearray(rsp.memory[0][1])
        data[0x30:0x38] = b"sd_bind4"
        rsp.memory[0] = (ATTR_ADDR, bytes(data))
        self.assertIsNone(probe.classify_bpf_attr(rsp, ATTR_ADDR, DIGEST))

    def test_optional_post_uart_login_icount(self):
        class DetachedRsp:
            def request(self, packet):
                self.packet = packet
                return "OK"

        class LoginQmp:
            def __init__(self):
                self.stopped = False

            def command(self, name):
                if name == "query-status":
                    return {"running": not self.stopped}
                if name == "stop":
                    self.stopped = True
                    return {}
                if name == "query-replay":
                    return {"icount": 5000}
                raise AssertionError(name)

        rsp = DetachedRsp()
        qmp = LoginQmp()
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "serial.log"
            log.write_bytes(b"VFS: Mounted root (ext4 filesystem)\r\n"
                            b"Run /sbin/init as init process\r\n"
                            b"localhost login: ")
            result = probe.observe_login_after_detach(rsp, qmp, log, 2, 2000)
        self.assertEqual(rsp.packet, "D")
        self.assertTrue(qmp.stopped)
        self.assertEqual(result["qemu_guest_instructions_after_login_uart"], 5000)
        self.assertEqual(result["sample_kind"], "QMP_STOP_AFTER_UART_OBSERVATION_NONATOMIC")


if __name__ == "__main__":
    unittest.main()
