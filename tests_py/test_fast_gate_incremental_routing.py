"""Real Git graph regression for fast-gate synchronize routing.

GitHub's pull_request.paths trigger sees the cumulative three-dot PR diff.
Once that trigger fires, the fast development lane should instead route the
newly pushed batch when its prior head remains a reachable ancestor.  Release
qualification remains an independent full-SHA gate.
"""

import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/fast-gate.yml"


def run_git(directory: Path, *args: str) -> str:
    return subprocess.check_output(
        ["git", *args], cwd=directory, text=True, stderr=subprocess.STDOUT
    ).strip()


def classifier_script() -> str:
    workflow = WORKFLOW.read_text()
    classification = workflow.split("      - name: Classify changed paths\n", 1)[1]
    classification = classification.split("\n  source-contract:\n", 1)[0]
    return textwrap.dedent(classification.split("\n        run: |\n", 1)[1])


class FastGateIncrementalRoutingSpec(unittest.TestCase):
    def test_synchronize_selects_new_batch_and_force_push_falls_back(self) -> None:
        script = classifier_script()
        with tempfile.TemporaryDirectory(prefix="aether-fast-gate-") as tmp:
            repo = Path(tmp)
            run_git(repo, "init", "-q")
            run_git(repo, "config", "user.email", "ci@example.invalid")
            run_git(repo, "config", "user.name", "CI Test")

            def commit(name: str, path: str) -> str:
                destination = repo / path
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text(name + "\n")
                run_git(repo, "add", path)
                run_git(repo, "commit", "-qm", name)
                return run_git(repo, "rev-parse", "HEAD")

            base = commit("base", "README.md")
            previous = commit(
                "previous-v2-change", "src/main/scala/aethercore/core/v2/TinyRecovery.scala"
            )
            head = commit("documentation-only", "docs/notes.md")

            def classify(before: str, after: str) -> dict[str, str]:
                output = repo / "routing-output.txt"
                output.write_text("")
                environment = os.environ.copy()
                environment.update(
                    PR_ACTION="synchronize",
                    PR_BASE_SHA=base,
                    PR_BEFORE_SHA=before,
                    PR_AFTER_SHA=after,
                    GITHUB_OUTPUT=str(output),
                )
                subprocess.run(
                    ["bash", "-e", "-o", "pipefail", "-c", script],
                    cwd=repo,
                    env=environment,
                    check=True,
                    capture_output=True,
                    text=True,
                )
                return dict(line.split("=", 1) for line in output.read_text().splitlines())

            incremental = classify(previous, head)
            self.assertEqual(incremental["classified_base"], previous)
            self.assertEqual(incremental["run_v2"], "false")
            self.assertEqual(incremental["run_legacy_chisel"], "false")

            # An unavailable previous head must not silently narrow coverage.
            fallback = classify("0" * 40, head)
            self.assertEqual(fallback["classified_base"], base)
            self.assertEqual(fallback["run_v2"], "true")


if __name__ == "__main__":
    unittest.main()
