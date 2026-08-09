from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "software/l32_busybox/manifest.env"
FIRMWARE_MANIFEST = ROOT / "software/l32/manifest.env"
BUILD = ROOT / "tools/ci/l32_busybox_build.sh"
INITRAMFS_BUILD = ROOT / "tools/ci/l32_busybox_initramfs_build.sh"
PAYLOAD_BUILD = ROOT / "tools/ci/l32_busybox_payload_build.sh"
WORKFLOW = ROOT / ".github/workflows/l32-busybox-build.yml"
SIM_TOP = ROOT / "src/main/scala/aethercore/sim/AetherCoreSimTop.scala"
RUNNER = ROOT / "sim/opensbi_boot_main.cpp"
LINUX_MAKEFILE = ROOT / "Makefile.l32-linux-boot"


class L32BusyBoxContract(unittest.TestCase):
    def test_frozen_userspace_inputs(self):
        text = MANIFEST.read_text()
        self.assertIn("L32_USERSPACE_CROSS_COMPILE_PREFIX=riscv64-unknown-elf-", text)
        self.assertIn("L32_USERSPACE_ISA=rv32ima_zicsr_zifencei", text)
        self.assertIn("L32_USERSPACE_ABI=ilp32", text)
        self.assertNotIn("riscv32-ilp32d", text)
        self.assertIn("MUSL_VERSION=1.2.5", text)
        self.assertIn(
            "MUSL_SHA256=a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4",
            text,
        )
        self.assertIn("BUSYBOX_VERSION=1.36.1", text)
        self.assertIn(
            "BUSYBOX_SHA256=b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314",
            text,
        )

    def test_userspace_freeze_isolated_from_firmware_and_minimal_init_paths(self):
        firmware = FIRMWARE_MANIFEST.read_text()
        self.assertNotIn("MUSL_VERSION=", firmware)
        self.assertNotIn("BUSYBOX_VERSION=", firmware)
        build = BUILD.read_text()
        self.assertIn('software/l32_busybox/manifest.env', build)
        self.assertNotIn('software/l32_userspace/manifest.env', build)
        self.assertNotIn('source "${ROOT_DIR}/software/l32/manifest.env"', build)

    def test_musl_is_built_as_separate_soft_float_sysroot(self):
        text = BUILD.read_text()
        self.assertIn("--target=riscv32-linux-musl", text)
        self.assertIn("--disable-shared", text)
        self.assertIn("--enable-static", text)
        self.assertIn('L32_CC="${BUILD_DIR}/l32-rv32ima-ilp32-gcc"', text)
        self.assertIn('-march="${L32_USERSPACE_ISA}"', text)
        self.assertIn('-mabi="${L32_USERSPACE_ABI}"', text)
        self.assertIn('"CC=${L32_CC}"', text)
        self.assertIn("toolchain-probe.o", text)
        self.assertIn("soft-float ABI", text)
        self.assertNotIn("--sysroot=/", text)

    def test_busybox_is_minimal_static_ash_and_rejects_fdc(self):
        text = BUILD.read_text()
        self.assertIn("make ARCH=riscv allnoconfig", text)
        self.assertNotIn("make ARCH=riscv defconfig", text)
        for symbol in (
            "CONFIG_STATIC",
            "CONFIG_LFS",
            "CONFIG_ASH",
            "CONFIG_SH_IS_ASH",
            "CONFIG_ECHO",
            "CONFIG_PRINTF",
            "CONFIG_TEST",
        ):
            self.assertIn(f"'{symbol}'", text)
        self.assertIn("minimal shell config unexpectedly enabled kbd_mode", text)
        self.assertIn("statically linked", text)
        self.assertIn("BusyBox retained unsupported F/D/C extension", text)
        self.assertIn("L32_BUSYBOX_BUILD_RESULT: status=PASS", text)

    def test_busybox_initramfs_executes_real_ash_without_replacing_frozen_linux(self):
        text = INITRAMFS_BUILD.read_text()
        for required in (
            'BUSYBOX_ELF="${BUSYBOX_BUILD_DIR}/busybox-src/busybox"',
            "L32_BUSYBOX_BUILD_RESULT: status=PASS",
            'FROZEN_BUILD_DIR="${ROOT_DIR}/build/l32-linux"',
            'BUILD_DIR="${ROOT_DIR}/build/l32-linux-busybox"',
            "nod /dev/console 0600 0 0 c 5 1",
            "file /bin/busybox",
            "slink /bin/sh busybox",
            "slink /bin/uname busybox",
            "#!/bin/sh",
            '/bin/uname -a',
            'echo "L32 BUSYBOX SHELL READY"',
            "exec /bin/sh -i",
            "BLK_DEV_INITRD",
            "INITRAMFS_SOURCE",
            "L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS",
        ):
            self.assertIn(required, text)
        self.assertNotIn('rm -rf "${FROZEN_BUILD_DIR}"', text)
        self.assertNotIn("src/main/scala", text)

    def test_busybox_payload_keeps_linux_platform_and_rdinit_boundary(self):
        text = PAYLOAD_BUILD.read_text()
        for required in (
            'LINUX_BUILD_DIR="${ROOT_DIR}/build/l32-linux-busybox"',
            "L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS",
            "rdinit=/init",
            'FW_PAYLOAD_OFFSET="0x00400000"',
            'FW_PAYLOAD_FDT_ADDR="0x87f00000"',
            "L32_BUSYBOX_SHELL_PAYLOAD_BUILD_RESULT: status=PASS",
            "next_addr=0x80400000",
            "next_mode=S-mode",
        ):
            self.assertIn(required, text)
        self.assertNotIn("src/main/scala", text)

    def test_supervisor_ns16550_rx_is_architectural_not_host_side_shortcut(self):
        text = SIM_TOP.read_text()
        for required in (
            "Some(Module(new Queue(UInt(8.W), 16)))",
            "uartIer.get(0) && uartRxAvailable",
            "uartRxPop",
            "uartRxByte",
            '"h60".U(8.W) | uartRxAvailable.asUInt',
            "Mux(uartRxInterrupt, 4.U",
            "uartCombinedInterrupt",
            "io.uartRxInterrupt.get := uartRxInterrupt",
        ):
            self.assertIn(required, text)

    def test_runner_requires_uart_rx_irq_and_post_input_seip(self):
        runner = RUNNER.read_text()
        for required in (
            "top.io_rxValid",
            "top.io_rxReady",
            "top.io_uartRxInterrupt",
            "L32_UART_INPUT_START",
            "L32_UART_INPUT_COMPLETE",
            "L32_UART_RX_INTERRUPT",
            "L32_UART_INPUT_SEIP",
            "sawPostInputSeip",
            "inputSatisfied",
        ):
            self.assertIn(required, runner)

        makefile = LINUX_MAKEFILE.read_text()
        for required in (
            "UART_TRIGGER ?=",
            "UART_COMMAND ?=",
            "UART_COMMAND_FILE ?=",
            '"$(UART_TRIGGER)"',
            '"$$uart_command"',
            "L32_UART_INPUT_COMPLETE",
            "L32_UART_RX_INTERRUPT",
            "L32_UART_INPUT_SEIP",
        ):
            self.assertIn(required, makefile)

    def test_workflow_runs_real_ttys0_command_round_trip(self):
        text = WORKFLOW.read_text()
        for required in (
            "tools/ci/l32_busybox_initramfs_build.sh",
            "tools/ci/l32_busybox_payload_build.sh",
            "Provision pinned Bootlin RV32 Linux GCC",
            "Build Linux Image with static BusyBox ash initramfs",
            "Build OpenSBI with BusyBox Linux payload",
            "MIN_INTERRUPTS=1",
            "MIN_SEIP=1",
            'UART_TRIGGER="L32 BUSYBOX SHELL READY"',
        ):
            self.assertIn(required, text)

        legacy_gate = all(
            required in text
            for required in (
                "Run real Linux BusyBox ash command over ttyS0",
                'MILESTONE="L32 BUSYBOX RX COMMAND OK"',
                "UART_COMMAND=\"printf 'L32 BUSYBOX RX COMMAND %s\\n' OK\"",
            )
        )
        multiprocess_gate = all(
            required in text
            for required in (
                "Run real Linux BusyBox multiprocess pipeline over ttyS0",
                'MILESTONE="L32 BUSYBOX PIPE PARENT OK"',
                'UART_COMMAND_FILE="$command_file"',
                "L32 BUSYBOX PIPE CHILD OK",
            )
        )
        self.assertTrue(
            legacy_gate or multiprocess_gate,
            "workflow must retain a real ttyS0 shell command round-trip gate",
        )


if __name__ == "__main__":
    unittest.main()
