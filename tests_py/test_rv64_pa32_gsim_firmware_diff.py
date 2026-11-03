"""OpenSBI/Linux firmware-prefix differential must be strict and repeatable."""
import importlib.util
import pathlib
import subprocess
import tempfile
import unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
DIFF=ROOT/"tools/ci/rv64_pa32_gsim_bare_diff.py"
spec=importlib.util.spec_from_file_location("gsim_firmware_diff",DIFF)
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class FirmwareDifferential(unittest.TestCase):
    def test_complete_digest_and_first_retire_must_match(self):
        commits=[("0x1000","0x00100293"),("0x1004","0x01f29293"),
                 ("0x1008","0x00028067")]
        def emit(engine, digest):
            lines=[f"AETHERCORE_PA32_{engine}_COMMIT index={i} pc={pc} "
                   f"inst={ins} exception=0 cause=0x0"
                   for i,(pc,ins) in enumerate(commits,1)]
            lines.append(f"AETHERCORE_PA32_{engine}_PREFIX guest_cycles=2000000 "
                         f"retires=100000 exceptions=0 interrupts=0 mtime=999992 "
                         f"last_pc=0x80000000 commit_digest={digest} "
                         "host_seconds=5.0 host_cps=400000")
            return "\n".join(lines)+"\n"
        with tempfile.TemporaryDirectory() as d:
            a=pathlib.Path(d)/"gsim"; b=pathlib.Path(d)/"verilator"
            a.write_text(emit("GSIM","0x1234"))
            b.write_text(emit("VERILATOR","0x1234"))
            m.compare(a,b,strict_bare=False)
            b.write_text(emit("VERILATOR","0x5678"))
            with self.assertRaises(ValueError):
                m.compare(a,b,strict_bare=False)

    def test_firmware_source_and_failure_contract(self):
        sh=ROOT/"tools/ci/rv64_pa32_gsim_firmware_diff.sh"
        subprocess.run(["bash","-n",str(sh)],check=True)
        text=sh.read_text()
        for token in ("rv64-linux-early","fw_payload.bin","2000000",
                      "--firmware","sha256sum","AetherCoreV2OpenSbiRV64SimTop"):
            self.assertIn(token,text)
        verifier=DIFF.read_text()
        self.assertIn("AETHERCORE_PA32_GSIM_VERILATOR_FIRMWARE_DIFF_PASS",verifier)
        ver=(ROOT/"sim/pa32_verilator_prefix_main.cpp").read_text()
        self.assertIn("memory.loadAtBase(argv[1])",ver)

if __name__=="__main__":
    unittest.main()
