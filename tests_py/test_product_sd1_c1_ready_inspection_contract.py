"""Cheap adversarial C1 READY checks; never runs a guest or modifies signed SD1.

The shell integration is exercised with an isolated inspector stub. The real
inspector must restore the full SHA-pinned Host/RTL state at zero guest cycles.
"""

from pathlib import Path
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / "sim/product_rv64_checkpoint_sd1_main.cpp"
RELAY = ROOT / "tools/ci/product_sd1_c1_artifact_relay.sh"
RUNNER = ROOT / "tools/ci/product_sd1_c1_checkpoint.sh"

HOST_SHA = "a" * 64
RTL_SHA = "b" * 64


class C1ReadyInspectionContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = RELAY.read_text()
        cls.inspector = (
            "inspect_c1_ready() {"
            + source.split("inspect_c1_ready() {", 1)[1].split(
                '\n\n[[ "$ACTION" == package', 1
            )[0]
        )
        cls.shell = """set -euo pipefail
fail() { printf 'REJECTED: %s\\n' "$*" >&2; exit 3; }
require_file() { [[ -s "$1" && -f "$1" && ! -L "$1" ]] || fail "missing file $1"; }
field() { sed -n "s/^$1=//p" "$2"; }
bash() {
  [[ "$1" == tools/ci/product_sd1_c1_checkpoint.sh && "$2" == inspect &&
     "$3" == "$C1_MOCK_DIR" ]] || fail 'unexpected inspector command'
  [[ "$C1_INSPECT_EXIT" == 0 ]] || return "$C1_INSPECT_EXIT"
  printf '%s\\n' "$C1_INSPECT_RECORD"
}
""" + cls.inspector + '\ninspect_c1_ready "$C1_MOCK_DIR"\n'

    @staticmethod
    def record(*, ready=1, marker=1, quiet=1, host=HOST_SHA, rtl=RTL_SHA):
        return (
            "PRODUCT_SD1_C1_HOST_RTL_INSPECTED cycles=32000000"
            f" ready={ready} uart_marker={marker} spi_quiescent={quiet}"
            f" host_sha256={host} rtl_sha256={rtl} uart_hash=0x123abc"
            " no_guest_cycles=1 not_debian_pass=1"
        )

    def invoke(self, *, declared=1, declared_quiet=1, record=None,
               inspector_exit=0, append_log=""):
        with tempfile.TemporaryDirectory(prefix="c1-ready-inspection-") as temp:
            proof = Path(temp)
            (proof / ".c1-ready").write_text(
                f"cycle=32000000\nhost_sha256={HOST_SHA}\nrtl_sha256={RTL_SHA}\n"
            )
            # A warm stage *never* needs to reprint the READY UART line.
            (proof / "save.log").write_text(
                "PRODUCT_SD1_PERSISTENT_CHECKPOINT_RESTORED cycles=12000000\n"
                "PRODUCT_SD1_C1_PREFIX_ADVANCED consumed_sectors=8"
                f" ready={declared} spi_quiescent={declared_quiet}"
                " no_rootfs_swap_claim=1\n" + append_log
            )
            env = dict(os.environ)
            env.update(C1_MOCK_DIR=str(proof),
                       C1_INSPECT_RECORD=self.record() if record is None else record,
                       C1_INSPECT_EXIT=str(inspector_exit))
            return subprocess.run(
                ["bash", "-c", self.shell], cwd=ROOT, env=env,
                capture_output=True, text=True, timeout=10,
            )

    def test_prior_stage_uart_ready_then_later_spi_quiet_is_accepted(self):
        accepted = self.invoke()
        self.assertEqual(accepted.returncode, 0, accepted.stderr)
        self.assertEqual(accepted.stdout, "1")

    def test_intermediate_unready_prefix_remains_relayable(self):
        accepted = self.invoke(declared=0, declared_quiet=0,
                               record=self.record(ready=0, marker=1, quiet=0))
        self.assertEqual(accepted.returncode, 0, accepted.stderr)
        self.assertEqual(accepted.stdout, "0")

    def test_forged_ready_in_last_stage_log_is_rejected(self):
        rejected = self.invoke(record=self.record(ready=0, marker=0, quiet=1))
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("differs from authenticated Host/RTL", rejected.stderr)

    def test_missing_cumulative_guest_ready_marker_is_rejected(self):
        rejected = self.invoke(record=self.record(ready=1, marker=0, quiet=1))
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("requires cumulative guest UART marker", rejected.stderr)

    def test_swapped_host_or_rtl_sha_is_rejected(self):
        for changed in (self.record(host="c" * 64), self.record(rtl="d" * 64)):
            with self.subTest(record=changed):
                rejected = self.invoke(record=changed)
                self.assertNotEqual(rejected.returncode, 0)
                self.assertIn("differs from complete Host/RTL SHA", rejected.stderr)

    def test_corrupt_uart_host_restore_fails_closed(self):
        # The production inspector exits nonzero when readHost() detects a
        # changed UART byte/hash; package must never trust a leftover log.
        rejected = self.invoke(inspector_exit=1)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("authenticated zero-cycle", rejected.stderr)

    def test_claimed_idle_spi_mismatch_and_guest_mount_are_rejected(self):
        bad_idle = self.invoke(declared_quiet=0)
        self.assertNotEqual(bad_idle.returncode, 0)
        self.assertIn("differs from authenticated Host/RTL", bad_idle.stderr)
        mount = self.invoke(append_log="AETHERCORE_C1_MOUNT_BEGIN\n")
        self.assertNotEqual(mount.returncode, 0)
        self.assertIn("rootfs-dependent guest execution", mount.stderr)

    def test_real_host_inspection_restores_without_guest_cycles(self):
        source = HOST.read_text()
        inspect_block = source.split("if (c1Inspect) {", 1)[1].split("\n    Identity continuationId", 1)[0]
        restore_block = source.split("} else {\n      readHost(prefix+\".pre.host\"", 1)[1].split(
            "\n    Identity continuationId", 1
        )[0]
        self.assertIn('requireC1Unreleased(obs);', inspect_block)
        self.assertIn('c1CaptureReady(obs,top,transport,card)', inspect_block)
        self.assertIn('shaFile(prefix+".pre.host")', inspect_block)
        self.assertIn('shaFile(prefix+".pre.rtl")', inspect_block)
        self.assertIn('return 0;', inspect_block)
        self.assertNotIn('oneCycle(', inspect_block)
        for required in ('readHost(prefix+".pre.host"',
                         'readRtl(prefix+".pre.rtl",top)',
                         'if (uartHash!=o.uartHash)',
                         'card.restore(std::span<const std::uint8_t>(cardBlob))'):
            self.assertIn(required, source)
        self.assertIn('if ((c1Advance || c1Inspect || c1Release)', restore_block)
        self.assertIn('timeout --signal=TERM --kill-after=10s 120s "$SIM" c1-inspect',
                      RUNNER.read_text())
        self.assertIn('inspected_ready="$(inspect_c1_ready "$ROOT/$source_proof")"',
                      RELAY.read_text())


if __name__ == "__main__":
    unittest.main()
