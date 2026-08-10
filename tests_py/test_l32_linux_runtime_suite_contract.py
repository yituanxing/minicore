from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SUITE_PATH = ROOT / "tools/ci/l32_linux_runtime_suite.py"
PROBE_SOURCE = ROOT / "software/l32_busybox/runtime_probe.c"
PROBE_BUILD = ROOT / "tools/ci/l32_runtime_probe_build.sh"
REAL_BUILD = ROOT / "tools/ci/l32_real_programs_build.sh"
REAL_MANIFEST = ROOT / "software/l32_real/manifest.env"
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
            [
                "builtin", "subshell", "pipeline", "vfs", "fd", "dir", "vm", "cow", "signal", "time",
                "unix", "poll", "futex", "lua-real", "sqlite-real", "bash-real",
                "busybox-awk", "busybox-gzip", "busybox-tar", "busybox-ed", "busybox-vi",
            ],
        )
        for case_id, level in (
            ("vfs", "L2-vfs"), ("fd", "L2-fd"), ("dir", "L2-vfs"),
            ("vm", "L3-vm"), ("cow", "L4-cow"), ("signal", "L5-signal"), ("time", "L6-time"),
            ("unix", "L6-ipc"), ("poll", "L6-blocking"), ("futex", "L6-futex"),
            ("lua-real", "L7-real"), ("sqlite-real", "L7-real"), ("bash-real", "L7-real"),
            ("busybox-awk", "L8-busybox-real"), ("busybox-gzip", "L8-busybox-real"),
            ("busybox-tar", "L8-busybox-real"), ("busybox-ed", "L8-busybox-real"),
            ("busybox-vi", "L8-busybox-real"),
        ):
            self.assertEqual(by_id[case_id].level, level)
        for case_id in ("vfs", "fd", "dir", "vm", "cow", "signal", "time", "unix", "poll", "futex"):
            case = by_id[case_id]
            self.assertEqual(case.command, f"/bin/l32-runtime-probe {case_id}")
            self.assertTrue(case.marker.startswith("L32_PROBE_"))
        self.assertEqual(by_id["lua-real"].command, "/opt/l32/lua /opt/l32/lua-smoke.lua")
        self.assertEqual(by_id["sqlite-real"].command, "/opt/l32/sqlite-smoke")
        self.assertEqual(by_id["bash-real"].command, "/opt/l32/bash /opt/l32/bash-smoke.sh")
        for case_id in ("busybox-awk", "busybox-gzip", "busybox-tar", "busybox-ed", "busybox-vi"):
            self.assertIn("/opt/l32/busybox-real", by_id[case_id].command)

    def test_probe_covers_linux_common_runtime_semantics(self):
        text = PROBE_SOURCE.read_text()
        for required in (
            "open(path_a, O_CREAT | O_TRUNC | O_RDWR", "write_all(fd, first", "lseek(fd, 0, SEEK_SET)",
            "fstat(fd, &st)", "rename(path_a, path_b)", "O_WRONLY | O_APPEND", "unlink(path_b)",
            "errno != ENOENT", "dup(fd)", "dup2(dupfd, target)", "FD_CLOEXEC",
            "openat(dfd, \"a\"", "fstatat(dfd, \"a\"", "renameat(dfd, \"a\", dfd, \"b\")",
            "opendir(dirpath)", "readdir(dir)", "unlinkat(dfd, \"b\", 0)",
            "MAP_PRIVATE | MAP_ANONYMOUS", "mprotect(p + page, page, PROT_READ)", "munmap(p, len)",
            "pid_t pid = fork()", "waitpid(pid, &status, 0)", "parent-cow", "sigaction(SIGUSR1",
            "kill(getpid(), SIGUSR1)", "clock_gettime(CLOCK_MONOTONIC", "nanosleep(&req, &req)",
            "socketpair(AF_UNIX, SOCK_STREAM", "poll(&fds, 1, 1000)", "SYS_futex", "L32_FUTEX_WAIT", "L32_FUTEX_WAKE",
            "L32_PROBE_VFS_PASS", "L32_PROBE_FD_PASS", "L32_PROBE_DIR_PASS", "L32_PROBE_VM_PASS",
            "L32_PROBE_COW_PASS", "L32_PROBE_SIGNAL_PASS", "L32_PROBE_TIME_PASS", "L32_PROBE_UNIX_PASS",
            "L32_PROBE_POLL_PASS", "L32_PROBE_FUTEX_PASS",
        ):
            self.assertIn(required, text)

    def test_real_programs_are_pinned_static_and_embedded(self):
        manifest = REAL_MANIFEST.read_text()
        for required in ("LUA_VERSION=5.5.0", "SQLITE_VERSION=3.53.3", "BASH_VERSION=5.3", "SHA256="):
            self.assertIn(required, manifest)
        build = REAL_BUILD.read_text()
        for required in (
            "lua-${LUA_VERSION}", "sqlite-amalgamation-${SQLITE_AMALGAMATION_ID}", "bash-${BASH_VERSION}",
            "SQLITE_THREADSAFE=0", "--enable-static-link", "check_elf", "L32_REAL_PROGRAMS_BUILD_RESULT: status=PASS",
            'build_triplet="$(sh support/config.guess)"',
            "sed -i -E 's/(^|[[:space:]])-rdynamic([[:space:]]|$)/ /g' Makefile",
            "generated Bash target Makefile still contains -rdynamic",
            "CONFIG_AWK", "CONFIG_GZIP", "CONFIG_GUNZIP", "CONFIG_TAR", "CONFIG_ED", "CONFIG_VI",
            'cp "${BUSYBOX_REAL_BUILD}/busybox" "${BUILD_DIR}/busybox-real"',
        ):
            self.assertIn(required, build)
        initramfs = INITRAMFS_BUILD.read_text()
        for required in (
            "L32_REAL_PROGRAMS_BUILD_RESULT: status=PASS",
            "file /opt/l32/lua ${LUA_ELF} 0755 0 0",
            "file /opt/l32/sqlite-smoke ${SQLITE_ELF} 0755 0 0",
            "file /opt/l32/bash ${BASH_ELF} 0755 0 0",
            "file /opt/l32/busybox-real ${BUSYBOX_REAL_ELF} 0755 0 0",
        ):
            self.assertIn(required, initramfs)

    def test_probe_is_static_qualified_and_embedded_in_initramfs(self):
        build = PROBE_BUILD.read_text()
        for required in ("l32-musl-real-gcc", "-fno-pie -no-pie", "statically linked", "expected ELF32 little-endian", "L32_RUNTIME_PROBE_BUILD_RESULT: status=PASS"):
            self.assertIn(required, build)
        initramfs = INITRAMFS_BUILD.read_text()
        for required in (
            'PROBE_ELF="${PROBE_BUILD_DIR}/l32-runtime-probe"', "L32_RUNTIME_PROBE_BUILD_RESULT: status=PASS",
            "file /bin/l32-runtime-probe ${PROBE_ELF} 0755 0 0", 'OBJ_MARKER="${OBJ_DIR}/.aethercore-object-inputs"',
            "Preserve this variant's Kbuild object tree",
        ):
            self.assertIn(required, initramfs)

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
            lines.append(f"L32_FORKSERVER_CASE_PASS id={case.case_id} delta-cycles=1 delta-commits=1 seip-delta=1")
        lines.extend(["L32 BUSYBOX PIPE CHILD OK", "L32 BUSYBOX PIPE PARENT OK", f"L32_FORKSERVER_PASS cases={len(suite.CASES)} boot-cycles=1"])
        good = "\n".join(lines) + "\n"
        suite.verify_text(good)
        for bad in suite.BAD_KERNEL_MARKERS:
            with self.subTest(bad=bad):
                with self.assertRaises(RuntimeError):
                    suite.verify_text(good + bad + "\n")
        with self.assertRaises(RuntimeError):
            suite.verify_text(good.replace("L32_FORKSERVER_CASE_PASS id=vm ", "L32_FORKSERVER_CASE_MISSING id=vm "))

    def test_batch_writer_preserves_one_case_per_tsv_line(self):
        suite = load_suite()
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "workloads.tsv"
            suite.write_batch(path)
            rows = path.read_text().splitlines()
        self.assertEqual(len(rows), len(suite.CASES))
        self.assertEqual([row.split("\t", 1)[0] for row in rows], [case.case_id for case in suite.CASES])
        self.assertTrue(all(row.count("\t") == 2 for row in rows))

    def test_workflow_routes_warm_batch_through_runtime_suite(self):
        text = WORKFLOW.read_text()
        for required in (
            "tools/ci/l32_linux_runtime_suite.py", "tools/ci/l32_runtime_probe_build.sh",
            'write-batch "$batch_file"', 'verify-log "$log"', "tests_py.test_l32_linux_runtime_suite_contract",
        ):
            self.assertIn(required, text)


if __name__ == "__main__":
    unittest.main()
