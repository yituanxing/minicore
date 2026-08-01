import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from make_fault_regressions import RESET_PC, build_cases


class FaultRegressionImageTest(unittest.TestCase):
    def test_fault_cases_resolve_and_identify_the_faulting_instruction(self) -> None:
        cases = build_cases()
        self.assertEqual(
            set(cases),
            {"illegal_instruction", "load_bus_fault", "store_bus_fault"},
        )

        for name, case in cases.items():
            with self.subTest(name=name):
                words = case.program.resolve()
                self.assertGreater(len(words), case.fault_index)
                self.assertEqual(len(case.program.image()), len(words) * 4)
                fields = case.manifest_line(name).split()
                self.assertEqual(int(fields[2], 0), RESET_PC + case.fault_index * 4)
                self.assertEqual(int(fields[3], 0), words[case.fault_index])
                self.assertEqual(int(fields[4]), case.expected_commits)

    def test_bus_faults_include_backpressure(self) -> None:
        cases = build_cases()
        self.assertEqual(cases["load_bus_fault"].stall_period, 3)
        self.assertEqual(cases["store_bus_fault"].stall_period, 4)

    def test_store_fault_checks_younger_store_suppression(self) -> None:
        case = build_cases()["store_bus_fault"]
        self.assertEqual(case.expected_memory_address, RESET_PC + 0x200)
        self.assertEqual(case.expected_memory_value, 0)
        self.assertEqual(case.forbidden_rd, 3)


if __name__ == "__main__":
    unittest.main()
