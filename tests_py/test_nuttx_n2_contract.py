from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
N2_SCRIPT = ROOT / "tools" / "ci" / "nuttx_n2_boot.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "nuttx-stage.yml"
ELABORATE = ROOT / "src" / "main" / "scala" / "aethercore" / "ElaborateZephyr.scala"


class NuttxN2ContractTest(unittest.TestCase):
    def test_n2_is_bounded_polling_uart_nsh_qualification(self) -> None:
        text = N2_SCRIPT.read_text()
        required = (
            "make_aethercore_nuttx_overlay.py",
            "CONFIG_AETHERCORE_UART",
            "CONFIG_RAM_START=0x80000000",
            "aethercore_serialinit",
            "riscv64-unknown-elf-objcopy -O binary nuttx nuttx.bin",
            "--self-check-exit",
            "AETHERCORE_NUTTX_N2_MAX_CYCLES",
            "grep -Fq 'nsh>'",
            "bounded-timeout-after-nsh-prompt",
        )
        for fragment in required:
            self.assertIn(fragment, text)
        self.assertIn('[[ "${rc}" -eq 2 ]]', text)
        self.assertIn('[[ -s nuttx.bin ]]', text)
        self.assertNotIn("--rx-byte", text)

    def test_n1_and_n2_share_one_bounded_self_hosted_job(self) -> None:
        text = WORKFLOW.read_text()
        runner = "runs-on: [self-hosted, Linux, X64, minicore]"
        self.assertEqual(text.count(runner), 1)
        self.assertEqual(text.count("timeout-minutes: 45"), 1)
        self.assertLess(
            text.index("Build pinned NuttX N1 image"),
            text.index("Boot pinned NuttX N2 on AetherCore"),
        )
        self.assertIn("${HOME}/.cache/aethercore", text)
        self.assertIn("group: nuttx-stage-${{ github.ref }}", text)
        self.assertIn("cancel-in-progress: true", text)
        self.assertNotIn("ubuntu-latest", text)
        self.assertNotIn("Fast Gate", text)
        self.assertNotIn("full-validation", text)

    def test_n2_uses_the_os_capable_rv32im_simulation_top(self) -> None:
        text = ELABORATE.read_text()
        self.assertIn("CoreProfiles.rv32imSoftware", text)
        self.assertIn("stopOnTrap = false", text)
        self.assertIn("withMachineInterruptPlatform = true", text)
        self.assertIn("stopOnWfi = false", text)


if __name__ == "__main__":
    unittest.main()
