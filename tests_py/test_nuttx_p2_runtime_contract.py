from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
TOP = ROOT / "src" / "main" / "scala" / "aethercore" / "sim" / "AetherCoreNuttXProtectedSimTop.scala"
ELABORATOR = ROOT / "src" / "main" / "scala" / "aethercore" / "ElaborateNuttXProtected.scala"
GENERATOR = ROOT / "tools" / "make_nuttx_protected_runner.py"
SHARED_RUNNER = ROOT / "sim" / "sim_main.cpp"
P2_SCRIPT = ROOT / "tools" / "ci" / "nuttx_p2_protected_boot.sh"


def generate_runner() -> str:
    with tempfile.TemporaryDirectory() as directory:
        output = Path(directory) / "sim_main_nuttx_protected.cpp"
        subprocess.run(
            ["python3", str(GENERATOR), str(SHARED_RUNNER), str(output)],
            check=True,
            cwd=ROOT,
        )
        return output.read_text()


class NuttxP2RuntimeContractTest(unittest.TestCase):
    def test_sim_top_combines_umode_pmp_timer_and_external_interrupts(self) -> None:
        text = TOP.read_text()
        for fragment in (
            "CoreProfiles.rv32imuPmpOsSoftware",
            "RV32IM + Zicsr + Zifencei",
            "stopOnTrap = false",
            "withMachineInterruptPlatform = true",
            "stopOnWfi = false",
        ):
            self.assertIn(fragment, text)
        self.assertNotIn("CoreProfiles.rv32imuPmpSoftware,", text)
        self.assertIn("AetherCoreNuttXProtectedSimTop", ELABORATOR.read_text())

    def test_runner_requires_architectural_user_mode_evidence(self) -> None:
        text = generate_runner()
        for fragment in (
            '#include "VAetherCoreNuttXProtectedSimTop.h"',
            "VAetherCoreNuttXProtectedSimTop top",
            "kUserTextBase = 0x80040000ULL",
            "kUserTextLimit = 0x80080000ULL",
            "kEnvironmentCallFromU = 8ULL",
            'kProtectedPrompt[] = "nsh>"',
            "UMODE_EVIDENCE user-commits=",
            "FAIL: no instruction retired from protected user text",
            "FAIL: no ECALL-from-U trap was observed",
            "FAIL: no MRET user transition was observed",
            "top.io_commit_exceptionCause",
            "top.io_commit_exceptionValue",
        ):
            self.assertIn(fragment, text)
        self.assertNotIn("VAetherCoreSimTop", text)

    def test_uart_injection_arms_from_the_real_nsh_prompt(self) -> None:
        text = generate_runner()
        for fragment in (
            "--rx-after-uart",
            "options.rxAfterUart = argv[++i]",
            "--rx-after-uart requires at least one --rx-byte",
            "uart.find(*options.rxAfterUart)",
            "const bool rxArmed",
        ):
            self.assertIn(fragment, text)

    def test_hello_command_must_add_post_prompt_umode_activity(self) -> None:
        text = generate_runner()
        for fragment in (
            "bool protectedCommandStarted = false",
            "commandStartUserCommits = userCommits",
            "commandStartUserEnvironmentCalls = userEnvironmentCalls",
            "commandStartMretCommits = mretCommits",
            "UMODE_COMMAND_EVIDENCE user-commits=",
            "commandUserCommits == 0",
            "commandUserEnvironmentCalls == 0",
            "commandMretCommits == 0",
            "FAIL: hello command retired no user instruction",
            "FAIL: hello command issued no ECALL-from-U",
            "FAIL: hello command observed no MRET return",
        ):
            self.assertIn(fragment, text)

    def test_runner_stops_at_the_returned_second_nsh_prompt(self) -> None:
        text = generate_runner()
        for fragment in (
            "bool protectedComplete = false",
            "!protectedComplete; ++cycles",
            "uart.find(kProtectedPrompt, pos)",
            "promptCount >= 2",
            "rxIndex == options.rxBytes.size()",
            "if (protectedComplete)",
            "PASS: protected NSH returned after U-mode hello",
        ):
            self.assertIn(fragment, text)

    def test_runtime_evidence_is_not_uart_only(self) -> None:
        text = GENERATOR.read_text()
        self.assertIn("userCommits == 0", text)
        self.assertIn("userEnvironmentCalls == 0", text)
        self.assertIn("mretCommits == 0", text)
        self.assertIn("commandUserCommits == 0", text)
        self.assertIn("commandUserEnvironmentCalls == 0", text)
        self.assertIn("commandMretCommits == 0", text)
        self.assertIn("protectedCommitPc >= kUserTextBase", text)
        self.assertIn("exceptionCause == kEnvironmentCallFromU", text)

    def test_p2_script_validates_the_protected_load_layout(self) -> None:
        text = P2_SCRIPT.read_text()
        for fragment in (
            "partition_size = 0x40000",
            "exceeds 256 KiB kflash",
            "exceeds 256 KiB uflash",
            "combined image does not preserve the kernel load bytes",
            "non-zero bytes escaped into the kflash/uflash gap",
            "userspace load bytes are not placed at 0x80040000",
            "P2 protected image layout PASS",
            "image-layout.log",
        ):
            self.assertIn(fragment, text)

    def test_p2_script_builds_one_bounded_runner_and_two_profiles(self) -> None:
        text = P2_SCRIPT.read_text()
        for fragment in (
            "-I${ROOT_DIR}/sim -std=c++20 -O2",
            "--top-module AetherCoreNuttXProtectedSimTop",
            '--rx-after-uart "nsh> "',
            "--self-check-exit",
            "shared_toy_assertions=disabled-via-self-check-exit",
            "STALL_PERIODS=(0 3)",
            "Hello, World!!",
            "UMODE_COMMAND_EVIDENCE",
            "hello command phase did not add user commits",
            "command_phase_proof=post-first-prompt-user-commit-ecall-mret",
            "expected immediate success at the second NSH prompt",
            "PASS: protected NSH returned after U-mode hello",
            "termination=immediate-success-after-second-nsh-prompt",
        ):
            self.assertIn(fragment, text)
        self.assertEqual(text.count("verilator --cc --exe --build"), 1)
        self.assertEqual(text.count("--self-check-exit"), 2)


if __name__ == "__main__":
    unittest.main()
