import hashlib
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from make_rv32imu_pmp import (
    BASE,
    KERNEL_LIMIT,
    USER_DATA,
    USER_LIMIT,
    USER_TEXT,
    build_image,
)


class RV32IMUPmpImageTest(unittest.TestCase):
    def test_image_is_deterministic_and_partitioned(self) -> None:
        first = build_image()
        second = build_image()
        first_image = first.image()
        second_image = second.image()

        self.assertEqual(first_image, second_image)
        self.assertEqual(
            hashlib.sha256(first_image).hexdigest(),
            hashlib.sha256(second_image).hexdigest(),
        )
        self.assertGreater(len(first_image), USER_DATA - BASE)
        self.assertLessEqual(len(first_image), USER_LIMIT - BASE)

        kernel_labels = (
            "trap_handler",
            "sys_write",
            "sys_get_ticks",
            "sys_exit",
            "forbidden_kernel_target",
        )
        for name in kernel_labels:
            address = BASE + first.labels[name] * 4
            self.assertGreaterEqual(address, BASE)
            self.assertLess(address, KERNEL_LIMIT)

        user_text_labels = (
            "user_main",
            "attack_uart_store",
            "attack_kernel_load",
            "attack_kernel_store",
            "attack_text_store",
            "attack_kernel_execute",
            "attack_data_execute",
        )
        for name in user_text_labels:
            address = BASE + first.labels[name] * 4
            self.assertGreaterEqual(address, USER_TEXT)
            self.assertLess(address, USER_DATA)

        for name in ("message", "marker", "forbidden_data_target"):
            address = BASE + first.labels[name] * 4
            self.assertGreaterEqual(address, USER_DATA)
            self.assertLess(address, USER_LIMIT)

    def test_attack_sites_use_the_expected_instruction_classes(self) -> None:
        program = build_image()
        words = program.resolve()

        expected = {
            "attack_uart_store": 0x23,
            "attack_kernel_load": 0x03,
            "attack_kernel_store": 0x23,
            "attack_text_store": 0x23,
            "attack_kernel_execute": 0x67,
            "attack_data_execute": 0x67,
        }
        for label, opcode in expected.items():
            with self.subTest(label=label):
                self.assertEqual(words[program.labels[label]] & 0x7F, opcode)


if __name__ == "__main__":
    unittest.main()
