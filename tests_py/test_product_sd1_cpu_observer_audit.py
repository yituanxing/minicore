"""Unit tests: actual frozen Observer v1 layout and strict fail-closed checks."""
import io
import struct
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"tools/ci"))
from product_sd1_cpu_observer_audit import inspect,compare
from product_sd1_relay_state import FNV_OFFSET,FNV_PRIME


def fixture(cycles=812000000,retired=300,uart=b"Run /sbin/init as init process",
            last_cause=8,recent_count=2):
    def u(n):return struct.pack("<Q",n)
    def st(x):return u(len(x))+x
    fnv=FNV_OFFSET
    for c in uart:fnv=((fnv^c)*FNV_PRIME)&((1<<64)-1)
    counters=[cycles,retired,0,0,0,9,200,100,0,last_cause,0,
              0x3f88c9b9de,0,fnv,fnv]
    raw=(b"AETSDCP1"+u(1)+b"".join(st(b"a"*64) for _ in range(6))+
      st(b"")+st(b"")+u(0)+u(cycles)+u(0)+
      b"".join(u(v) for v in counters)+st(uart)+
      u(1)+u(0)+u(0)+u(0)+u(0)+u(0)+u(0)+u(0)+
      u(0)+u(0)+u(0)+u(0)+u(0)+u(max(0,cycles-100))+
      b"".join(u(v) for v in [0x3f88c9b9de,0xffffffff80006d9c]+[0]*14)+
      u(recent_count)+u(2))
    return io.BytesIO(raw)


class CpuObserverTest(unittest.TestCase):
    def test_u_ecall_does_not_certify_login(self):
        state=inspect(fixture())
        self.assertTrue(state["last_exception_is_user_ecall"])
        self.assertFalse(state["login_seen"])
        self.assertEqual(state["last_uart_age_cycles"],100)
        self.assertEqual(len(state["last_16_retired_pc"]),2)

    def test_activity_without_console_does_not_mean_pid1_success(self):
        before=inspect(fixture(cycles=1000,retired=50,uart=b""))
        after=inspect(fixture(cycles=1200,retired=80,uart=b"",last_cause=9))
        report=compare(before,after)
        self.assertEqual(report["retired_delta"],30)
        self.assertTrue(report["retirement_during_uart_silence"])
        self.assertFalse(report["last_exception_is_user_ecall"])
        self.assertFalse(report["login_seen"])

    def test_corrupted_pc_ring_rejected(self):
        with self.assertRaisesRegex(ValueError,"recent PC ring"):
            inspect(fixture(recent_count=17))

    def test_truncated_checkpoint_rejected(self):
        with self.assertRaisesRegex(ValueError,"truncated"):
            inspect(io.BytesIO(b"AETSDCP1"))


if __name__=="__main__":unittest.main()
