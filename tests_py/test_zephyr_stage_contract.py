from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "software" / "zephyr" / "west.yml"
APP_ROOT = ROOT / "software" / "zephyr" / "apps" / "kernel_smoke"
BRINGUP = ROOT / "docs" / "zephyr" / "BRINGUP.md"
WORKFLOW = ROOT / ".github" / "workflows" / "zephyr-host-gate.yml"


class ZephyrStageContractTest(unittest.TestCase):
    def test_manifest_pins_the_lts_revision(self) -> None:
        text = MANIFEST.read_text(encoding="utf-8")
        self.assertIn("revision: v3.7.0", text)
        self.assertIn("import: true", text)
        self.assertIn("path: minicore", text)

    def test_kernel_smoke_exercises_threads_semaphores_and_timer(self) -> None:
        source = (APP_ROOT / "src" / "main.c").read_text(encoding="utf-8")
        config = (APP_ROOT / "prj.conf").read_text(encoding="utf-8")
        cmake = (APP_ROOT / "CMakeLists.txt").read_text(encoding="utf-8")

        for token in (
            "k_thread_create",
            "k_sem_take",
            "k_sem_give",
            "k_sleep(K_MSEC(1))",
            "AETHERCORE ZEPHYR BOOT",
            "AETHERCORE ZEPHYR PASS handoffs=%d",
        ):
            self.assertIn(token, source)

        self.assertIn("CONFIG_MULTITHREADING=y", config)
        self.assertIn("CONFIG_UART_CONSOLE=y", config)
        self.assertIn("target_sources(app PRIVATE src/main.c)", cmake)

    def test_runner_policy_keeps_exploration_off_self_hosted(self) -> None:
        text = BRINGUP.read_text(encoding="utf-8")
        self.assertIn("ubuntu-latest", text)
        self.assertIn("No automatic self-hosted PR gate during exploration", text)
        self.assertIn("Full gate", text)
        self.assertIn("cancel-in-progress", text)

    def test_host_gate_never_uses_the_minicore_runner(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("runs-on: ubuntu-latest", text)
        self.assertIn("timeout-minutes: 5", text)
        self.assertIn("cancel-in-progress: true", text)
        self.assertNotIn("self-hosted", text)
        self.assertNotIn("pull_request:", text)


if __name__ == "__main__":
    unittest.main()
