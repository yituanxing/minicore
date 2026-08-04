from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "west.yml"
APP_ROOT = ROOT / "software" / "zephyr" / "apps" / "kernel_smoke"
BRINGUP = ROOT / "docs" / "zephyr" / "BRINGUP.md"
WORKFLOW = ROOT / ".github" / "workflows" / "zephyr-host-gate.yml"
BUILD_SCRIPT = ROOT / "tools" / "ci" / "zephyr_host_build.sh"
SOC_CMAKE = ROOT / "software" / "zephyr" / "soc" / "aethercore" / "CMakeLists.txt"


class ZephyrStageContractTest(unittest.TestCase):
    def test_manifest_pins_the_lts_revision(self) -> None:
        text = MANIFEST.read_text(encoding="utf-8")
        self.assertIn("revision: v3.7.2", text)
        self.assertIn("import: true", text)
        self.assertIn("path: minicore", text)
        self.assertFalse((ROOT / "software" / "zephyr" / "west.yml").exists())

    def test_kernel_smoke_exercises_threads_semaphores_timer_and_exit(self) -> None:
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
            "aethercore_exit(0U)",
        ):
            self.assertIn(token, source)

        self.assertIn("#include <aethercore/exit.h>", source)
        self.assertNotIn("0x10000008", source)
        for option in (
            "CONFIG_MULTITHREADING=y",
            "CONFIG_UART_CONSOLE=y",
            "CONFIG_UART_AETHERCORE=y",
            "CONFIG_AETHERCORE_SIM_EXIT=y",
        ):
            self.assertIn(option, config)
        self.assertIn("target_sources(app PRIVATE src/main.c)", cmake)

    def test_soc_selects_the_generic_riscv_linker_script(self) -> None:
        text = SOC_CMAKE.read_text(encoding="utf-8")
        self.assertIn(
            "set(SOC_LINKER_SCRIPT ${ZEPHYR_BASE}/include/zephyr/arch/riscv/common/linker.ld CACHE INTERNAL \"\")",
            text,
        )

    def test_runner_policy_keeps_exploration_off_self_hosted(self) -> None:
        text = BRINGUP.read_text(encoding="utf-8")
        self.assertIn("ubuntu-latest", text)
        self.assertIn("No automatic self-hosted PR gate during exploration", text)
        self.assertIn("Full gate", text)
        self.assertIn("cancel-in-progress", text)

    def test_host_gate_builds_without_the_minicore_runner(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("runs-on: ubuntu-latest", text)
        self.assertIn("timeout-minutes: 20", text)
        self.assertIn("cancel-in-progress: true", text)
        self.assertIn("path: minicore", text)
        self.assertIn("app-path: minicore", text)
        self.assertIn("actions/setup-java@v4", text)
        self.assertIn('java-version: "21"', text)
        self.assertIn("./mill aethercore.compile", text)
        self.assertIn("src/main/scala/aethercore/core/MachinePlicMmio.scala", text)
        self.assertIn("src/test/scala/aethercore/AetherCoreInterruptPlatformSpec.scala", text)
        self.assertIn("software/freertos/aethercore/platform.c", text)
        self.assertIn("zephyrproject-rtos/action-zephyr-setup@v1", text)
        self.assertIn("sdk-version: 0.16.9", text)
        self.assertIn("toolchains: riscv64-zephyr-elf", text)
        self.assertIn("bash tools/ci/zephyr_host_build.sh", text)
        self.assertIn("include-hidden-files: true", text)
        self.assertIn("minicore/build/zephyr-host/zephyr/.config", text)
        self.assertNotIn("self-hosted", text)
        self.assertNotIn("pull_request:", text)

    def test_host_build_freezes_outputs_isa_and_exit_contract(self) -> None:
        text = BUILD_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("west build -p always", text)
        self.assertIn("-b aethercore_sim", text)
        self.assertIn("zephyr/zephyr.elf", text)
        self.assertIn("zephyr/zephyr.bin", text)
        self.assertIn("CONFIG_RISCV_ISA_EXT_M=y", text)
        self.assertIn("CONFIG_RISCV_ISA_EXT_(A|C)=y", text)
        self.assertIn("CONFIG_AETHERCORE_SIM_EXIT=y", text)
        self.assertIn("test-exit@10000008", text)
        self.assertIn("grep -Fq 'aethercore_exit'", text)
        self.assertIn("exit_address=0x10000008", text)
        self.assertIn("contract=zephyr-v3.7.2-aethercore-z1-host-build-v1", text)


if __name__ == "__main__":
    unittest.main()
