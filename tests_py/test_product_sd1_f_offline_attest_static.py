"""Static no-guest-clock safety review of the inert one-off F8372 attestation."""
import ast
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_f_offline_attest.py"
WF = ROOT / ".github/workflows/product-sd1-v3-f8372-offline-attest-INERT-DRAFT.yml"


class OfflineAttestationStatic(unittest.TestCase):
    def test_plan_is_inert(self):
        a = subprocess.run(["python3", str(SCRIPT), "--plan"], cwd=ROOT,
                           text=True, capture_output=True, check=False)
        self.assertEqual(a.returncode, 0, a.stderr)
        self.assertIn("no_guest_cycles=1", a.stdout)
        self.assertFalse((ROOT / "build").exists())

    def test_source_parses_and_proves_exact_known_failure_only(self):
        s = SCRIPT.read_text()
        ast.parse(s)
        for marker in (
            "F_RUN = 42542974143",
            'F_HEAD = "d07660bbaff04f19bdd57909ff27b1ab03c7dde7"',
            "F_JOB_ID = 128921785366",
            'run.get("conclusion") == "failure"',
            '"Verify five complete F checkpoints and no false Debian PASS"',
            '"line 4: e: unbound variable"',
            '"--allow-escape-sequences"',
            "len(failure) == 1",
            "len(matches) == 1",
            "snapshot_host_sha256",
            "snapshot_rtl_sha256",
            "card_progress_sha256",
            "source_host_sha256",
            "source_rtl_sha256",
            "five_advances_and_uploads_success",
            '"only_failing_step_is_known_verifier_unbound_e": True',
            '"no_guest_cycles_executed": True',
            '"debian_login_claim": False',
        ):
            self.assertIn(marker, s)
        for bad in ("VAetherCoreProductDebianSdRV64SimTop", "qemu-system", "verilator --"):
            self.assertNotIn(bad, s)

    def test_all_five_pinned_zips(self):
        s = SCRIPT.read_text()
        pins = (
            ("8292", "13642630450", "28316adb927a21ade2e23369d4e1388e6159370ec681e5b287eb898a493da955"),
            ("8312", "13641838139", "30ae8d62b421b9b3fb4ed88cf5d9157fea719f4c0ef0ba78490c4ecd498c6c16"),
            ("8332", "13642825008", "29b352e049c447588ae9a7454875b88abfdb7c139c9432e60e0a2bde89df45a2"),
            ("8352", "13642178383", "4aa6e991348d6a4786e8818a01cde6aefd9ae84ad674c967a6132b0ed8ce3ad8"),
            ("8372", "13642786033", "8684ccb87bc9e2ee03bcd12156679fc28b940053a05e59478652442404dd7468"),
        )
        for stage, artifact, sha in pins:
            self.assertIn(f"({stage}, {artifact}, \"{sha}\")", s)

    def test_workflow_separate_reviewed_trigger_and_no_guest_steps(self):
        s = WF.read_text()
        self.assertIn("AUDIT_SOURCE_SHA: UNREVIEWED_FAIL_CLOSED", s)
        self.assertIn("product-sd1-v3-f8372-offline-attest-reviewed", s)
        self.assertIn("F8372_offline_sha_attest_no_guest", s)
        self.assertIn("product-sd1-v3-f8372-offline-attested-", s)
        self.assertNotIn("ADVANCE", s)
        self.assertNotIn("execute-reviewed-20m", s)
        self.assertNotIn("actions/cache/restore", s)
        self.assertNotIn("Restore frozen", s)
        try:
            import yaml
        except ImportError:
            return
        d = yaml.load(s, Loader=yaml.BaseLoader)
        steps = d["jobs"]["F8372_offline_sha_attest_no_guest"]["steps"]
        self.assertEqual(len(steps), 5)


if __name__ == "__main__":
    unittest.main()
