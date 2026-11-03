"""Real firmware prefix: first differing checkpoint and full RAM state must fail."""
import importlib.util
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
DIFF = ROOT / "tools/ci/rv64_pa32_gsim_bare_diff.py"
spec = importlib.util.spec_from_file_location("firmware_diff", DIFF)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def fixture(engine, digest="0x1234", ram="0x5678", checkpoint="0xabcd",
            linux=False, uart="0x8888"):
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
        f"uart_bytes={100 if linux else 0} uart_digest={uart} "
        f"opensbi_banner={int(linux)} linux_banner={int(linux)} "
        f"milestone_cycle={1900000 if linux else 0} "
        f"stip={2 if linux else 0} seip={1 if linux else 0} "
        "host_seconds=5.0 host_cps=400000"
    )
    if linux:
        lines.append(f"AETHERCORE_PA32_{engine}_LINUX_MILESTONE_PASS "
                     "cycles=1900000 marker=RV64_USER_UART_IRQ_OK stip=2 seip=1")
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

    def test_full_linux_requires_pid1_and_interrupts(self):
        self.gs.write_text(fixture("GSIM", linux=True))
        self.vl.write_text(fixture("VERILATOR", linux=True))
        module.compare(self.gs, self.vl, strict_bare=False, require_linux=True)
        self.vl.write_text(fixture("VERILATOR"))
        with self.assertRaisesRegex(ValueError, "milestone_cycle|field"):
            module.compare(self.gs, self.vl, strict_bare=False, require_linux=True)

    def test_full_linux_uart_difference_rejected(self):
        self.gs.write_text(fixture("GSIM", linux=True))
        self.vl.write_text(fixture("VERILATOR", linux=True, uart="0x9999"))
        with self.assertRaisesRegex(ValueError, "uart_digest"):
            module.compare(self.gs, self.vl, strict_bare=False, require_linux=True)

    def test_full_linux_missing_marker_rejected_even_if_terminal_state_matches(self):
        self.gs.write_text(fixture("GSIM", linux=True))
        self.vl.write_text(fixture("VERILATOR", linux=True).replace(
            "AETHERCORE_PA32_VERILATOR_LINUX_MILESTONE_PASS", "MISSING"))
        with self.assertRaisesRegex(ValueError, "one PID1 milestone"):
            module.compare(self.gs, self.vl, strict_bare=False, require_linux=True)

    def test_shared_linux_observer_recognizes_real_stream_and_interrupt_causes(self):
        compiler = shutil.which("c++")
        if not compiler:
            self.skipTest("no host C++ compiler")
        binary = pathlib.Path(self.dir.name) / "observer"
        source = r'''
#include "pa32_firmware_milestone.h"
#include <cassert>
#include <string>
int main() {
  aethercore::pa32sim::FirmwareMilestone observer;
  assert(!observer.linuxPassed());
  const std::string uart = "OpenSBI v1.6\nLinux version 6.6.143\nRV64 USER UART IRQ OK\n";
  unsigned cycle = 1;
  for (unsigned char byte : uart) observer.observeUart(true, byte, cycle++);
  assert(observer.milestoneCycle != 0);
  assert(!observer.linuxPassed());
  observer.observeInterrupt(true, 0x8000000000000005ULL);
  assert(!observer.linuxPassed());
  observer.observeInterrupt(true, 0x80000009ULL);
  assert(observer.linuxPassed());
  assert(observer.uartBytes == uart.size());
  assert(observer.stip == 1 && observer.seip == 1);
  return 0;
}
'''
        subprocess.run([compiler, "-std=c++20", "-O2", "-x", "c++", "-",
                        "-I", str(ROOT / "sim"), "-o", str(binary)],
                       input=source, text=True, check=True, capture_output=True)
        subprocess.run([str(binary)], check=True)

    def test_firmware_source_and_benchmark_contract(self):
        sh = ROOT / "tools/ci/rv64_pa32_gsim_firmware_diff.sh"
        subprocess.run(["bash", "-n", str(sh)], check=True)
        src = sh.read_text()
        for token in ("rv64-linux-early", "fw_payload.bin", "2000000",
                      "--firmware", "sha256sum", "AetherCoreV2OpenSbiRV64SimTop",
                      "TIMEOUT_SECONDS", "LINUX_PID1", "--linux"):
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
        shared = (ROOT / "sim/pa32_firmware_milestone.h").read_text()
        for token in ("RV64 USER UART IRQ OK", "OpenSBI v1.6",
                      "Linux version 6.6.143", "uartDigest", "stip", "seip"):
            self.assertIn(token, shared)

    def test_full_linux_signoff_is_explicit(self):
        gsim = (ROOT / ".github/workflows/rv64-pa32-gsim-host-bridge.yml").read_text()
        self.assertIn("default: false", gsim)
        self.assertEqual(
            gsim.count("if: ${{ github.event_name == 'workflow_dispatch' && inputs.full_linux }}"),
            2,
        )
        self.assertNotIn("github.event_name == 'pull_request' ||", gsim)
        self.assertIn("CYCLES=20000000 bash tools/ci/rv64_pa32_gsim_firmware_diff.sh", gsim)
        self.assertIn("CYCLES=450000000 TIMEOUT_SECONDS=7200 LINUX_PID1=1", gsim)
    def test_gsim_ci_reuses_qualified_cache_and_real_pid1_payload(self):
        gsim = (ROOT / ".github/workflows/rv64-pa32-gsim-host-bridge.yml").read_text()
        baseline = (ROOT / ".github/workflows/rv64-minimal-initramfs-v1.yml").read_text()

        def cache_paths(workflow, step):
            block = workflow.split("      - name: " + step + "\n", 1)[1]
            block = block.split("          path: |\n", 1)[1]
            return tuple(line.strip() for line in block.split("          key:", 1)[0].splitlines()
                         if line.strip())

        self.assertEqual(
            cache_paths(gsim, "Restore RV64 Linux GCC cache"),
            cache_paths(baseline, "Restore RV64 Linux GCC cache"))
        self.assertEqual(
            cache_paths(gsim, "Restore qualified RV64 Linux/OpenSBI software cache"),
            cache_paths(baseline, "Restore qualified RV64 software cache"))
        self.assertNotIn("pa32-gsim-opensbi-software-foundation-v1", gsim)
        self.assertIn("Save qualified RV64 Linux/OpenSBI software cache", gsim)
        self.assertIn("Save RV64 Linux GCC cache after provisioning", gsim)
        self.assertIn("bash tools/ci/rv64_minimal_initramfs_build.sh", gsim)
        self.assertIn("bash tools/ci/rv64_minimal_init_payload_build.sh", gsim)
        self.assertIn("FW_BIN=build/rv64-minimal-init-boot/opensbi/platform/generic/firmware/fw_payload.bin", gsim)
        self.assertIn("CYCLES=450000000 TIMEOUT_SECONDS=7200 LINUX_PID1=1", gsim)

if __name__ == "__main__":
    unittest.main()
