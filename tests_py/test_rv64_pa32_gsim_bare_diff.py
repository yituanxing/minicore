"""Deterministic GSIM/Verilator real RV64I retirement differential fixtures."""
import importlib.util
import pathlib
import subprocess
import tempfile
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
DIFF=ROOT/"tools/ci/rv64_pa32_gsim_bare_diff.py"
spec=importlib.util.spec_from_file_location("bare_diff",DIFF)
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

COMMITS = [
    ("0x1000","0x00100293"), ("0x1004","0x01f29293"),
    ("0x1008","0x00028067"), ("0x80000000","0x00100093"),
    ("0x80000004","0x00208113"), ("0x80000008","0x00310193"),
    ("0x8000000c","0x0000006f"),
]

def make_log(engine, changed=False):
    lines=[]
    for i,(pc,inst) in enumerate(COMMITS,1):
        if changed and i==5: inst="0x00000000"
        lines.append(f"AETHERCORE_PA32_{engine}_COMMIT "
                     f"index={i} pc={pc} inst={inst} exception=0 cause=0x0")
    lines.append(f"AETHERCORE_PA32_{engine}_PREFIX "
                 "guest_cycles=20000 retires=7 exceptions=0 interrupts=0 "
                 "mtime=9992 last_pc=0x8000000c commit_digest=0xdeadbeef "
                 "ram_digest=0x45 uart_bytes=0 uart_digest=0xabc "
                 "opensbi_banner=0 linux_banner=0 milestone_cycle=0 stip=0 seip=0 "
                 "host_seconds=1.0 host_cps=20000")
    return "\n".join(lines)+"\n"

class RealBareDifferential(unittest.TestCase):
    def test_accepts_exact_architecture(self):
        with tempfile.TemporaryDirectory() as d:
            a=pathlib.Path(d)/"g";b=pathlib.Path(d)/"v"
            a.write_text(make_log("GSIM"));b.write_text(make_log("VERILATOR"))
            m.compare(a,b)

    def test_detects_real_instruction_divergence(self):
        with tempfile.TemporaryDirectory() as d:
            a=pathlib.Path(d)/"g";b=pathlib.Path(d)/"v"
            a.write_text(make_log("GSIM"));b.write_text(make_log("VERILATOR",True))
            with self.assertRaises(ValueError):
                m.compare(a,b)

    def test_pinned_build_scripts(self):
        subprocess.run(["bash","-n",str(ROOT/"tools/ci/rv64_pa32_gsim_bare_diff.sh")],check=True)
        content=(ROOT/"tools/ci/rv64_pa32_gsim_bare_diff.sh").read_text()
        for marker in ("5.054","--compiler clang","sha256sum",
                       "AETHERCORE_SIM_ADAPTIVE_SETTLE",
                       "ElaborateV2VirtualFpgaBoardPA32RV64",
                       "rv64_pa32_gsim_bare_diff.py"):
            self.assertIn(marker,content)

if __name__=="__main__":
    unittest.main()
