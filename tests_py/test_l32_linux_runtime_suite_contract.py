from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SUITE_PATH = ROOT / "tools/ci/l32_linux_runtime_suite.py"
PROBE_SOURCE = ROOT / "software/l32_busybox/runtime_probe.c"
PROBE_BUILD = ROOT / "tools/ci/l32_runtime_probe_build.sh"
INITRAMFS_BUILD = ROOT / "tools/ci/l32_busybox_initramfs_build.sh"
WORKFLOW = ROOT / ".github/workflows/l32-busybox-build.yml"


def load_suite():
    spec = spec_from_file_location("l32_linux_runtime_suite", SUITE_PATH)
    assert spec is not None and spec.loader is not None
    module = module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class L32LinuxRuntimeSuiteContract(unittest.TestCase):
    def test_runtime_matrix_is_cumulative_and_cpu_focused(self):
        suite = load_suite()
        by_id = {case.case_id: case for case in suite.CASES}
        self.assertEqual(
            list(by_id),
            ["builtin", "subshell", "pipeline", "vfs", "vm", "cow", "signal", "time"],
        )
        self.assertEqual(by_id["vfs"].level, "L2-vfs")
        self.assertEqual(by_id["vm"].level, "L3-vm")
        self.assertEqual(by_id["cow"].level, "L4-cow")
        self.assertEqual(by_id["signal"].level, "L5-signal")
        self.assertEqual(by_id["time"].level, "L6-time")
        for case_id in ("vfs", "vm", "cow", "signal", "time"):
            case = by_id[case_id]
            self.assertEqual(case.command, f"/bin/l32-runtime-probe {case_id}")
            self.assertTrue(case.marker.startswith("L32_PROBE_"))
            self.assertNotIn(case.marker, case.command)

    def test_probe_covers_vfs_vm_cow_signal_and_time_semantics(self):
        text = PROBE_SOURCE.read_text()
        for required in (
            "open(path_a, O_CREAT | O_TRUNC | O_RDWR",
            "write_all(fd, first",
            "lseek(fd, 0, SEEK_SET)",
            "fstat(fd, &st)",
            "rename(path_a, path_b)",
            "O_WRONLY | O_APPEND",
            "unlink(path_b)",
            "errno != ENOENT",
            "MAP_PRIVATE | MAP_ANONYMOUS",
            "mprotect(p + page, page, PROT_READ)",
            "munmap(p, len)",
            "pid_t pid = fork()",
            "waitpid(pid, &status, 0)",
            "parent-cow",
            "sigaction(SIGUSR1",
            "kill(getpid(), SIGUSR1)",
            "clock_gettime(CLOCK_MONOTONIC",
            "nanosleep(&req, &req)",
            "L32_PROBE_VFS_PASS",
            "L32_PROBE_VM_PASS",
            "L32_PROBE_COW_PASS",
            "L32_PROBE_SIGNAL_PASS",
            "L32_PROBE_TIME_PASS",
        ):
            self.assertIn(required, text)

    def test_probe_is_static_qualified_and_embedded_in_initramfs(self):
        build = PROBE_BUILD.read_text()
        for required in (
            "l32-musl-real-gcc",
            "-fno-pie -no-pie",
            "statically linked",
            "expected ELF32 little-endian",
            "L32_RUNTIME_PROBE_BUILD_RESULT: status=PASS",
        ):
            self.assertIn(required, build)

        initramfs = INITRAMFS_BUILD.read_text()
        for required in (
            'PROBE_ELF="${PROBE_BUILD_DIR}/l32-runtime-probe"',
            "L32_RUNTIME_PROBE_BUILD_RESULT: status=PASS",
            "file /bin/l32-runtime-probe ${PROBE_ELF} 0755 0 0",
            'OBJ_MARKER="${OBJ_DIR}/.aethercore-object-inputs"',
            "Preserve this variant's Kbuild object tree",
        ):
            self.assertIn(required, initramfs)
        self.assertNotIn('rm -rf "${OBJ_DIR}"\nmkdir -p "${OBJ_DIR}"', initramfs)

    def test_final_markers_cannot_be_satisfied_by_tty_command_echo(self):
        suite = load_suite()
        for case in suite.CASES:
            with self.subTest(case_id=case.case_id):
                self.assertNotIn(case.marker, case.command)

    def test_suite_rejects_kernel_health_failures_and_requires_every_case(self):
        suite = load_suite()
        lines = [f"L32_FORKSERVER_READY cycles=1 commits=1 seip=1 cases={len(suite.CASES)}"]
        for case in suite.CASES:
            lines.append(case.marker)
            lines.append(
                f"L32_FORKSERVER_CASE_PASS id={case.case_id} "
                "delta-cycles=1 delta-commits=1 seip-delta=1"
            )
        lines.extend(
            [
                "L32 BUSYBOX PIPE CHILD OK",
                "L32 BUSYBOX PIPE PARENT OK",
                f"L32_FORKSERVER_PASS cases={len(suite.CASES)} boot-cycles=1",
            ]
        )
        good = "\n".join(lines) + "\n"
        suite.verify_text(good)

        for bad in suite.BAD_KERNEL_MARKERS:
            with self.subTest(bad=bad):
                with self.assertRaises(RuntimeError):
                    suite.verify_text(good + bad + "\n")

        with self.assertRaises(RuntimeError):
            suite.verify_text(
                good.replace(
                    "L32_FORKSERVER_CASE_PASS id=vm ",
                    "L32_FORKSERVER_CASE_MISSING id=vm ",
                )
            )

    def test_batch_writer_preserves_one_case_per_tsv_line(self):
        suite = load_suite()
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "workloads.tsv"
            suite.write_batch(path)
            rows = path.read_text().splitlines()
        self.assertEqual(len(rows), len(suite.CASES))
        self.assertEqual(
            [row.split("\t", 1)[0] for row in rows],
            [case.case_id for case in suite.CASES],
        )
        self.assertTrue(all(row.count("\t") == 2 for row in rows))

    def test_workflow_routes_warm_batch_through_runtime_suite(self):
        text = WORKFLOW.read_text()
        self.assertIn("tools/ci/l32_linux_runtime_suite.py", text)
        self.assertIn("tools/ci/l32_runtime_probe_build.sh", text)
        self.assertIn('write-batch "$batch_file"', text)
        self.assertIn('verify-log "$log"', text)
        self.assertIn("tests_py.test_l32_linux_runtime_suite_contract", text)


if __name__ == "__main__":
    unittest.main()
