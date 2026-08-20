from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
FAST_GATE = ROOT / ".github/workflows/fast-gate.yml"
RV64_PID1 = ROOT / ".github/workflows/rv64-minimal-initramfs-v1.yml"
INITRAMFS_BUILD = ROOT / "tools/ci/rv64_minimal_initramfs_build.sh"
HOSTED_DOC = ROOT / "docs/CI_HOSTED_RUNNERS.md"


class HostedRunnerCacheContract(unittest.TestCase):
    def test_public_pr_gates_use_hosted_linux(self):
        fast = FAST_GATE.read_text(encoding="utf-8")
        pid1 = RV64_PID1.read_text(encoding="utf-8")

        self.assertIn("runs-on: ubuntu-24.04", fast)
        self.assertIn("runs-on: ubuntu-24.04", pid1)
        self.assertNotIn("runs-on: [self-hosted, Linux, X64, minicore]", fast)
        self.assertNotIn("runs-on: [self-hosted, Linux, X64, minicore]", pid1)

    def test_hosted_gates_persist_only_explicit_cache_roots(self):
        fast = FAST_GATE.read_text(encoding="utf-8")
        pid1 = RV64_PID1.read_text(encoding="utf-8")

        for text in (fast, pid1):
            self.assertIn("uses: actions/cache@v4", text)
            self.assertIn("~/.cache/aethercore/toolchains", text)
            self.assertIn("~/.cache/coursier", text)

        self.assertIn("~/.cache/aethercore/references", fast)
        self.assertIn("~/.cache/aethercore/sources", fast)
        self.assertIn("~/.cache/aethercore/rv64/linux-build", pid1)
        self.assertIn("~/.cache/aethercore/l32/opensbi", pid1)

    def test_pid1_cache_is_revalidated_by_repository_scripts(self):
        pid1 = RV64_PID1.read_text(encoding="utf-8")

        self.assertIn("bash tools/ensure_verilator_5_024.sh", pid1)
        self.assertIn("bash tools/ensure_riscv64_linux_gcc_13_3.sh", pid1)
        self.assertIn("bash tools/ci/rv64_linux_early_build.sh", pid1)
        self.assertIn("every restored tool/software payload is revalidated", pid1)

    def test_initramfs_uses_private_copy_of_qualified_baseline_objects(self):
        script = INITRAMFS_BUILD.read_text(encoding="utf-8")

        self.assertIn('cp -a --reflink=auto "${BASELINE_OBJ}" "${OBJ_DIR}"', script)
        self.assertNotIn("cp -al", script)
        self.assertIn("baseline_object_seed=${BASELINE_OBJ}", script)

    def test_cache_ownership_is_documented(self):
        doc = HOSTED_DOC.read_text(encoding="utf-8")

        self.assertIn("Cache is an accelerator, never an authority", doc)
        self.assertIn("Normal public pull requests run only on GitHub-hosted runners", doc)
        self.assertIn("Frontier", doc)
        self.assertIn("Milestone", doc)
        self.assertIn("Freeze", doc)


if __name__ == "__main__":
    unittest.main()
