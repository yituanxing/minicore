import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from make_smoke import build
from rv64i_ref import run


class SmokeProgramTest(unittest.TestCase):
    def test_program_reaches_expected_state(self) -> None:
        state = run(build())
        self.assertEqual(state.uart, "A")
        self.assertEqual(state.regs[3], 12)
        self.assertTrue(state.halted)
        self.assertEqual(state.commits, 7)


if __name__ == "__main__":
    unittest.main()
