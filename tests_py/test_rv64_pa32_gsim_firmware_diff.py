"""Real firmware prefix: first differing checkpoint and full RAM state must fail."""
import importlib.util
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
DIFF = ROOT / "tools/ci/rv64_pa32_gsim_bare_diff.py"
spec = importlib.util.spec_from_file_location("firmware_diff", DIFF)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def fixture(engine, digest="0x1234", ram="0x5678", checkpoint="0xabcd"):
    commits = [("0x1000", "0x00100293"), ("0x1004", "0x01f29293"),
               ("0x1008", "0x00028067")]
    lines = [
        f"AETHERCORE_PA32_{engine}_COMMIT index={i} pc={pc} "
        f"inst={inst} exception=0 cause=0x0"
        for i, (pc, inst) in enumerate(commits, 1)
    ]
    lines.append(
        f"AETHERCORE_PA32_{engine}_CHECKPOINT cycle=100000 retires=50000 "
        f"exceptions=0 interrupts=0 mtime=49992 "
        f"last_pc=0x80000100 commit_digest={checkpoint}"
    )
    lines.append(
        f"AETHERCORE_PA32_{engine}_PREFIX guest_cycles=2000000 "
        f"retires=100000 exceptions=0 interrupts=0 mtime=999992 "
        f"last_pc=0x80000100 commit_digest={digest} ram_digest={ram} "
        "host_seconds=5.0 host_cps=400000"
    )
    return "\n".join(lines) + "\n"

class FirmwareDifferential(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.gs = pathlib.Path(self.dir.name) / "gsim"
        self.vl = pathlib.Path(self.dir.name) / "verilator"
        self.gs.write_text(fixture("GSIM"))
        self.vl.write_text(fixture("VERILATOR"))

    def test_matching_firmware_checkpoint_and_ram(self):
        module.compare(self.gs, self.vl, strict_bare=False)

    def test_mismatched_retirement_digest_rejected(self):
        self.vl.write_text(fixture("VERILATOR", digest="0x9999"))
        with self.assertRaisesRegex(ValueError, "commit_digest"):
            module.compare(self.gs, self.vl, strict_bare=False)

    def test_mismatched_ram_digest_rejected(self):
        self.vl.write_text(fixture("VERILATOR", ram="0x9999"))
        with self.assertRaisesRegex(ValueError, "ram_digest"):
            module.compare(self.gs, self.vl, strict_bare=False)

    def test_first_checkpoint_mismatch_localizes_cycle(self):
        self.vl.write_text(fixture("VERILATOR", checkpoint="0x9999"))
        with self.assertRaisesRegex(ValueError, "FIRST_CHECKPOINT_DIVERGENCE.*cycle=100000"):
            module.compare(self.gs, self.vl, strict_bare=False)

    def test_missing_checkpoints_fail_closed(self):
        self.vl.write_text(
            "\n".join(x for x in fixture("VERILATOR").splitlines()
                      if "_CHECKPOINT " not in x) + "\n"
        )
        with self.assertRaisesRegex(ValueError, "requires periodic checkpoints"):
            module.compare(self.gs, self.vl, strict_bare=False)

    def test_firmware_source_and_benchmark_contract(self):
        sh = ROOT / "tools/ci/rv64_pa32_gsim_firmware_diff.sh"
        subprocess.run(["bash", "-n", str(sh)], check=True)
        src = sh.read_text()
        for token in ("rv64-linux-early", "fw_payload.bin", "2000000",
                      "--firmware", "sha256sum", "AetherCoreV2OpenSbiRV64SimTop",
                      "900s"):
            self.assertIn(token, src)
        verifier = DIFF.read_text()
        self.assertIn("AETHERCORE_PA32_GSIM_VERILATOR_FIRMWARE_DIFF_PASS", verifier)
        self.assertIn("FIRST_CHECKPOINT_DIVERGENCE", verifier)
        ver = (ROOT / "sim/pa32_verilator_prefix_main.cpp").read_text()
        gs = (ROOT / "sim/pa32_gsim_prefix_main.cpp").read_text()
        mem = (ROOT / "sim/l32_opensbi_runtime.h").read_text()
        for port in ("memory.loadAtBase(argv[1])",
                     "AETHERCORE_PA32_VERILATOR_CHECKPOINT",
                     "memory.stateDigest()"):
            self.assertIn(port, ver)
        for port in ("AETHERCORE_PA32_GSIM_CHECKPOINT", "memory.stateDigest()"):
            self.assertIn(port, gs)
        self.assertIn("std::uint64_t stateDigest() const", mem)

if __name__ == "__main__":
    unittest.main()
