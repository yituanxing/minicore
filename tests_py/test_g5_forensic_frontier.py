from pathlib import Path
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "sim/opensbi_boot_main.cpp"
PRODUCT = ROOT / "src/main/scala/aethercore/core/product/ProductSmallPagedCore.scala"
CACHE = ROOT / "tools/ci/product_sim_cache.sh"
WORKFLOW = ROOT / ".github/workflows/product-g5-debian-rv64gc.yml"


class G5ForensicFrontierTest(unittest.TestCase):
    def test_manual_d2_forensic_never_repeats_speed_ab(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("product-g5-longrun", workflow)
        self.assertIn("AETHERCORE_G5_FORENSIC:", workflow)
        self.assertIn("AETHERCORE_G5_FORENSIC != '1'", workflow)
        self.assertIn("Run one restartable G5 D2 late-boot forensic session without performance A/B", workflow)
        self.assertIn("product_checkpoint_g5_short.sh diagnose", workflow)
        self.assertIn("build/product-g5-long-forensic/**", workflow)
        self.assertIn("build/rv64-debian-linux/obj/vmlinux", workflow)
        self.assertIn("build/rv64-debian-linux/obj/System.map", workflow)

    def test_debian_host_watchdog_header_invalidates_the_exact_model_cache(self):
        cache = CACHE.read_text()
        self.assertIn('"$sim_cxxflags" == *-DAETHERCORE_PRODUCT_DEBIAN*', cache)
        self.assertIn('model_specific_sources+=(sim/l32_g5_silence_watchdog.h)', cache)
        self.assertIn('"${model_specific_sources[@]}"', cache)

    def test_real_product_interrupt_pc_is_precise_trap_boundary(self):
        product = PRODUCT.read_text()
        self.assertIn("io.commit.interruptPc := Mux(interruptTake, pc, 0.U)", product)
        self.assertIn("csrFile.io.trapPc := Mux(interruptTake, pc,", product)

    def test_runner_diagnostic_is_opt_in_and_uses_the_trap_boundary_not_retire_pc(self):
        runner = RUNNER.read_text()
        self.assertIn("AETHERCORE_G5_STALL_WATCHDOG_CYCLES", runner)
        self.assertIn("lateBootWatchdog.observeUart(cycles, byte)", runner)
        self.assertIn("lateBootWatchdog.observeTimerInterrupt(", runner)
        self.assertIn("top.io_commit_interruptPc", runner)
        self.assertIn("L32_G5_LATE_BOOT_SILENCE_WATCHDOG", runner)
        self.assertIn("L32_G5_LATE_BOOT_PC_PROGRESS", runner)
        self.assertIn("classification=suspected-stalled-initcall-not-proven-deadlock", runner)
        self.assertIn("return 14;", runner)
        self.assertIn("#ifdef AETHERCORE_PRODUCT_DEBIAN", runner)

    def test_native_silent_watchdog_requires_exact_late_initcall_and_is_reset_by_uart(self):
        source = r'''
#include "l32_g5_silence_watchdog.h"
#include <cassert>
#include <sstream>
#include <string>

int main() {
  using aethercore::l32sim::G5SilenceWatchdog;
  G5SilenceWatchdog disabled(0);
  for (char c : std::string("clk: Disabling unused clocks")) disabled.observeUart(2, c);
  disabled.observeTimerInterrupt(0xabc);
  assert(!disabled.anchored() && !disabled.expired(100000));
  assert(disabled.timerEvents() == 0);

  G5SilenceWatchdog watchdog(100);
  for (char c : std::string("clk: Disabling unused clock")) watchdog.observeUart(2, c);
  assert(!watchdog.expired(100000)); // Not even a near-match can trigger.
  watchdog.observeUart(3, 's');
  assert(watchdog.anchored() && watchdog.anchorCycle() == 3);
  watchdog.observeUart(4, '\n');
  watchdog.observeTimerInterrupt(0x1234);
  watchdog.observeTimerInterrupt(0x5678);
  watchdog.observeTimerInterrupt(0x1234);
  assert(watchdog.timerEvents() == 3);
  assert(!watchdog.expired(103));
  assert(watchdog.expired(104));
  std::ostringstream output;
  watchdog.emit(output);
  assert(output.str().find("timer-boundary-pc-top=0x1234:2,0x5678:1") != std::string::npos);
  watchdog.observeUart(105, 'x'); // A newly active UART must rearm the budget.
  assert(watchdog.timerEvents() == 0);
  assert(!watchdog.expired(199));
  assert(watchdog.expired(205));
  return 0;
}
'''
        # Some CI hosts mount a very small/full /tmp; keep compiler temporaries
        # beside the checkout so the bounded native helper remains reproducible.
        with tempfile.TemporaryDirectory(prefix="g5-frontier-", dir=ROOT.parent) as directory:
            binary = Path(directory) / "test"
            build = subprocess.run(
                ["g++", "-std=c++20", "-Wall", "-Wextra", "-Werror", "-I", str(ROOT / "sim"), "-x", "c++", "-", "-o", str(binary)],
                input=source,
                text=True,
                capture_output=True,
                env={**os.environ, "TMPDIR": directory},
            )
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run([str(binary)], text=True, capture_output=True)
            self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == "__main__":
    unittest.main()
