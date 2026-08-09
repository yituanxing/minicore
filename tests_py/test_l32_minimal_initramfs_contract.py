from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class L32MinimalInitramfsContractTest(unittest.TestCase):
    def test_minimal_init_is_libc_free_rv32_syscall_workload(self):
        text = (ROOT / "software/l32_userspace/minimal_init.S").read_text()
        self.assertIn("_start:", text)
        self.assertIn("__NR_write", text)
        self.assertIn("__NR_sched_yield", text)
        self.assertIn("ecall", text)
        self.assertIn("L32 USER OK", text)
        self.assertIn("L32_INIT_MESSAGE_LEN 12", text)
        self.assertNotIn("libc", text.split(".section .text", 1)[-1].lower())

    def test_kernel_build_embeds_deterministic_initramfs_without_changing_frozen_image(self):
        text = (ROOT / "tools/ci/l32_minimal_initramfs_build.sh").read_text()
        for required in (
            "software/l32_userspace/minimal_init.S",
            "-march=rv32ima_zicsr_zifencei",
            "-mabi=ilp32",
            "-nostdlib",
            "-static",
            "-no-pie",
            "nod /dev/console 0600 0 0 c 5 1",
            "file /init",
            "BLK_DEV_INITRD",
            "INITRAMFS_SOURCE",
            "L32_MINIMAL_INITRAMFS_BUILD_RESULT: status=PASS",
        ):
            self.assertIn(required, text)
        self.assertIn('BUILD_DIR="${ROOT_DIR}/build/l32-linux-initramfs"', text)
        self.assertIn('FROZEN_BUILD_DIR="${ROOT_DIR}/build/l32-linux"', text)
        self.assertNotIn('rm -rf "${FROZEN_BUILD_DIR}"', text)

    def test_payload_uses_initramfs_image_and_explicit_rdinit(self):
        text = (ROOT / "tools/ci/l32_minimal_init_payload_build.sh").read_text()
        for required in (
            "build/l32-linux-initramfs",
            "L32_MINIMAL_INITRAMFS_BUILD_RESULT: status=PASS",
            "rdinit=/init",
            'FW_PAYLOAD_OFFSET="0x00400000"',
            'FW_PAYLOAD_FDT_ADDR="0x87f00000"',
            "L32_MINIMAL_INIT_PAYLOAD_BUILD_RESULT: status=PASS",
        ):
            self.assertIn(required, text)

    def test_userspace_stage_does_not_modify_rtl(self):
        for path in (
            ROOT / "tools/ci/l32_minimal_initramfs_build.sh",
            ROOT / "tools/ci/l32_minimal_init_payload_build.sh",
        ):
            text = path.read_text()
            self.assertNotIn("src/main/scala", text)
            self.assertNotIn("MachineCsrFile", text)


if __name__ == "__main__":
    unittest.main()
