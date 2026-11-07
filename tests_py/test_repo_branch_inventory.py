"""The inventory must not invent merge status for unfetched GitHub branches."""

from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "ci"))
import report_branch_inventory as inventory  # noqa: E402


class BranchInventorySpec(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="branch-inventory-test-")
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name) / "example"
        self.repo.mkdir()
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Inventory Test")
        self.git("config", "user.email", "inventory@example.invalid")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, text=True,
                              capture_output=True, check=True).stdout.strip()

    def commit(self, name):
        marker = self.repo / name
        marker.write_text(name, encoding="utf-8")
        self.git("add", name)
        self.git("commit", "-m", name)

    def seed_graph(self):
        self.commit("base")
        self.git("branch", "archive/merged")
        self.git("checkout", "-b", "feature/diverged")
        self.commit("side")
        self.git("checkout", "main")
        self.commit("base-advance")
        self.git("checkout", "-b", "feature/ahead")
        self.commit("forward")

    def test_local_merged_ahead_and_diverged_have_real_counts(self):
        self.seed_graph()
        rows = inventory.make_inventory(self.repo, "origin", "main")["heads"]
        by_name = {x["name"]: x for x in rows}
        self.assertEqual((by_name["archive/merged"]["state"],
                          by_name["archive/merged"]["ahead"],
                          by_name["archive/merged"]["behind"]), ("merged", 0, 1))
        self.assertTrue(by_name["archive/merged"]["merged_into_base"])
        self.assertEqual((by_name["feature/ahead"]["state"],
                          by_name["feature/ahead"]["ahead"],
                          by_name["feature/ahead"]["behind"]), ("ahead", 1, 0))
        self.assertEqual((by_name["feature/diverged"]["state"],
                          by_name["feature/diverged"]["ahead"],
                          by_name["feature/diverged"]["behind"]), ("diverged", 1, 1))
        self.assertEqual(by_name["main"]["state"], "base")

    def test_live_remote_missing_objects_are_never_marked_merged(self):
        self.seed_graph()
        before = self.git("status", "--porcelain")
        snapshot = "0" * 40 + "\trefs/heads/unknown/old-experiment\n"
        report = inventory.make_inventory(self.repo, "origin", "main", snapshot)
        remote = next(x for x in report["heads"] if x["scope"] == "remote-live")
        self.assertEqual(remote["state"], "unknown-missing-object")
        self.assertIsNone(remote["merged_into_base"])
        self.assertIsNone(remote["ahead"])
        self.assertEqual(before, self.git("status", "--porcelain"))
        rendered = inventory.markdown_report(report)
        self.assertIn("No branch has been deleted", rendered)

    def test_missing_base_does_not_guess_branch_ancestry(self):
        self.seed_graph()
        report = inventory.make_inventory(self.repo, "origin", "not-a-branch")
        self.assertIsNone(report["base_sha"])
        self.assertEqual({x["state"] for x in report["heads"]},
                         {"unknown-missing-base"})

    def test_strict_remote_snapshot_and_no_output_overwrite(self):
        with self.assertRaises(ValueError):
            inventory.parse_remote_heads("not-a-sha refs/heads/experiment\n")
        destination = Path(self.temporary.name) / "report.md"
        inventory.write_new(destination, "original")
        with self.assertRaises(FileExistsError):
            inventory.write_new(destination, "overwrite")
        self.assertEqual(destination.read_text(encoding="utf-8"), "original")

    def test_cli_writes_parseable_json_and_markdown_without_git_mutation(self):
        self.seed_graph()
        before = self.git("status", "--porcelain")
        output = Path(self.temporary.name) / "results"
        self.assertEqual(inventory.main(["--repo", str(self.repo), "--base", "main",
                                         "--output-dir", str(output)]), 0)
        data = json.loads((output / "branch-inventory.json").read_text(encoding="utf-8"))
        self.assertTrue(data["read_only"])
        self.assertEqual(len(data["heads"]), 4)
        self.assertIn("## Ancestry classification",
                      (output / "branch-inventory.md").read_text(encoding="utf-8"))
        self.assertEqual(before, self.git("status", "--porcelain"))


if __name__ == "__main__":
    unittest.main()
