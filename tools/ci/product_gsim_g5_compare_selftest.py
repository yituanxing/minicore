#!/usr/bin/env python3
"""No guest cycles: prove G5 strict differential rejects missing FP state."""

from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/ci/product_gsim_g5_compare.py"
CYCLES = 30_000_000
STATE = (
    "L32_OPENSBI_TIMEOUT cycles=30000000 commits=123 compressed=12 "
    "opensbi-compressed=3 linux-compressed=9 uart-bytes=30 uart-fingerprint=0x11 "
    "banner=1 milestone=0 exceptions=2 interrupts=3 stip=2 seip=1 "
    "mtime=0x1234 mtimecmp=0x5678 last-exception-pc=0x80200000 "
    "last-exception-cause=0x9 last-exception-value=0x0 ram-fingerprint=0x123 "
    "recent-pcs=0x80200000,0x80200004 "
    "g5-retire-digest=0x111 g5-int-digest=0x222 g5-fp-digest=0x333 "
    "g5-fp-writes=2 g5-fflags-events=1\n"
)
DETAIL = (
    "AETHERCORE_PRODUCT_GSIM_PREFIX guest_cycles=29999996 "
    "host_bridge_calls=300 host_imem_calls=100 "
    "trace_mode=strict-same-cycle commit_digest=0x111 wire_digest=0x444\n"
)


def check(ver_state: str, gsim_state: str, expected: bool, reason: str) -> None:
    with tempfile.TemporaryDirectory(prefix="g5-gsim-diff-") as temp:
        root = Path(temp)
        v = root / "v.log"
        g = root / "g.log"
        v.write_text("OpenSBI v1.6\n" + ver_state)
        g.write_text("OpenSBI v1.6\nAETHERCORE_PRODUCT_GSIM_HOST_BRIDGE_ENABLED "
                     "actual_top=AetherCoreProductDebianRV64SimTop pa_bits=56\n" +
                     gsim_state + DETAIL)
        result = subprocess.run(["python3", str(SCRIPT), "--verilator", str(v),
                                 "--gsim", str(g), "--cycles", str(CYCLES)],
                                capture_output=True, text=True, check=False)
        if (result.returncode == 0) != expected:
            raise AssertionError(f"{reason}: rc={result.returncode} stdout={result.stdout} stderr={result.stderr}")


check(STATE, STATE, True, "positive")
check(STATE, STATE.replace("g5-fp-digest=0x333", "g5-fp-digest=0x334"),
      False, "floating-state mismatch")
check(STATE, STATE.replace("g5-fp-digest=0x333 ", ""),
      False, "missing floating-state trace")
check(STATE, STATE.replace("ram-fingerprint=0x123", "ram-fingerprint=0x124"),
      False, "DDR mismatch")
check(STATE, STATE.replace("cycles=30000000", "cycles=29999999"),
      False, "wrong cycle")
print("PRODUCT_GSIM_G5_COMPARE_SELFTEST_PASS positive=1 fp_divergence=1 fp_missing=1 ram_divergence=1 wrong_cycle=1 guest_cycles=0")
