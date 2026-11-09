"""Fail-closed static checks for INERT Host-v4 C five-stage 8512->8612M RTL relay."""
import os
import subprocess
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/ci/product_sd1_migrated_v4_postcross_20m.sh"
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-c-8512-to-8612-INERT-DRAFT.yml"
TARGETS = (8532, 8552, 8572, 8592, 8612)


class V4PostcrossStatic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.content = SCRIPT.read_text()
        cls.workflow_text = WORKFLOW.read_text()
        cls.workflow = yaml.safe_load(cls.workflow_text)
        cls.job = cls.workflow["jobs"]["v4_postcross_five_true_rtl_stages"]

    def test_shell_syntax_and_zero_guest_plan(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)
        p = subprocess.run(["bash", str(SCRIPT), "--plan"], capture_output=True, text=True, check=True)
        self.assertIn("no_guest_cycles=1", p.stdout)
        self.assertIn("stages=8532,8552,8572,8592,8612", p.stdout)
        self.assertNotIn("REAL_RTL_STAGE_SAVED", p.stdout)

    def test_unreviewed_execution_fails_before_any_rtl_cycle(self):
        env = os.environ.copy()
        for key in ("AETHERCORE_SD1_V4_CATCHUP_EXECUTE", "AETHERCORE_SD1_RELAY_CONFIRM",
                    "AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA"):
            env.pop(key, None)
        for target in TARGETS:
            p = subprocess.run(["bash", str(SCRIPT), "--execute-reviewed-postcross-20m",
                                "/tmp", str(target)], capture_output=True, text=True, env=env)
            self.assertEqual(p.returncode, 16, (target, p.stderr))
            self.assertIn("unreviewed v4 model or timing configuration", p.stderr)

    def test_wrong_stage_or_source_is_rejected_even_with_auth_flags(self):
        env = os.environ.copy()
        env.update({
            "AETHERCORE_SD1_V4_CATCHUP_EXECUTE": "REVIEWED_V4_8292M_20M_EXACT_DIFF_AND_SOURCE_SHA_AUDITED",
            "AETHERCORE_SD1_RELAY_CONFIRM": "SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_10B",
            "AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA": "ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a",
            "AETHERCORE_VERILATOR_VERSION": "5.054",
            "SIM_SETTLE_MODE": "reference",
            "VERILATOR_THREADS": "1",
            "AETHERCORE_DATA_MEM_WAIT_CYCLES": "0",
        })
        p = subprocess.run(["bash", str(SCRIPT), "--execute-reviewed-postcross-20m", "/tmp", "8632"],
                           env=env, capture_output=True, text=True)
        self.assertEqual(p.returncode, 16)
        self.assertIn("only five exact 20M", p.stderr)
        p = subprocess.run(["bash", str(SCRIPT), "--execute-reviewed-postcross-20m", "/tmp", "8532"],
                           env=env, capture_output=True, text=True)
        self.assertEqual(p.returncode, 16)
        self.assertIn("must start from full authenticated B-8512M", p.stderr)

    def test_only_exact_five_stages_source_chain_and_no_overwrite(self):
        s = self.content
        for check in ("8532|8552|8572|8592|8612", '"$SOURCE_M" -eq "$FIRST_M"',
                      'C_FIRST="$ROOT/build/product-sd1-v4-c-source/8512m"',
                      'SOURCE" == "$OUT/${SOURCE_M}m"',
                      '! -e "$OUT/${TARGET_M}m"',
                      'mv -T -- "$stage" "$OUT/${TARGET_M}m"',
                      "TARGET-START)) -eq 20000000"):
            self.assertIn(check, s, check)
        self.assertNotIn("--execute-reviewed-cross-20m", s)
        self.assertNotIn("verilator --cc", s)

    def test_separate_d_profile_is_bounded_and_preserves_c_behavior(self):
        env = os.environ.copy()
        env["AETHERCORE_SD1_V4_POSTCROSS_PROFILE"] = "D"
        plan = subprocess.run(["bash", str(SCRIPT), "--plan"], env=env,
                              capture_output=True, text=True, check=True)
        self.assertIn("no_guest_cycles=1", plan.stdout)
        self.assertIn("stages=8632,8652,8672,8692,8712; input=C-8612M", plan.stdout)
        for term in ('D_FIRST="$ROOT/build/product-sd1-v4-d-source/8612m"',
                     'AETHERCORE_SD1_V4_D_8612_HOST_SHA',
                     'AETHERCORE_SD1_V4_D_8612_RTL_SHA',
                     'AETHERCORE_SD1_V4_D_8612_CARD_SHA',
                     '8632|8652|8672|8692|8712'):
            self.assertIn(term, self.content)
        env.update({
            "AETHERCORE_SD1_V4_CATCHUP_EXECUTE": "REVIEWED_V4_8292M_20M_EXACT_DIFF_AND_SOURCE_SHA_AUDITED",
            "AETHERCORE_SD1_RELAY_CONFIRM": "SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_10B",
            "AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA": "ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a",
            "AETHERCORE_VERILATOR_VERSION": "5.054",
            "SIM_SETTLE_MODE": "reference",
            "VERILATOR_THREADS": "1",
            "AETHERCORE_DATA_MEM_WAIT_CYCLES": "0",
        })
        for target, needle in ((8532, "only five exact 20M D stages"),
                               (8632, "must start from full authenticated C-8612M")):
            process = subprocess.run(["bash", str(SCRIPT),
                                      "--execute-reviewed-postcross-20m", "/tmp", str(target)],
                                     env=env, capture_output=True, text=True)
            self.assertEqual(process.returncode, 16)
            self.assertIn(needle, process.stderr)

    def test_exact_reviewed_b_host_rtl_card_seals(self):
        s = self.content
        for term in ("AETHERCORE_SD1_V4_C_8512_HOST_SHA",
                     "AETHERCORE_SD1_V4_C_8512_RTL_SHA",
                     "AETHERCORE_SD1_V4_C_8512_CARD_SHA",
                     "snapshot_host_sha256", "snapshot_rtl_sha256", "card_progress_sha256",
                     "source_host_sha256", "source_rtl_sha256", "source_cycles",
                     "firmware_sha256", "dtb_sha256", "sd_image_sha256",
                     "linux_image_sha256", "rootfs_result_sha256",
                     "retired_delta", "sd_command_delta", "debian_login_claim=0",
                     "full_rtl_identical", "full_host_except_model_sha_identical",
                     "PRODUCT_SD1_MODEL_MIGRATION_V4: status=QUALIFIED_HOST_ONLY"):
            self.assertIn(term, s, term)
        for digest in ("4c9b6ed61950094e2551bda780fbea9c79ded65fab433f13b06e2eb64817e5be",
                       "72f9b4298f7f4544321288334e2642943a4a2fb5a9eef1e1957c8e3e8e87fc1c",
                       "6f4ab4daa2b4eb9b9d46ca69816a52ff37f334734ab4e7698e1be64dbc011ed7"):
            self.assertIn(digest, s)

    def test_every_b_pin_is_explicitly_unreviewed(self):
        e = self.job["env"]
        for key in ("V4_C_SOURCE_SHA", "B_RUN", "B_HEAD", "B_8512_ID", "B_8512_ZIP",
                    "AETHERCORE_SD1_V4_C_8512_HOST_SHA", "AETHERCORE_SD1_V4_C_8512_RTL_SHA",
                    "AETHERCORE_SD1_V4_C_8512_CARD_SHA"):
            self.assertEqual(e[key], "UNREVIEWED_FAIL_CLOSED", key)
        self.assertEqual(e["V4_QUAL_RUN"], "42545549282")
        self.assertEqual(e["V4_QUAL_ID"], "13642643665")
        self.assertEqual(e["V4_QUAL_ZIP"], "256746ebcf7d40861fd671bf9df17d9136884fc08719861c469308ddc7b29377")
        self.assertEqual(e["V4_QUAL_MANIFEST"], "2b82759d2dab4abe1ae213d682338c5238a0e8abf828562455036e7f7554a833")
        self.assertEqual(e["AETHERCORE_SD1_V4_REVIEWED_MODEL_SHA"], "ffe4b434df00c3736e3bad6de36bae74444ea84addb8f4c2554e468fb392784a")

    def test_b_success_only_one_exact_advance_and_upload(self):
        t = self.workflow_text
        for check in ('.status=="completed" and .conclusion=="success"',
                      '.path==".github/workflows/product-sd1-v4-b-8492-to-8512.yml"',
                      '.name=="v4_cross_8p5b_real_rtl_stage"',
                      "Advance genuine v4 Product RTL 8492M to 8512M crossing old 8.5B Host cap",
                      "Upload complete v4 8512M before any further guest RTL cycle",
                      "Verify full v4 8512M RTL stage and explicit 8.5B crossing without Debian PASS",
                      "product-sd1-migrated-v4-b-8512m-$B_RUN",
                      ".workflow_run.id==$run", '.digest==$digest',
                      '.expired==false', '"$RUNNER_TEMP/b-8512.zip"'):
            self.assertIn(check, t, check)
        self.assertIn('sha256sum "$RUNNER_TEMP/b-8512.zip"', t)

    def test_five_true_rtl_stages_alternating_save_upload(self):
        steps = self.job["steps"]
        names = [x["name"] for x in steps]
        adv = [x for x in names if x.startswith("Advance genuine v4 Product RTL ")]
        upl = [x for x in names if x.startswith("Upload complete v4 ")]
        self.assertEqual(len(adv), 5)
        self.assertEqual(len(upl), 5)
        for i, m in enumerate(TARGETS):
            self.assertIn(f"{m-20}M to {m}M", adv[i])
            self.assertEqual(names[names.index(adv[i])+1], upl[i])
            self.assertIn(f"{m}M", upl[i])
            action = steps[names.index(adv[i])]
            self.assertIn(f'--execute-reviewed-postcross-20m', action["run"])
            self.assertTrue(action["run"].endswith(f' {m}'))
            up_action = steps[names.index(upl[i])]
            self.assertIn(f"c-{m}m-", up_action["with"]["name"])
            self.assertTrue(up_action["with"]["include-hidden-files"])
            self.assertEqual(up_action["with"]["if-no-files-found"], "error")
        self.assertIn("no Debian PASS", names[-2])

    def test_checkout_happens_before_every_download(self):
        t = self.workflow_text
        checkout = t.index("uses: actions/checkout@v4")
        self.assertLess(checkout, t.index("uses: actions/download-artifact@v4"))
        self.assertLess(checkout, t.index("curl -fsSL --retry 2"))
        self.assertIn("github.repository == 'yituanxing/minicore'", t)
        self.assertIn("github.event.pull_request.number == 379", t)
        self.assertNotIn("verilator --cc", t)
        for x in self.job["steps"]:
            if "run" in x and "\n" in x["run"]:
                subprocess.run(["bash", "-n"], input=x["run"], text=True, check=True)

    def test_no_false_login_claim(self):
        t = self.workflow_text.lower() + self.content.lower()
        self.assertIn("not_debian_pass", t)
        self.assertIn("guest_uart_service_verified':false", t.replace(" ", ""))
        self.assertIn("debian_login_claim=0", t)
        self.assertIn("product_sd1_official_seed.py verify", t)
        self.assertIn("old_checkpoint_compatibility", t)


if __name__ == "__main__":
    unittest.main()
