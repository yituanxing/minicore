"""No guest execution: V5 15B needs-chain, checkpoint and serial proof contracts."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import sys
import os
import subprocess
import zipfile

import yaml


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools/ci"))
import product_sd1_v5_chain as v5


class V5LongChain(unittest.TestCase):
    def test_real_serial_proof_requires_ordered_four_markers(self):
        good = (b"VFS: Mounted root (ext4 filesystem)\r\n"
                b"Run /sbin/init as init process\n"
                b"[\x1b[0;32m  OK  \x1b[0m] Started \x1b[1msystemd-journald.service\x1b[0m.\n"
                b"Debian GNU/Linux 13 localhost console\nlocalhost login: ")
        self.assertTrue(v5.serial_proof(good)["ordered_service_login_verified"])
        self.assertFalse(v5.serial_proof(good)["debian_login_claim"])
        self.assertFalse(v5.serial_proof(good.replace(b"Started", b"Starting"))["ordered_service_login_verified"])
        self.assertFalse(v5.serial_proof(good.replace(b"localhost login: ", b""))["ordered_service_login_verified"])
        self.assertFalse(v5.serial_proof(b"localhost login:\n" + good[:-16])["ordered_service_login_verified"])
        self.assertFalse(v5.serial_proof(good.replace(b"systemd-journald.service", b"systemd.target"))["ordered_service_login_verified"])
        with self.assertRaisesRegex(ValueError, "fatal UART"):
            v5.serial_proof(good + b"\nKernel panic - not syncing\n")

    def test_artifact_provenance_exact(self):
        digest = "a" * 64
        head = "b" * 40
        name = "product-sd1-v5-15b-123456789-attempt2-v5_billion_1-10012m"
        data = {"id": 123, "name": name, "digest": "sha256:" + digest,
                "expired": False, "size_in_bytes": 2048,
                "workflow_run": {"id": 123456789, "head_sha": head}}
        v5.verify_artifact_metadata(data, "123", name, digest, "123456789", head)
        for drift in (dict(data, digest="sha256:" + "c" * 64),
                      dict(data, workflow_run={"id": 123456789, "head_sha": "c" * 40}),
                      dict(data, expired=True)):
            with self.assertRaises(ValueError):
                v5.verify_artifact_metadata(drift, "123", name, digest, "123456789", head)

    def test_immutable_zip_hash_fail_closed(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "archive.zip"
            p.write_bytes(b"reviewed-test-archive")
            expected = v5.digest(p)
            v5.verify_artifact_zip(p, expected)
            with self.assertRaises(ValueError):
                v5.verify_artifact_zip(p, "0" * 64)

    def test_prune_only_old_stage_in_batch_root(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t) / "batch"
            old, new = root / "10012m", root / "10032m"
            old.mkdir(parents=True)
            new.mkdir()
            for suffix in ("host", "rtl"):
                (old / ("persistent.pre." + suffix)).write_bytes(b"checkpoint")
            v5.safe_prune(old, root, new)
            self.assertFalse((old / "persistent.pre.host").exists())
            self.assertFalse((old / "persistent.pre.rtl").exists())
            with self.assertRaises(ValueError):
                v5.safe_prune(new, root, new)

    def test_manifest_duplicate_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            p = Path(t) / "m"
            p.write_text("model_sha256=abc\nmodel_sha256=def\n")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                v5.manifest(p)

    def test_full_host_and_snapshot_verify_no_real_guest_cycles(self):
        with tempfile.TemporaryDirectory() as t:
            p = Path(t)
            (p / "persistent.pre.host").touch()
            with (p / "persistent.pre.host").open("r+b") as f:
                f.truncate(256 * 1024 * 1024 + 128)
            (p / "persistent.pre.rtl").write_bytes(b"rtl")
            (p / "card-progress.json").write_text("{}")
            sha = "a" * 64
            (p / ".migration-ready").write_text(
                "schema=AETSDCP1\nboot_cycles=10012000000\nsource_cycles=9992000000\n"
                "model_sha256=" + sha + "\n" +
                "snapshot_host_sha256=" + sha + "\n" +
                "snapshot_rtl_sha256=" + sha + "\n" +
                "card_progress_sha256=" + sha + "\n" +
                "debian_login_claim=0\n")
            uart = (b"VFS: Mounted root (ext4 filesystem)\nRun /sbin/init as init process\n"
                    b"Started systemd-journald.service\nlocalhost login:")
            with mock.patch.object(v5, "digest", return_value=sha), \
                 mock.patch.object(v5, "decode", return_value={
                    "cycles": 10012000000, "failure": None, "identity": [sha] * 6}), \
                 mock.patch.object(v5, "authentic_uart", return_value=uart):
                result = v5.verify_checkpoint(p, 10012000000, proof_file=True)
            self.assertTrue(result["proof"]["ordered_service_login_verified"])
            self.assertTrue((p / "serial-proof.json").is_file())
            self.assertFalse(json.loads((p / "serial-proof.json").read_text())["debian_login_claim"])

    def test_one_pr_event_five_needs_jobs_fifty_stages(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/product-sd1-v5-15b-needs-9992-to-14992-INERT-DRAFT.yml").read_text())
        triggers = workflow.get("on", workflow.get(True))
        self.assertEqual(triggers, {"pull_request": {"types": ["labeled"]}})
        self.assertEqual(workflow["permissions"], {"contents": "read", "actions": "read"})
        self.assertNotIn("REPLACE_WITH_REVIEWED_V5_PR_HEAD_SHA", workflow["jobs"]["v5_billion_1"]["if"])
        self.assertIn("V5_REVIEWED_PRIOR_HEAD", workflow["env"])
        self.assertEqual(len([key for key in workflow["jobs"] if key.startswith("v5_billion_")]), 5)
        for index in range(1, 6):
            job = workflow["jobs"][f"v5_billion_{index}"]
            self.assertEqual(job["timeout-minutes"], 335)
            self.assertEqual(job["steps"][0]["with"]["fetch-depth"], 2)
            if index > 1:
                self.assertEqual(job["needs"], f"v5_billion_{index-1}")
            self.assertEqual(job["steps"][-1]["uses"], "./.github/actions/product-sd1-v5-batch")
        batch = yaml.safe_load((ROOT / ".github/actions/product-sd1-v5-batch/action.yml").read_text())
        stages = [step for step in batch["runs"]["steps"] if step.get("uses") == "./.github/actions/product-sd1-v5-20m"]
        self.assertEqual(len(stages), 50)
        self.assertEqual([int(step["with"]["ordinal"]) for step in stages], list(range(1, 51)))
        one = yaml.safe_load((ROOT / ".github/actions/product-sd1-v5-20m/action.yml").read_text())
        self.assertEqual(one["runs"]["steps"][1]["uses"], "actions/upload-artifact@v4")
        self.assertTrue(one["runs"]["steps"][1]["with"]["include-hidden-files"])
        self.assertEqual(one["runs"]["steps"][1]["with"]["retention-days"],
                         "${{ steps.advance.outputs.retention_days }}")
        self.assertIn("retention_days=90", one["runs"]["steps"][0]["run"])
        self.assertIn("retention_days=7", one["runs"]["steps"][0]["run"])
        self.assertIn("run_attempt", one["runs"]["steps"][1]["with"]["name"])
        self.assertIn('ART_SHA="${ART_SHA#sha256:}"', one["runs"]["steps"][2]["run"])

    def test_single_release_commit_parent_gate_prevents_reused_label(self):
        def command(*args, cwd, env=None, check=True):
            return subprocess.run(args, cwd=cwd, env=env, check=check,
                                  text=True, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            workflow_root = root / ".github/workflows"
            workflow_root.mkdir(parents=True)
            v5 = workflow_root / "product-sd1-v5-15b-needs-9992-to-14992-INERT-DRAFT.yml"
            old = workflow_root / "product-sd1-v4-o-9712-to-9992.yml"
            qual = workflow_root / "product-sd1-v5-9972-to-9992-INERT-DRAFT.yml"
            for f in (v5, old, qual):
                f.write_text("initial\n")
            (root / "README").write_text("signed sources\n")
            command("git", "init", "-q", cwd=root)
            command("git", "add", ".", cwd=root)
            command("git", "-c", "user.name=Test", "-c", "user.email=test@example.net",
                    "commit", "-qm", "Reviewed B", cwd=root)
            reviewed_b = command("git", "rev-parse", "HEAD", cwd=root).stdout.strip()
            qual_head = "c" * 40
            old.write_text("github.event.pull_request.head.sha == "
                           "'ea42929063907b8c9a5a5c27c972f16bbdb28ff1'\n")
            qual.write_text(f"github.event.pull_request.head.sha == '{qual_head}'\n")
            v5.write_text("release trigger enabled\n")
            command("git", "add", ".", cwd=root)
            command("git", "-c", "user.name=Test", "-c", "user.email=test@example.net",
                    "commit", "-qm", "Release C workflow-only", cwd=root)
            release_c = command("git", "rev-parse", "HEAD", cwd=root).stdout.strip()
            env = dict(os.environ, GITHUB_REPOSITORY="yituanxing/minicore",
                       GITHUB_EVENT_NAME="pull_request", V5_SOURCE_SHA=release_c,
                       V5_REVIEWED_PRIOR_HEAD=reviewed_b, V5_QUAL_PRIOR_HEAD=qual_head)
            guard = ROOT / "tools/ci/product_sd1_v5_release_guard.sh"
            self.assertEqual(command("bash", str(guard), cwd=root, env=env).returncode, 0)
            (root / "README").write_text("unreviewed content change\n")
            command("git", "add", ".", cwd=root)
            command("git", "-c", "user.name=Test", "-c", "user.email=test@example.net",
                    "commit", "-qm", "Unreviewed D", cwd=root)
            env["V5_SOURCE_SHA"] = command("git", "rev-parse", "HEAD", cwd=root).stdout.strip()
            rejected = command("bash", str(guard), cwd=root, env=env, check=False)
            self.assertEqual(rejected.returncode, 16)
            self.assertIn("single release commit", rejected.stderr)

    def test_retry_recovers_last_complete_artifact_without_guest_replay(self):
        run, head, model = "123456789", "b" * 40, "f" * 64
        sha0 = {"host": "0" * 64, "rtl": "1" * 64, "card": "2" * 64}
        sha1 = {"host": "3" * 64, "rtl": "4" * 64, "card": "5" * 64}
        artifact = {"id": 123, "name": f"product-sd1-v5-15b-{run}-attempt1-v5_billion_1-10012m",
                    "digest": "sha256:" + "a" * 64,
                    "workflow_run": {"id": int(run), "head_sha": head},
                    "size_in_bytes": 2048, "expired": False}

        def fake_download(argv, **_):
            p = Path(argv[argv.index("-o") + 1])
            with zipfile.ZipFile(p, "w") as z:
                z.writestr(".migration-ready",
                           "source_cycles=9992000000\nsource_host_sha256=" + sha0["host"] +
                           "\nsource_rtl_sha256=" + sha0["rtl"] +
                           "\nmodel_sha256=" + model + "\n")
            return mock.Mock(returncode=0)

        with tempfile.TemporaryDirectory() as t:
            root = Path(t) / "stage"
            root.mkdir()
            source = Path(t) / "qualified" / "9992m"
            source.mkdir(parents=True)
            with mock.patch.object(v5.subprocess, "check_output", return_value=json.dumps({"artifacts": [artifact]})), \
                 mock.patch.object(v5.subprocess, "run", side_effect=fake_download), \
                 mock.patch.object(v5, "verify_artifact_zip"), \
                 mock.patch.object(v5, "verify_checkpoint", return_value={
                     "shas": sha1, "proof": {"ordered_service_login_verified": False}}):
                result = v5.recover_prior_attempts(root, 9992, 10992, source, sha0,
                                                   model, run, head, "v5_billion_1", "UNIT_TEST")
            self.assertEqual(result["current_m"], 10012)
            self.assertEqual(result["recovered_stages"], 1)
            self.assertEqual(result["last_artifact"][0], "123")
            self.assertEqual(result["last_sha"], sha1)

    def test_retry_gapped_artifact_chain_fails_closed(self):
        run, head = "123456789", "b" * 40
        artifact = {"id": 123, "name": f"product-sd1-v5-15b-{run}-attempt1-v5_billion_1-10032m"}
        with tempfile.TemporaryDirectory() as t, \
             mock.patch.object(v5.subprocess, "check_output", return_value=json.dumps({"artifacts": [artifact]})):
            with self.assertRaisesRegex(ValueError, "missing 20M"):
                v5.recover_prior_attempts(Path(t), 9992, 10992, Path(t),
                                          {}, "f" * 64, run, head, "v5_billion_1", "UNIT_TEST")


    def test_postcross_arithmetic_reaches_expected_guard_not_unbound_variable(self):
        # Regression for official V5 job #129101101296: a single `readonly`
        # invocation expanded START before SOURCE_M had become assigned.
        # Exit at the deliberately invalid output-root gate, before RTL.
        with tempfile.TemporaryDirectory() as t:
            env = dict(os.environ,
                       AETHERCORE_SD1_V5_15B_APPROVED="QUALIFIED_V5_15B_SIGNED_9992_MODEL_MIGRATION",
                       AETHERCORE_SD1_RELAY_CONFIRM="SD1_FULL_DEBIAN_SIGNED_RELAY_V6_MIGRATED_15B",
                       AETHERCORE_SD1_V5_REVIEWED_MODEL_SHA="4" * 64,
                       AETHERCORE_VERILATOR_VERSION="5.054",
                       SIM_SETTLE_MODE="reference", VERILATOR_THREADS="1",
                       AETHERCORE_DATA_MEM_WAIT_CYCLES="0",
                       AETHERCORE_SD1_V5_OUT=str(Path(t)))
            cmd = ["bash", str(ROOT / "tools/ci/product_sd1_migrated_v5_postcross_20m.sh"),
                   "--execute-reviewed-postcross-20m", t, "10012"]
            result = subprocess.run(cmd, cwd=ROOT, env=env, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(result.returncode, 16)
            self.assertIn("stage output path invalid or exists", result.stderr)
            self.assertNotIn("unbound variable", result.stderr)


if __name__ == "__main__":
    unittest.main()
