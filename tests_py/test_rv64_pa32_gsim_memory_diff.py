"""RV64I+A real host-RAM side-effect differential contracts."""
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools/ci"))
import rv64_pa32_gsim_memory_diff as diff

OPS = [
    "0x00000097", "0x02a00113", "0x08008393", "0x0023a023",
    "0x0003a183", "0x00118213", "0x1003a2af", "0x1843a32f",
    "0x0003a403", "0x0023a4af", "0x0003a503", "0x0000006f",
]
def sample(engine, ram="0x123456", success=True):
    commits=[("0x1000","0x00100293"),("0x1004","0x01f29293"),
             ("0x1008","0x00028067")]
    commits.extend((f"0x{0x80000000+i*4:x}", op) for i,op in enumerate(OPS))
    lines=[
        f"AETHERCORE_PA32_{engine}_COMMIT index={i} pc={pc} "
        f"inst={inst} exception=0 cause=0x0"
        for i,(pc,inst) in enumerate(commits,1)
    ]
    lines.append(f"AETHERCORE_PA32_{engine}_PREFIX "
                 "guest_cycles=20000 retires=9987 exceptions=0 interrupts=0 "
                 f"mtime=9992 last_pc=0x8000002c commit_digest=0x9988 ram_digest={ram} "
                 "uart_bytes=0 uart_digest=0xabc opensbi_banner=0 linux_banner=0 "
                 "milestone_cycle=0 stip=0 seip=0 "
                 "host_seconds=1.0 host_cps=20000")
    if success:
        lines.append(f"AETHERCORE_PA32_{engine}_MEMORY_PASS final_word=85")
    return "\n".join(lines)+"\n"

class MemoryDifferential(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.a=pathlib.Path(self.temp.name)/"g"
        self.b=pathlib.Path(self.temp.name)/"v"
        self.a.write_text(sample("GSIM"))
        self.b.write_text(sample("VERILATOR"))

    def test_correct_memory_atomic_sequence(self):
        diff.compare(self.a,self.b)

    def test_different_final_ram_rejected(self):
        self.b.write_text(sample("VERILATOR",ram="0xdead"))
        with self.assertRaisesRegex(ValueError,"ram_digest mismatch"):
            diff.compare(self.a,self.b)

    def test_missing_atomic_side_effect_rejected(self):
        self.b.write_text(sample("VERILATOR",success=False))
        with self.assertRaisesRegex(ValueError,"expected 42->43->85"):
            diff.compare(self.a,self.b)

    def test_code_contains_identical_instruction_vector(self):
        g=(ROOT/"sim/pa32_gsim_prefix_main.cpp").read_text()
        v=(ROOT/"sim/pa32_verilator_prefix_main.cpp").read_text()
        for op in OPS:
            self.assertIn(op.replace("0x","0x")+"U",g)
            self.assertIn(op.replace("0x","0x")+"U",v)
        self.assertIn("memoryProbe",g)
        self.assertIn("memoryProbe",v)
        for engine,file in (("GSIM",g),("VERILATOR",v)):
            self.assertIn(f"AETHERCORE_PA32_{engine}_MEMORY_PASS",file)
            self.assertIn("finalWord != 85",file)

    def test_scripts_syntax_and_strict_marker(self):
        for filename in ("rv64_pa32_gsim_memory_diff.sh",
                         "rv64_pa32_gsim_firmware_diff.sh"):
            subprocess.run(["bash","-n",str(ROOT/"tools/ci"/filename)],check=True)
        source=(ROOT/"tools/ci/rv64_pa32_gsim_memory_diff.sh").read_text()
        self.assertIn("AETHERCORE_PA32_GSIM_MEMORY_PASS final_word=85",source)
        self.assertIn("AETHERCORE_PA32_VERILATOR_MEMORY_PASS final_word=85",source)

if __name__=="__main__":
    unittest.main()
