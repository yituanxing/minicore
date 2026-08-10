from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"


def pull_request_paths(path: Path) -> list[str]:
    text = path.read_text()
    marker = "  pull_request:\n    paths:\n"
    if marker not in text:
        return []
    block = text.split(marker, 1)[1].split("  workflow_dispatch:", 1)[0]
    paths = []
    for line in block.splitlines():
        if line.startswith("      - "):
            paths.append(line[len("      - ") :].strip())
    return paths


class L32CiTopologyContractTest(unittest.TestCase):
    def test_deep_linux_runtime_is_the_canonical_pr_hardware_signoff(self):
        path = WORKFLOWS / "l32-busybox-build.yml"
        text = path.read_text()
        paths = pull_request_paths(path)

        for required in (
            "src/main/scala/aethercore/**",
            "build.mill",
            "mill",
            "tools/ensure_verilator_5_024.sh",
            "Makefile.l32-linux-boot",
            "sim/opensbi_boot_main.cpp",
            "sim/opensbi_forkserver_main.cpp",
            ".github/workflows/l32-busybox-build.yml",
        ):
            self.assertIn(required, paths)

        self.assertIn("if: ${{ github.event_name == 'pull_request' }}", text)
        self.assertIn("if: ${{ github.event_name == 'workflow_dispatch' }}", text)
        self.assertNotIn("github.event.pull_request.draft", text)
        self.assertIn("Run cumulative Linux functional matrix from warm shell", text)
        self.assertIn("python3 tools/ci/l32_linux_runtime_suite.py verify-log", text)

    def test_prefix_milestones_do_not_shadow_shared_hardware_changes(self):
        prefix_workflows = (
            "l32-opensbi.yml",
            "l32-linux-handoff.yml",
            "l32-linux-boot.yml",
            "l32-linux-deeper-boot.yml",
            "l32-minimal-initramfs.yml",
        )

        for filename in prefix_workflows:
            with self.subTest(workflow=filename):
                path = WORKFLOWS / filename
                text = path.read_text()
                paths = pull_request_paths(path)
                self.assertIn("  workflow_dispatch:", text)
                self.assertIn(f".github/workflows/{filename}", paths)
                self.assertFalse(
                    any(item.startswith("src/main/scala/aethercore/") for item in paths),
                    msg=f"{filename} must leave shared AetherCore hardware PR signoff to l32-busybox-build.yml",
                )

    def test_kernel_init_remains_historical_manual_self_validation(self):
        path = WORKFLOWS / "l32-linux-kernel-init.yml"
        text = path.read_text()
        self.assertEqual(
            pull_request_paths(path),
            [".github/workflows/l32-linux-kernel-init.yml"],
        )
        self.assertIn("  workflow_dispatch:", text)


if __name__ == "__main__":
    unittest.main()
