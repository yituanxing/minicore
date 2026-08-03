import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
CACHE_HELPER = ROOT / "tools" / "ensure_git_revision.sh"
DETERMINISTIC_PROBE = ROOT / "tools" / "probe_rv32_nemu_deterministic.sh"
MAKEFILE = ROOT / "Makefile"
FULL_GATE = ROOT / ".github" / "workflows" / "full-gate.yml"


class PinnedSourceCacheTest(unittest.TestCase):
    def run_command(self, *args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            args,
            cwd=ROOT,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_cache_materializes_exact_clean_revision_without_second_network_source(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            source = base / "source"
            cache = base / "cache"
            first_destination = base / "first"
            second_destination = base / "second"
            source.mkdir()

            self.assertEqual(self.run_command("git", "init", "-q", str(source)).returncode, 0)
            self.assertEqual(
                self.run_command("git", "-C", str(source), "config", "user.name", "AetherCore CI").returncode,
                0,
            )
            self.assertEqual(
                self.run_command(
                    "git", "-C", str(source), "config", "user.email", "ci@aethercore.invalid"
                ).returncode,
                0,
            )
            (source / "payload.txt").write_text("pinned source\n", encoding="utf-8")
            self.assertEqual(self.run_command("git", "-C", str(source), "add", "payload.txt").returncode, 0)
            self.assertEqual(
                self.run_command("git", "-C", str(source), "commit", "-q", "-m", "fixture").returncode,
                0,
            )
            revision_result = self.run_command("git", "-C", str(source), "rev-parse", "HEAD")
            self.assertEqual(revision_result.returncode, 0, revision_result.stderr)
            revision = revision_result.stdout.strip()

            environment = os.environ.copy()
            environment["AETHERCORE_SOURCE_CACHE"] = str(cache)
            first = self.run_command(
                "bash",
                str(CACHE_HELPER),
                str(source),
                revision,
                str(first_destination),
                "fixture",
                env=environment,
            )
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertEqual(first.stdout.strip(), revision)
            self.assertEqual((first_destination / "payload.txt").read_text(encoding="utf-8"), "pinned source\n")

            shutil.rmtree(source)
            (first_destination / "payload.txt").write_text("dirty destination\n", encoding="utf-8")
            second = self.run_command(
                "bash",
                str(CACHE_HELPER),
                str(source),
                revision,
                str(second_destination),
                "fixture",
                env=environment,
            )
            self.assertEqual(second.returncode, 0, second.stderr)
            self.assertEqual(second.stdout.strip(), revision)
            self.assertEqual((second_destination / "payload.txt").read_text(encoding="utf-8"), "pinned source\n")
            status = self.run_command("git", "-C", str(second_destination), "status", "--porcelain")
            self.assertEqual(status.returncode, 0, status.stderr)
            self.assertEqual(status.stdout, "")

    def test_cache_rejects_non_exact_revision(self) -> None:
        result = self.run_command(
            "bash",
            str(CACHE_HELPER),
            "/unused",
            "main",
            "/unused",
            "fixture",
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("full 40-character", result.stderr)


class RunnerCompositionTest(unittest.TestCase):
    def test_rv64_simulator_is_a_timestamped_reusable_target(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("RTL_STAMP := $(RTL_DIR)/.elaborated.stamp", text)
        self.assertIn("SIM_BIN := $(OBJ_DIR)/V$(TOP)", text)
        self.assertIn("rtl: $(RTL_STAMP)", text)
        self.assertIn("sim: $(SIM_BIN)", text)
        self.assertIn("$(SIM_BIN): $(RTL_STAMP)", text)
        self.assertEqual(text.count("./mill aethercore.runMain aethercore.Elaborate"), 1)
        self.assertEqual(text.count("$(VERILATOR) --cc --exe --build"), 1)

    def test_nemu_sources_use_the_validated_cache_and_retry_builds(self) -> None:
        makefile = MAKEFILE.read_text(encoding="utf-8")
        probe = DETERMINISTIC_PROBE.read_text(encoding="utf-8")
        self.assertIn("bash tools/ensure_git_revision.sh", makefile)
        self.assertNotIn("git -C $(NEMU_DIR) fetch", makefile)
        self.assertIn("NEMU configure/build attempt $$attempt/3", makefile)
        self.assertIn("GIT_CONFIG_KEY_0=http.version", makefile)
        self.assertIn('bash "$script_dir/ensure_git_revision.sh"', probe)
        self.assertIn('"$AETHERCORE_NEMU_CACHE_CHECKOUT"', probe)
        self.assertIn("/usr/bin/find", probe)

    def test_full_gate_skips_docs_and_fails_source_bootstrap_early(self) -> None:
        workflow = FULL_GATE.read_text(encoding="utf-8")
        self.assertIn("paths-ignore:", workflow)
        self.assertIn("- 'docs/**'", workflow)
        self.assertIn("- '**/*.md'", workflow)
        self.assertNotIn("Provision fixed Verilator", workflow)
        self.assertLess(
            workflow.index("Build both frozen RV32 NEMU references once"),
            workflow.index("Chisel unit tests"),
        )
        self.assertLess(
            workflow.index("Build both frozen RV32 NEMU references once"),
            workflow.index("RV64 RTL, smoke and precise pipeline regressions"),
        )


if __name__ == "__main__":
    unittest.main()
