"""Opt-in GSIM phase sampling must not affect guest semantics or output parsing."""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class GsimPhaseProfileTests(unittest.TestCase):
    def test_compiles_with_clang_linux_macro_and_reports_exact_phases(self):
        compiler = shutil.which("clang++-19") or shutil.which("clang++") or shutil.which("c++")
        if not compiler:
            self.skipTest("C++ compiler not available")
        with tempfile.TemporaryDirectory() as d:
            src = pathlib.Path(d) / "phase.cpp"
            exe = pathlib.Path(d) / "phase"
            src.write_text(r"""
#include "pa32_gsim_phase_profile.h"
#include <chrono>
int main() {
  using P = aethercore::pa32sim::GsimPhaseProfile;
  P p;
  const char* flag = std::getenv("AETHERCORE_GSIM_PHASE_PROFILE");
  const bool enabled = flag && std::strcmp(flag, "1") == 0;
  if (enabled && (!p.sample(4093, false) || !p.sample(8186, false))) return 3;
  if (!enabled && p.sample(4093, false)) return 5;
  if (p.sample(4093, true) || p.sample(1, false)) return 4;
  auto a = P::TimePoint(std::chrono::nanoseconds(10));
  auto b = P::TimePoint(std::chrono::nanoseconds(20));
  auto c = P::TimePoint(std::chrono::nanoseconds(50));
  auto e = P::TimePoint(std::chrono::nanoseconds(60));
  if (p.sample(4093, false)) p.record(a,b,c,e);
  if (p.sample(8186, false)) p.record(a,b,c,e);
  p.report(8192);
}
""")
            subprocess.run([compiler, "-std=gnu++20", "-O3", "-Werror", "-Dlinux=1",
                            "-I", str(ROOT / "sim"), str(src), "-o", str(exe)],
                           check=True, text=True, capture_output=True)
            normal = subprocess.run([str(exe)], check=False, text=True,
                                    capture_output=True, env={**os.environ,
                                        "AETHERCORE_GSIM_PHASE_PROFILE": "0"})
            self.assertEqual(normal.returncode, 0)
            self.assertNotIn("PHASE_PROFILE", normal.stdout)
            enabled = subprocess.run([str(exe)], check=True, text=True,
                                     capture_output=True, env={**os.environ,
                                        "AETHERCORE_GSIM_PHASE_PROFILE": "1"})
            self.assertIn("guest_cycles=8192", enabled.stdout)
            self.assertIn("samples=2", enabled.stdout)
            self.assertIn("request_ns=20 model_ns=60 observe_ns=20", enabled.stdout)
            self.assertIn("request_pct=20.00 model_pct=60.00 observe_pct=20.00",
                          enabled.stdout)
            self.assertIn("model_includes_host_bridge=1", enabled.stdout)
            self.assertEqual(enabled.stderr, "")
            invalid = subprocess.run([str(exe)], text=True, capture_output=True,
                                     env={**os.environ,
                                         "AETHERCORE_GSIM_PHASE_PROFILE": "broken"})
            self.assertNotEqual(invalid.returncode, 0)

    def test_runner_and_ci_use_opt_in_only(self):
        source = (ROOT / "sim/pa32_gsim_prefix_main.cpp").read_text()
        self.assertIn("phaseProfile.record(", source)
        self.assertIn("phaseProfile.report(executedCycles);", source)
        self.assertIn("const bool bareProbe =", source)
        self.assertNotIn('if (std::string(argv[1]) == "--bare"', source)
        workflow = (ROOT / ".github/workflows/rv64-pa32-gsim-host-bridge.yml").read_text()
        self.assertIn("AETHERCORE_GSIM_PHASE_PROFILE=1 CYCLES=20000000", workflow)
        self.assertIn("tests_py.test_rv64_pa32_gsim_phase_profile", workflow)
        self.assertIn("sim/pa32_gsim_phase_profile.h", workflow)
        self.assertIn("AETHERCORE_PA32_GSIM_PHASE_PROFILE", (
            ROOT / "tools/ci/rv64_pa32_sim_run.sh").read_text())

if __name__ == "__main__":
    unittest.main()
