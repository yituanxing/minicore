"""Live PA32 engine progress, shared observer, exit and timeout behavior."""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
RUN = ROOT / "tools/ci/rv64_pa32_sim_run.sh"

class SimProgressTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = pathlib.Path(self.tmp.name)
        self.exe = self.dir / "pa32-gsim-prefix"
        self.firmware = self.dir / "firmware.bin"
        self.firmware.write_bytes(b"firmware")
    def fake(self, script):
        self.exe.write_text("#!/usr/bin/env bash\nset -e\n" + script)
        self.exe.chmod(0o755)
    def env(self):
        return dict(os.environ, GSIM_OUT=str(self.dir),
                    SIM_LOG=str(self.dir / "engine.log"),
                    SIM_TIMEOUT_SECONDS="2")
    def test_wiring_and_syntax(self):
        subprocess.run(["bash", "-n", str(RUN)], check=True)
        sh = (ROOT / "tools/ci/rv64_pa32_gsim_firmware_diff.sh").read_text()
        self.assertIn("rv64_pa32_sim_run.sh", sh)
        for path in ("pa32_gsim_prefix_main.cpp", "pa32_verilator_prefix_main.cpp"):
            src = (ROOT / "sim" / path).read_text()
            self.assertIn('#include "pa32_sim_progress.h"', src)
            self.assertIn("progress.observe(", src)
            self.assertIn("progress.finish(", src)
    def test_progress_streams_before_exit(self):
        self.fake("echo 'AETHERCORE_PA32_SIM_PROGRESS engine=GSIM cycles=100'\n"
                  "sleep 0.6\n"
                  "echo 'AETHERCORE_PA32_SIM_STAGE engine=GSIM status=END'\n")
        proc = subprocess.Popen(
            ["bash", str(RUN), "gsim", str(self.firmware), "1000"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=self.env())
        self.addCleanup(lambda: proc.kill() if proc.poll() is None else None)
        self.assertIn("status=START", proc.stdout.readline())
        self.assertIn("SIM_PROGRESS", proc.stdout.readline())
        self.assertIsNone(proc.poll(), "heartbeat must precede engine completion")
        out, err = proc.communicate(timeout=4)
        self.assertEqual(proc.returncode, 0, err)
        self.assertIn("status=END", out)
        self.assertIn("SIM_PROGRESS", (self.dir / "engine.log").read_text())
    def test_nonzero_exit_preserved(self):
        self.fake("echo 'AETHERCORE_PA32_SIM_PROGRESS engine=GSIM cycles=1'\nexit 9\n")
        r = subprocess.run(["bash", str(RUN), "gsim", str(self.firmware), "1000"],
                           text=True, capture_output=True, env=self.env())
        self.assertEqual(r.returncode, 9)
        self.assertIn("status=FAIL exit=9", r.stderr)
    def test_timeout_preserved(self):
        self.fake("sleep 3\n")
        env = self.env()
        env["SIM_TIMEOUT_SECONDS"] = "1"
        r = subprocess.run(["bash", str(RUN), "gsim", str(self.firmware), "1000"],
                           text=True, capture_output=True, env=env, timeout=5)
        self.assertEqual(r.returncode, 124)
        self.assertIn("status=FAIL exit=124", r.stderr)
    def test_clang_native_predefined_linux_macro(self):
        clang = shutil.which("clang++-19") or shutil.which("clang++")
        if not clang:
            self.skipTest("Clang not installed")
        source = '#include "pa32_sim_progress.h"\nint main(){return 0;}\n'
        subprocess.run([clang, "-std=gnu++20", "-O3", "-march=native",
                        "-Dlinux=1", "-Werror", "-fsyntax-only", "-x", "c++",
                        "-", "-I", str(ROOT / "sim")],
                       input=source, text=True, check=True, capture_output=True)

    def test_heartbeat_preserves_complete_retirement_records(self):
        cxx = shutil.which("c++")
        if not cxx:
            self.skipTest("no C++ compiler")
        src = self.dir / "coherent.cpp"
        src.write_text(r"""
#include "pa32_sim_progress.h"
#include <chrono>
#include <cstdio>
int main() {
  aethercore::pa32sim::FirmwareMilestone fw;
  std::puts("AETHERCORE_PA32_GSIM_COMMIT index=5 pc=0x80000004 inst=0x02a00113 exception=0 cause=0x0");
  aethercore::pa32sim::SimProgress p("GSIM", 100, false,
      std::chrono::steady_clock::now());
  p.observe(100, 5, fw);
}
""")
        exe = self.dir / "coherent"
        subprocess.run([cxx, "-std=c++20", "-O2", "-Dlinux=1", "-I",
                        str(ROOT / "sim"), str(src), "-o", str(exe)],
                       check=True, text=True, capture_output=True)
        r = subprocess.run([str(exe)], check=True, text=True,
                           capture_output=True,
                           env={**os.environ, "AETHERCORE_SIM_PROGRESS_CYCLES": "100"})
        self.assertIn("AETHERCORE_PA32_GSIM_COMMIT index=5 pc=0x80000004 "
                      "inst=0x02a00113 exception=0 cause=0x0", r.stdout)
        self.assertIn("AETHERCORE_PA32_SIM_PROGRESS engine=GSIM", r.stdout)
        self.assertEqual(r.stderr, "")

    def test_shared_observer_compiles_and_reports(self):
        cxx = shutil.which("c++")
        if not cxx:
            self.skipTest("no C++ compiler")
        src = self.dir / "prog.cpp"
        src.write_text(r"""
#include "pa32_sim_progress.h"
#include <chrono>
int main() {
  aethercore::pa32sim::FirmwareMilestone fw;
  aethercore::pa32sim::SimProgress p("GSIM", 100, true,
      std::chrono::steady_clock::now());
  const char* uart = "OpenSBI v1.6\nLinux version 6.6.143\nRV64 USER UART IRQ OK\n";
  for (const char* c = uart; *c; ++c) fw.observeUart(true, *c, 1);
  fw.observeInterrupt(true, 0x8000000000000005ULL);
  fw.observeInterrupt(true, 0x8000000000000009ULL);
  p.observe(5, 2, fw);
  p.finish(9, 4, fw);
}
""")
        exe = self.dir / "prog"
        subprocess.run([cxx, "-std=c++20", "-O2", "-Dlinux=1", "-I", str(ROOT / "sim"),
                        str(src), "-o", str(exe)], check=True,
                       capture_output=True, text=True)
        r = subprocess.run([str(exe)], capture_output=True, text=True,
                           check=True, env={**os.environ,
                               "AETHERCORE_SIM_PROGRESS_CYCLES": "5"})
        for token in ("name=OPENSBI", "name=LINUX", "name=PID1",
                      "name=STIP", "name=SEIP", "cycles=5", "status=END"):
            self.assertIn(token, r.stdout)

if __name__ == "__main__":
    unittest.main()
