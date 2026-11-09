"""Fail-closed continuous 14×20M O RTL replay, source-auth and bounded disk contract."""

import subprocess
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/product-sd1-v4-o-9712-to-9992.yml"
STAGES = tuple(range(9732, 9993, 20))
O_SOURCE = "e020a2535907febf69532ed59f2c17bc7b990724"
PINNED_N = {
    "N_RUN": "42584780799",
    "N_HEAD": "898a5375dcd19a3051bdaec783e4b17e23f303ca",
    "N_9712_ID": "13659557215",
    "N_9712_ZIP": "cc20770178a3354cc863cafade33d60f3fb04478fc6dea539bc8f5727ffb2b4f",
    "AETHERCORE_SD1_V4_O_9712_HOST_SHA": "276d9e92405a0c2b11c073204cb11a6ae3f40cb75132d4ab769f65aa20926c2b",
    "AETHERCORE_SD1_V4_O_9712_RTL_SHA": "214ef74fe4bea619781911f49be1828897c573635586da6f38e3745278698783",
    "AETHERCORE_SD1_V4_O_9712_CARD_SHA": "b6e2b27cd75adfdb74acaed83eb9688e47c6586c501f15186a6c89e24b868f7d",
}


class V4ORelayContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = WORKFLOW.read_text()
        cls.data = yaml.safe_load(cls.raw)
        cls.job = cls.data["jobs"]["v4_postcross_fourteen_true_rtl_stages_o"]
        cls.steps = cls.job["steps"]

    def test_reviewed_n_terminal_pins_and_source(self):
        env = self.job["env"]
        self.assertEqual(env["V4_O_SOURCE_SHA"], O_SOURCE)
        self.assertEqual(env["AETHERCORE_SD1_V4_POSTCROSS_PROFILE"], "O")
        for key, value in PINNED_N.items():
            self.assertEqual(str(env[key]), value, key)
        self.assertIn("github.event.pull_request.number == 379", self.raw)
        self.assertIn("product-sd1-v4-reviewed-9712-to-9992-fourteen-stages", self.raw)
        self.assertIn('.path==".github/workflows/product-sd1-v4-n-9612-to-9712.yml"', self.raw)
        self.assertIn('.name=="v4_postcross_five_true_rtl_stages_n"', self.raw)
        self.assertIn('Verify five complete v4 postcross real RTL checkpoints, no Debian PASS', self.raw)
        self.assertIn("prev=9612\n          for m in 9632 9652 9672 9692 9712", self.raw)
        self.assertIn('sha256sum "$RUNNER_TEMP/n-9712.zip"', self.raw)
        self.assertIn("boot_cycles=9712000000", self.raw)
        self.assertIn("source_cycles=9692000000", self.raw)
        self.assertIn('debian_login_claim=0', self.raw)
        self.assertIn('"$(git rev-parse HEAD)" == "$V4_O_SOURCE_SHA"', self.raw)
        self.assertIn("tests_py.test_product_sd1_v4_o_executor_static", self.raw)

    def test_one_job_fourteen_exact_stages_each_full_upload_before_next(self):
        names = [s["name"] for s in self.steps]
        advance = [(i, step) for i, step in enumerate(self.steps)
                   if step["name"].startswith("Advance genuine v4 Product RTL")]
        uploads = [(i, step) for i, step in enumerate(self.steps)
                   if step["name"].startswith("Upload complete v4")]
        self.assertEqual(len(advance), 14)
        self.assertEqual(len(uploads), 14)
        self.assertEqual(self.job["timeout-minutes"], 210)
        self.assertIn("10b", self.job["env"]["OUT"])
        for target, (ai, astep), (ui, ustep) in zip(STAGES, advance, uploads):
            self.assertEqual(ui, ai + 1)
            self.assertIn(f"{target-20}M to {target}M", astep["name"])
            self.assertTrue(astep["run"].endswith(f" {target}"))
            self.assertIn("--execute-reviewed-postcross-20m", astep["run"])
            self.assertIn(f"o-{target}m-", ustep["with"]["name"])
            self.assertIn(f"/{target}m", ustep["with"]["path"])
            self.assertTrue(ustep["with"]["include-hidden-files"])
            self.assertEqual(ustep["with"]["if-no-files-found"], "error")
            if target != 9992:
                self.assertLess(ui, advance[(target-9732)//20 + 1][0])

    def test_bounded_local_snapshot_pruning_is_after_upload_only(self):
        retire = [(i, s) for i, s in enumerate(self.steps)
                  if s["name"].startswith("Retire local")]
        self.assertEqual(len(retire), 13)
        names = [s["name"] for s in self.steps]
        for target in STAGES[1:]:
            prev = target - 20
            step_index, step = next((i, s) for i, s in retire
                                    if f"after {target}M complete artifact upload" in s["name"])
            self.assertEqual(names[step_index-1],
                             f"Upload complete v4 {target}M before any further guest RTL cycle")
            self.assertEqual(names[step_index+1].startswith("Advance genuine"), target != 9992)
            self.assertIn(f'prior="$OUT/{prev}m"', step["run"])
            self.assertIn(f'current="$OUT/{target}m"', step["run"])
            self.assertIn('rm -- "$prior/persistent.pre.host" "$prior/persistent.pre.rtl"', step["run"])
            self.assertIn('sha256sum "$f"', step["run"])
            self.assertIn('[[ "$OUT" == build/product-sd1-migrated-v4-10b-postcross-o ]]', step["run"])
        verify = next(s["run"] for s in self.steps if
                      s["name"].startswith("Verify fourteen complete"))
        self.assertIn('if [[ "$m" -eq 9992 ]]', verify)
        self.assertIn('! -e "$d/persistent.pre.host"', verify)
        self.assertIn('snapshot_host_sha256=', verify)
        self.assertIn('debian_login_claim=0', verify)

    def test_shell_syntax_signed_media_and_single_environment_setup(self):
        self.assertEqual(self.raw.count("uses: actions/checkout@v4"), 1)
        self.assertEqual(self.raw.count("uses: actions/cache/restore@v4"), 2)
        self.assertEqual(self.raw.count("Import v4 qualified model and exact N9712"), 1)
        self.assertIn("product_sd1_official_seed.py verify", self.raw)
        self.assertIn("_NOT_DEBIAN_PASS", self.raw)
        self.assertIn("persist-credentials: false", self.raw)
        self.assertLess(self.raw.index("uses: actions/checkout@v4"),
                        self.raw.index("curl -fsSL --retry 2"))
        self.assertNotIn("verilator --cc", self.raw)
        for step in self.steps:
            if "run" in step:
                subprocess.run(["bash", "-n"], input=step["run"], text=True,
                               check=True)


if __name__ == "__main__":
    unittest.main()
