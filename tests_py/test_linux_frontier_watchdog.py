from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
WATCHDOG = ROOT / "tools" / "ci" / "l32_linux_frontier_watchdog.py"


def run_watchdog(program: str, stall_cycles: int = 1_000_000) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(WATCHDOG),
            "--stall-cycles",
            str(stall_cycles),
            "--",
            sys.executable,
            "-u",
            "-c",
            program,
        ],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=10,
        check=False,
    )


class LinuxFrontierWatchdogTest(unittest.TestCase):
    def test_commit_progress_passes(self) -> None:
        result = run_watchdog(
            "print('L32_SIM_PROGRESS cycles=1000000 commits=10');"
            "print('L32_SIM_PROGRESS cycles=2000000 commits=11')"
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("L32_FRONTIER_STALL", result.stdout)

    def test_no_guest_progress_fails_early(self) -> None:
        result = run_watchdog(
            "import time;"
            "print('L32_SIM_PROGRESS cycles=1000000 commits=10');"
            "print('L32_SIM_PROGRESS cycles=2000000 commits=10');"
            "time.sleep(5)"
        )
        self.assertEqual(result.returncode, 86, result.stdout)
        self.assertIn("L32_FRONTIER_STALL", result.stdout)
        self.assertIn("stall-cycles=1000000", result.stdout)

    def test_uart_activity_resets_stall_window(self) -> None:
        result = run_watchdog(
            "print('L32_SIM_PROGRESS cycles=1000000 commits=10');"
            "print('[    0.000000] Linux booting');"
            "print('L32_SIM_PROGRESS cycles=2000000 commits=10');"
            "print('L32_SIM_PROGRESS cycles=3000000 commits=11')"
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("L32_FRONTIER_STALL", result.stdout)

    def test_child_failure_is_preserved(self) -> None:
        result = run_watchdog("raise SystemExit(7)")
        self.assertEqual(result.returncode, 7, result.stdout)

    def test_zero_budget_disables_watchdog(self) -> None:
        result = run_watchdog(
            "print('L32_SIM_PROGRESS cycles=1000000 commits=10');"
            "print('L32_SIM_PROGRESS cycles=9000000 commits=10')",
            stall_cycles=0,
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("L32_FRONTIER_STALL", result.stdout)


if __name__ == "__main__":
    unittest.main()
