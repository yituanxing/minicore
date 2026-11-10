"""Cheap real shell smoke test for disposable C1 corrupt-copy probes.

The mocked inspection command checks the exact specimen SHA identities.  The
actual savable RTL/Host inspector still needs to run on a seeded Runner model.
"""

from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_c1_inspect_smoke.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-c1-checkpoint-relay.yml"


class C1InspectSmokeContract(unittest.TestCase):
    @staticmethod
    def sha(data):
        return hashlib.sha256(data).hexdigest()

    def run_smoke(self, corrupt_original=False):
        with tempfile.TemporaryDirectory(prefix="c1-inspect-smoke-test-") as tmp:
            root = Path(tmp)
            fixture = root / "source"
            fixture.mkdir()
            host = b"AETSDCP1" + b"host-state-UART-observer-data"
            rtl = b"real-model-state-test-fixture"
            (fixture / "persistent.pre.host").write_bytes(host)
            (fixture / "persistent.pre.rtl").write_bytes(rtl)
            (fixture / ".c1-ready").write_text(
                f"host_sha256={self.sha(host)}\nrtl_sha256={self.sha(rtl)}\n"
            )
            if corrupt_original:
                (fixture / "persistent.pre.host").write_bytes(host + b"X")
            fakebin = root / "fakebin"
            fakebin.mkdir()
            # A fake bash command intercepts exactly the runner's checkpoint
            # helper, never the outer /bin/bash executing the smoke script.
            fake = fakebin / "bash"
            fake.write_text("""#!/bin/sh
set -eu
[ "$1" = 'tools/ci/product_sd1_c1_checkpoint.sh' ] || exit 61
action="$2"; proof="$3"
[ "$action" = inspect ] || [ "$action" = verify ] || exit 62
expected_host=$(sed -n 's/^host_sha256=//p' "$proof/.c1-ready")
expected_rtl=$(sed -n 's/^rtl_sha256=//p' "$proof/.c1-ready")
actual_host=$(sha256sum "$proof/persistent.pre.host" | cut -d' ' -f1)
actual_rtl=$(sha256sum "$proof/persistent.pre.rtl" | cut -d' ' -f1)
if [ "$expected_host" != "$actual_host" ] || [ "$expected_rtl" != "$actual_rtl" ]; then
  echo 'PRODUCT_SD1_C1_CHECKPOINT_REJECTED: checkpoint SHA256/regular file identity differs' >&2
  exit 3
fi
if [ "$action" = inspect ]; then
  echo 'PRODUCT_SD1_C1_HOST_RTL_INSPECTED cycles=12000000 ready=0 uart_marker=0 spi_quiescent=1 no_guest_cycles=1 not_debian_pass=1'
fi
""")
            fake.chmod(0o755)
            env = dict(os.environ, PATH=str(fakebin) + os.pathsep + os.environ["PATH"],
                       RUNNER_TEMP=str(root))
            result = subprocess.run(
                ["/bin/bash", str(SCRIPT), str(fixture)],
                cwd=ROOT, env=env, capture_output=True, text=True, timeout=10,
            )
            return result, (fixture / "persistent.pre.host").read_bytes()

    def test_valid_original_accepts_and_both_corrupt_clones_reject(self):
        result, after = self.run_smoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("corrupted_host_rejected=1 corrupted_rtl_rejected=1", result.stdout)
        self.assertEqual(after, b"AETSDCP1" + b"host-state-UART-observer-data")

    def test_corrupt_original_rejected_before_any_cloning(self):
        result, _ = self.run_smoke(corrupt_original=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("zero-cycle live Host/RTL inspector failed", result.stderr)

    def test_workflow_calls_only_after_real_seed_and_does_not_claim_login(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("- name: Zero-cycle C1 Host/RTL inspect and corrupted-copy rejection", workflow)
        self.assertIn("if: steps.source.outputs.mode == 'seed'", workflow)
        self.assertIn("bash tools/ci/product_sd1_c1_inspect_smoke.sh \"$SEED_PROOF\"", workflow)
        source = SCRIPT.read_text()
        self.assertIn("not_debian_pass=1", source)
        self.assertIn("cp --reflink=auto", source)


if __name__ == "__main__":
    unittest.main()
