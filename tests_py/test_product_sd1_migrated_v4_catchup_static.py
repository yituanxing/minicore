"""Fail-closed static checks for isolated 8292->8492 and separate 8.5B crossing."""

import os
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_migrated_v4_catchup_20m.sh"
A = ROOT / ".github/workflows/product-sd1-v4-a-8292-to-8492-INERT-DRAFT.yml"
A1 = ROOT / ".github/workflows/product-sd1-v4-a1-8292-to-8392-INERT-DRAFT.yml"
A2 = ROOT / ".github/workflows/product-sd1-v4-a2-8392-to-8492-INERT-DRAFT.yml"
B = ROOT / ".github/workflows/product-sd1-v4-b-8492-to-8512-INERT-DRAFT.yml"


class V4CatchupStatic(unittest.TestCase):
    def test_shell_syntax_and_zero_guest_plan(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)
        p = subprocess.run(["bash", str(SCRIPT), "--plan"], capture_output=True, text=True, check=True)
        self.assertIn("no_guest_cycles=1", p.stdout)
        self.assertIn("separate_cross=8512", p.stdout)
        self.assertNotIn("PRODUCT_SD1_V4_REAL_RTL_STAGE_SAVED", p.stdout)
        # GitHub Actions exports the real relay credentials to *all* steps.
        # Negative tests must explicitly drop them, so a CI runner cannot
        # accidentally turn this into a different validation path.
        isolated_env = os.environ.copy()
        for key in ("AETHERCORE_SD1_V4_CATCHUP_EXECUTE",
                    "AETHERCORE_SD1_RELAY_CONFIRM",
                    "AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA"):
            isolated_env.pop(key, None)
        p = subprocess.run(["bash", str(SCRIPT), "--execute-reviewed-20m", "/tmp", "8312"],
                           capture_output=True, text=True, env=isolated_env)
        self.assertEqual(p.returncode, 16)
        self.assertIn("unreviewed v4", p.stderr)

    def test_exact_source_and_10b_gate(self):
        s = SCRIPT.read_text()
        self.assertIn("8312|8332|8352|8372|8392|8412|8432|8452|8472|8492", s)
        self.assertIn("TARGET_M\" == 8512", s)
        self.assertIn("SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_10B", s)
        self.assertIn("TARGET\" -le 10000000000", s)
        self.assertIn("[[ ! -e \"$OUT/${TARGET_M}m\" ]]", s)
        self.assertIn("mv -T --", s)

    def test_manifest_seals_all_wrapper_state(self):
        s = SCRIPT.read_text()
        for term in ("snapshot_host_sha256", "snapshot_rtl_sha256", "card_progress_sha256",
                     "firmware_sha256", "dtb_sha256", "sd_image_sha256", "linux_image_sha256",
                     "rootfs_result_sha256", "source_host_sha256", "source_rtl_sha256",
                     "source_cycles", "retired_delta", "sd_command_delta", "debian_login_claim=0"):
            self.assertIn(term, s, term)
        self.assertIn("full_host_except_model_sha_identical", s)
        self.assertIn("full_rtl_identical", s)

    def test_a_workflow_10_real_stages_upload_each(self):
        import yaml
        d = yaml.safe_load(A.read_text())
        j = d["jobs"]["v4_catchup_ten_true_rtl_stages"]
        steps = j["steps"]
        names = [x["name"] for x in steps]
        runs = [x for x in names if x.startswith("Advance genuine v4 Product RTL ")]
        uploads = [x for x in names if x.startswith("Upload complete v4 ")]
        self.assertEqual(len(runs), 10)
        self.assertEqual(len(uploads), 10)
        self.assertEqual(j["env"]["V4_CATCHUP_SOURCE_SHA"], "UNREVIEWED_FAIL_CLOSED")
        self.assertEqual(j["env"]["V4_QUAL_RUN"], "42545549282")
        self.assertEqual(j["env"]["V4_QUAL_ID"], "13642643665")
        for i, n in enumerate(range(8312, 8493, 20)):
            self.assertIn(f"{n-20}M to {n}M", runs[i])
            self.assertIn(f"{n}M", uploads[i])
            assert_i = names.index(runs[i])
            self.assertEqual(names[assert_i+1], uploads[i])
        for x in steps:
            if "run" in x and "\n" in x["run"]:
                subprocess.run(["bash", "-n"], input=x["run"], text=True, check=True)

    def test_official_v4_artifact_is_exact(self):
        t = A.read_text()
        for needle in ("256746ebcf7d40861fd671bf9df17d9136884fc08719861c469308ddc7b29377",
                       "2b82759d2dab4abe1ae213d682338c5238a0e8abf828562455036e7f7554a833",
                       "ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a",
                       "5a2efcdcc748b28a3ccf05787ac949dc4e473b1c2889790b21d39d135dde3580",
                       "71b8083cb692db41759ade061b256a8107765d99fd476b7a42e96685830142f1",
                       "4b41bae42c7d858aa17a5b3379a45b32faff1edec51ed22605e949b6a273b0ae",
                       "cross_8p5b_executed=0", "full_rtl_identical=1",
                       "full_host_except_model_sha_identical=1"):
            self.assertIn(needle, t)

    def test_b_cross_8p5b_8512_is_separately_sha_locked(self):
        import yaml
        d = yaml.safe_load(B.read_text())
        j = d["jobs"]["v4_cross_8p5b_real_rtl_stage"]
        env = j["env"]
        for key in ("A2_RUN", "A2_HEAD", "A2_8492_ID", "A2_8492_ZIP",
                    "AETHERCORE_SD1_V4_CROSS_8492_HOST_SHA",
                    "AETHERCORE_SD1_V4_CROSS_8492_RTL_SHA",
                    "AETHERCORE_SD1_V4_CROSS_8492_CARD_SHA"):
            self.assertEqual(env[key], "UNREVIEWED_FAIL_CLOSED", key)
        names = [x["name"] for x in j["steps"]]
        runs = [n for n in names if n.startswith("Advance genuine v4 Product RTL ")]
        self.assertEqual(len(runs), 1)
        self.assertIn("8492M to 8512M", runs[0])
        self.assertIn("Upload complete v4 8512M", names[names.index(runs[0])+1])
        self.assertIn("not_debian", SCRIPT.read_text().lower())
        for x in j["steps"]:
            if "run" in x and "\n" in x["run"]:
                subprocess.run(["bash", "-n"], input=x["run"], text=True, check=True)

    def test_a1_first_five_stages_short_ci_option(self):
        import yaml
        d = yaml.safe_load(A1.read_text())
        j = d["jobs"]["v4_catchup_five_true_rtl_stages"]
        names = [x["name"] for x in j["steps"]]
        advances = [n for n in names if n.startswith("Advance genuine v4 Product RTL ")]
        uploads = [n for n in names if n.startswith("Upload complete v4 ")]
        self.assertEqual(len(advances), 5)
        self.assertEqual(len(uploads), 5)
        self.assertIn("8292M to 8312M", advances[0])
        self.assertIn("8372M to 8392M", advances[-1])
        self.assertLess(j["timeout-minutes"], 180)
        for x in j["steps"]:
            if "run" in x and "\n" in x["run"]:
                subprocess.run(["bash", "-n"], input=x["run"], text=True, check=True)

    def test_a2_five_stages_and_five_a1_artifact_metadata_seals(self):
        import yaml
        d = yaml.safe_load(A2.read_text())
        j = d["jobs"]["v4_catchup_five_true_rtl_stages_a2"]
        names = [x["name"] for x in j["steps"]]
        advances = [n for n in names if n.startswith("Advance genuine v4 Product RTL ")]
        uploads = [n for n in names if n.startswith("Upload complete v4 ")]
        self.assertEqual(len(advances), 5)
        self.assertEqual(len(uploads), 5)
        self.assertIn("8392M to 8412M", advances[0])
        self.assertIn("8472M to 8492M", advances[-1])
        self.assertLess(j["timeout-minutes"], 180)
        for m in (8312, 8332, 8352, 8372, 8392):
            self.assertEqual(j["env"][f"A1_{m}_ID"], "UNREVIEWED_FAIL_CLOSED")
            self.assertEqual(j["env"][f"A1_{m}_ZIP"], "UNREVIEWED_FAIL_CLOSED")
        self.assertEqual(j["env"]["A1_RUN"], "UNREVIEWED_FAIL_CLOSED")
        self.assertEqual(j["env"]["A1_HEAD"], "UNREVIEWED_FAIL_CLOSED")
        self.assertIn("for m in 8312 8332 8352 8372 8392; do", A2.read_text())
        self.assertIn("--arg digest \"sha256:$zip\"", A2.read_text())
        for x in j["steps"]:
            if "run" in x and "\n" in x["run"]:
                subprocess.run(["bash", "-n"], input=x["run"], text=True, check=True)


if __name__ == "__main__":
    unittest.main()
