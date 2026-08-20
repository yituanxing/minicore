from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
FAST_GATE = ROOT / ".github/workflows/fast-gate.yml"
RV64_PID1 = ROOT / ".github/workflows/rv64-minimal-initramfs-v1.yml"
L32_LINUX = ROOT / ".github/workflows/l32-linux-build.yml"
L32_HANDOFF = ROOT / ".github/workflows/l32-linux-handoff.yml"
INITRAMFS_BUILD = ROOT / "tools/ci/rv64_minimal_initramfs_build.sh"
HOSTED_DOC = ROOT / "docs/CI_HOSTED_RUNNERS.md"


class HostedRunnerCacheContract(unittest.TestCase):
    def test_migrated_public_pr_gates_use_hosted_linux(self):
        workflows = [
            FAST_GATE.read_text(encoding="utf-8"),
            RV64_PID1.read_text(encoding="utf-8"),
            L32_LINUX.read_text(encoding="utf-8"),
            L32_HANDOFF.read_text(encoding="utf-8"),
        ]

        for text in workflows:
            self.assertIn("runs-on: ubuntu-24.04", text)
            self.assertNotIn("runs-on: [self-hosted, Linux, X64, minicore]", text)
            self.assertIn("clean: true", text)

    def test_hosted_gates_persist_only_explicit_cache_roots(self):
        fast = FAST_GATE.read_text(encoding="utf-8")
        pid1 = RV64_PID1.read_text(encoding="utf-8")
        l32 = L32_LINUX.read_text(encoding="utf-8")
        handoff = L32_HANDOFF.read_text(encoding="utf-8")

        # Fast Gate and handoff still use the combined restore/post-save action.
        for text in (fast, handoff):
            self.assertIn("uses: actions/cache@v4", text)

        # Long software producers use explicit restore/save checkpoints so a
        # later runtime failure cannot discard already validated prerequisites.
        for text in (pid1, l32):
            self.assertIn("uses: actions/cache/restore@v4", text)
            self.assertIn("uses: actions/cache/save@v4", text)

        for text in (fast, pid1, l32, handoff):
            self.assertIn("~/.cache/aethercore/toolchains", text)

        for text in (fast, pid1, handoff):
            self.assertIn("~/.cache/coursier", text)

        self.assertIn("~/.cache/aethercore/references", fast)
        self.assertIn("~/.cache/aethercore/sources", fast)
        self.assertIn("~/.cache/aethercore/rv64/linux-build", pid1)
        self.assertIn("~/.cache/aethercore/l32/opensbi", pid1)
        self.assertIn("~/.cache/aethercore/l32/linux", l32)
        self.assertIn("build/l32-linux", l32)
        self.assertIn("~/.cache/aethercore/l32/opensbi", handoff)
        self.assertIn("build/l32-linux", handoff)

    def test_pid1_cache_is_revalidated_by_repository_scripts(self):
        pid1 = RV64_PID1.read_text(encoding="utf-8")

        # Restored caches are accelerators only. Their corresponding owner
        # scripts always run and validate version/SHA/recipe before use.
        self.assertIn("bash tools/ensure_verilator_5_024.sh", pid1)
        self.assertIn("bash tools/ensure_riscv64_linux_gcc_13_3.sh", pid1)
        self.assertIn("bash tools/ci/rv64_linux_early_build.sh", pid1)
        self.assertIn("immutable tools are checkpointed immediately after repository-owned validation", pid1)

        # A compiled simulator cache can save rebuild time, but qualification
        # still executes the real Linux PID1/UART/STIP/SEIP run on every gate.
        self.assertIn("Run real RV64 PID 1 UART interrupt proof", pid1)
        self.assertIn('MILESTONE="RV64 USER UART IRQ OK"', pid1)
        self.assertIn("MIN_STIP=1", pid1)
        self.assertIn("MIN_SEIP=1", pid1)
        self.assertIn("the real PID1/UART/STIP/SEIP proof is always executed for qualification", pid1)

    def test_l32_cached_outputs_are_still_checked_before_use(self):
        l32 = L32_LINUX.read_text(encoding="utf-8")
        handoff = L32_HANDOFF.read_text(encoding="utf-8")

        self.assertIn("tools/ci/l32_linux_cache_key.sh check build/l32-linux", l32)
        self.assertIn("tools/ci/l32_linux_cache_key.sh mark build/l32-linux", l32)
        self.assertIn("tools/ci/l32_linux_cache_key.sh check build/l32-linux", handoff)
        self.assertIn("L32_LINUX_IMAGE_SHA256", handoff)
        self.assertIn("L32_LINUX_VMLINUX_SHA256", handoff)
        self.assertIn("L32_LINUX_CONFIG_SHA256", handoff)

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
