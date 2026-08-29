import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import make_rv32imu_isolated_scheduler as workload


class RV32IMUIsolatedSchedulerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.program = workload.build_image()
        self.words = self.program.resolve()

    def address(self, label: str) -> int:
        return workload.BASE + self.program.labels[label] * 4

    def test_sections_match_dynamic_tor_contract(self) -> None:
        self.assertEqual(self.address("task_a_main"), workload.TASK_A_TEXT)
        self.assertEqual(self.address("task_a_counter"), workload.TASK_A_DATA)
        self.assertEqual(self.address("task_a_message"), workload.A_MESSAGE)
        self.assertEqual(self.address("task_b_main"), workload.TASK_B_TEXT)
        self.assertEqual(self.address("task_b_counter"), workload.TASK_B_DATA)
        self.assertEqual(self.address("task_b_ready"), workload.B_READY)
        self.assertEqual(self.address("task_b_message"), workload.B_MESSAGE)
        self.assertLess(self.address("bad_trap"), workload.CTX_A)
        self.assertLess(len(self.words) * 4, workload.TASK_B_LIMIT - workload.BASE)

    def test_cross_task_attack_is_a_load_from_task_b_data(self) -> None:
        index = self.program.labels["task_a_attack_b_data"]
        instruction = self.words[index]
        self.assertEqual(instruction & 0x7F, 0x03)
        self.assertEqual((instruction >> 12) & 0x7, 0x2)
        self.assertEqual((instruction >> 15) & 0x1F, 5)
        self.assertEqual((instruction >> 7) & 0x1F, 6)

    def test_combines_timer_user_and_pmp_architecture(self) -> None:
        csr_addresses = [(word >> 20) & 0xFFF for word in self.words if (word & 0x7F) == 0x73]
        self.assertIn(workload.MIE, csr_addresses)
        self.assertIn(workload.MSTATUS, csr_addresses)
        self.assertIn(workload.MTVEC, csr_addresses)
        self.assertIn(workload.MSCRATCH, csr_addresses)
        self.assertIn(workload.PMPCFG0, csr_addresses)
        self.assertGreaterEqual(csr_addresses.count(workload.PMPADDR0), 3)
        self.assertGreaterEqual(csr_addresses.count(workload.PMPADDR1), 3)
        self.assertGreaterEqual(csr_addresses.count(workload.PMPADDR2), 3)
        self.assertGreaterEqual(self.words.count(0x30200073), 2)
        self.assertGreaterEqual(self.words.count(0x00000073), 6)

    def test_user_buffers_are_private_and_kernel_frames_are_hidden(self) -> None:
        self.assertLess(workload.CTX_A, workload.KERNEL_LIMIT)
        self.assertLess(workload.CTX_B, workload.KERNEL_LIMIT)
        self.assertLess(workload.KSTATE, workload.KERNEL_LIMIT)
        self.assertLessEqual(workload.TASK_A_DATA, workload.A_MESSAGE)
        self.assertLess(workload.A_MESSAGE + len(workload.MESSAGE_A), workload.TASK_A_LIMIT)
        self.assertLessEqual(workload.TASK_B_DATA, workload.B_MESSAGE)
        self.assertLess(workload.B_MESSAGE + len(workload.MESSAGE_B), workload.TASK_B_LIMIT)
        self.assertEqual(workload.PMPCFG0_VALUE, 0x000B0D08)

    def test_context_frames_and_kernel_state_do_not_overlap(self) -> None:
        # x1..x31 occupy offsets 4..124 and saved mepc occupies offset 128.
        frame_bytes = 33 * 4
        kernel_state_bytes = workload.K_B_FAULTS + 4
        self.assertEqual(workload.CTX_A % 4, 0)
        self.assertEqual(workload.CTX_B % 4, 0)
        self.assertEqual(workload.KSTATE % 4, 0)
        self.assertLessEqual(workload.CTX_A + frame_bytes, workload.CTX_B)
        self.assertLessEqual(workload.CTX_B + frame_bytes, workload.KSTATE)
        self.assertLessEqual(workload.KSTATE + kernel_state_bytes, workload.KERNEL_LIMIT)

    def test_private_stacks_and_dynamic_tor_bounds_are_complete(self) -> None:
        for text, data, limit, stack in (
            (
                workload.TASK_A_TEXT,
                workload.TASK_A_DATA,
                workload.TASK_A_LIMIT,
                workload.TASK_A_STACK,
            ),
            (
                workload.TASK_B_TEXT,
                workload.TASK_B_DATA,
                workload.TASK_B_LIMIT,
                workload.TASK_B_STACK,
            ),
        ):
            self.assertEqual(text % 4, 0)
            self.assertEqual(data % 4, 0)
            self.assertEqual(limit % 4, 0)
            self.assertLess(text >> 2, data >> 2)
            self.assertLess(data >> 2, limit >> 2)
            self.assertGreaterEqual(stack, data)
            self.assertLessEqual(stack + 4, limit)
            self.assertEqual(stack % 4, 0)


if __name__ == "__main__":
    unittest.main()
