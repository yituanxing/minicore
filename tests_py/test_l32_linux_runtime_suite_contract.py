from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SUITE_PATH = ROOT / "tools/ci/l32_linux_runtime_suite.py"
WORKFLOW = ROOT / ".github/workflows/l32-busybox-build.yml"


def load_suite():
    spec = spec_from_file_location("l32_linux_runtime_suite", SUITE_PATH)
    assert spec is not None and spec.loader is not None
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class L32LinuxRuntimeSuiteContract(unittest.TestCase):
    def test_vfs_case_is_self_checking_and_stays_within_minimal_ash(self):
        suite = load_suite()
        cases = {case_id: (marker, command) for case_id, marker, command in suite.CASES}
        self.assertIn("vfs", cases)
        marker, command = cases["vfs"]
        self.assertEqual(marker, "L32 BUSYBOX VFS OK")
        for required in (
            "set -eu",
            "/tmp/l32-vfs-runtime-file",
            "printf 'alpha\\nbeta\\n' >",
            "IFS= read -r a",
            "IFS= read -r b",
            '[ "$a" = alpha ]',
            '[ "$b" = beta ]',
            '[ -f "$f" ]',
            "[ ! -e /tmp/l32-vfs-runtime-missing ]",
            "printf 'gamma\\n' >>",
            "IFS= read -r a2",
            "IFS= read -r b2",
            "IFS= read -r c2",
            "! IFS= read -r extra",
            '[ "$a2" = alpha ]',
            '[ "$b2" = beta ]',
            '[ "$c2" = gamma ]',
            "L32 BUSYBOX VFS %s\\n",
        ):
            self.assertIn(required, command)
        for forbidden in (
            "/bin/busybox rm",
            "/bin/busybox mkdir",
            "/bin/busybox mv",
            "/bin/busybox cat",
            "/bin/busybox tail",
            "$(('",
            "$((",
        ):
            self.assertNotIn(forbidden, command)
        self.assertNotIn(marker, command)
        self.assertTrue(command.index("set -eu") < command.index("L32 BUSYBOX VFS %s\\n"))

    def test_final_markers_cannot_be_satisfied_by_tty_command_echo(self):
        suite = load_suite()
        for case_id, marker, command in suite.CASES:
            with self.subTest(case_id=case_id):
                self.assertNotIn(marker, command)

    def test_suite_rejects_kernel_health_failures_and_requires_every_case(self):
        suite = load_suite()
        lines = ["L32_FORKSERVER_READY cycles=1 commits=1 seip=1 cases=4"]
        for case_id, marker, _ in suite.CASES:
            lines.append(marker)
            lines.append(f"L32_FORKSERVER_CASE_PASS id={case_id} delta-cycles=1 delta-commits=1 seip-delta=1")
        lines.extend(
            [
                "L32 BUSYBOX PIPE CHILD OK",
                "L32 BUSYBOX PIPE PARENT OK",
                "L32_FORKSERVER_PASS cases=4 boot-cycles=1",
            ]
        )
        good = "\n".join(lines) + "\n"
        suite.verify_text(good)

        for bad in suite.BAD_KERNEL_MARKERS:
            with self.subTest(bad=bad):
                with self.assertRaises(RuntimeError):
                    suite.verify_text(good + bad + "\n")

        with self.assertRaises(RuntimeError):
            suite.verify_text(good.replace("L32_FORKSERVER_CASE_PASS id=vfs ", "L32_FORKSERVER_CASE_MISSING id=vfs "))

    def test_batch_writer_preserves_one_case_per_tsv_line(self):
        suite = load_suite()
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "workloads.tsv"
            suite.write_batch(path)
            rows = path.read_text().splitlines()
        self.assertEqual(len(rows), len(suite.CASES))
        self.assertEqual([row.split("\t", 1)[0] for row in rows], [case[0] for case in suite.CASES])
        self.assertTrue(all(row.count("\t") == 2 for row in rows))

    def test_workflow_routes_warm_batch_through_runtime_suite(self):
        text = WORKFLOW.read_text()
        self.assertIn("tools/ci/l32_linux_runtime_suite.py", text)
        self.assertIn('write-batch "$batch_file"', text)
        self.assertIn('verify-log "$log"', text)
        self.assertIn("tests_py.test_l32_linux_runtime_suite_contract", text)


if __name__ == "__main__":
    unittest.main()
