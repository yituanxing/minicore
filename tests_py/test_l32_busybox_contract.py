from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "software/l32_busybox/manifest.env"
FIRMWARE_MANIFEST = ROOT / "software/l32/manifest.env"
BUILD = ROOT / "tools/ci/l32_busybox_build.sh"
PROFILE = ROOT / "tools/ci/l32_userspace_profile.sh"
PROFILE_AUDIT = ROOT / "tools/ci/riscv_elf_profile.py"
INITRAMFS_BUILD = ROOT / "tools/ci/l32_busybox_initramfs_build.sh"
PAYLOAD_BUILD = ROOT / "tools/ci/l32_busybox_payload_build.sh"
WORKFLOW = ROOT / ".github/workflows/l32-busybox-build.yml"
SIM_TOP = ROOT / "src/main/scala/aethercore/sim/AetherCoreSimTop.scala"
RUNNER = ROOT / "sim/opensbi_boot_main.cpp"
RUNTIME = ROOT / "sim/l32_opensbi_runtime.h"
LINUX_MAKEFILE = ROOT / "Makefile.l32-linux-boot"


class L32BusyBoxContract(unittest.TestCase):
    def test_frozen_userspace_inputs(self):
        text = MANIFEST.read_text()
        self.assertIn("L32_USERSPACE_CROSS_COMPILE_PREFIX=riscv64-unknown-elf-", text)
        self.assertIn("L32_USERSPACE_ISA=rv32ima_zicsr_zifencei", text)
        self.assertIn("L32_USERSPACE_ABI=ilp32", text)
        self.assertNotIn("riscv32-ilp32d", text)
        self.assertIn("MUSL_VERSION=1.2.5", text)
        self.assertIn("MUSL_SHA256=a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4", text)
        self.assertIn("BUSYBOX_VERSION=1.36.1", text)
        self.assertIn("BUSYBOX_SHA256=b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314", text)

    def test_userspace_freeze_isolated_from_firmware_and_minimal_init_paths(self):
        firmware = FIRMWARE_MANIFEST.read_text()
        self.assertNotIn("MUSL_VERSION=", firmware)
        self.assertNotIn("BUSYBOX_VERSION=", firmware)
        build = BUILD.read_text()
        self.assertIn('software/l32_busybox/manifest.env', build)
        self.assertIn('tools/ci/l32_userspace_profile.sh', build)
        self.assertNotIn('software/l32_userspace/manifest.env', build)
        self.assertNotIn('source "${ROOT_DIR}/software/l32/manifest.env"', build)

    def test_musl_build_consumes_explicit_profile_without_changing_default_lane(self):
        text = BUILD.read_text()
        profile = PROFILE.read_text()
        audit = PROFILE_AUDIT.read_text()
        for required in (
            "--target=riscv32-linux-musl",
            "--disable-shared",
            "--enable-static",
            'BUILD_DIR="${L32_USERSPACE_BUSYBOX_BUILD_DIR}"',
            'L32_CC="${BUILD_DIR}/l32-${L32_USERSPACE_PROFILE}-ilp32-gcc"',
            '-march="${L32_USERSPACE_EFFECTIVE_ISA}"',
            '-mabi="${L32_USERSPACE_ABI}"',
            '"CC=${L32_CC}"',
            "toolchain-probe.o",
            "audit_riscv_profile toolchain-probe",
        ):
            self.assertIn(required, text)
        self.assertIn('L32_USERSPACE_PROFILE="${AETHERCORE_L32_USERSPACE_PROFILE:-rv32ima}"', profile)
        self.assertIn('L32_USERSPACE_BUILD_SUFFIX=""', profile)
        self.assertIn('L32_USERSPACE_EFFECTIVE_ISA="${L32_USERSPACE_ISA}"', profile)
        self.assertIn('L32_USERSPACE_BUILD_SUFFIX="-rv32imac"', profile)
        self.assertIn("expected soft-float", audit)
        self.assertNotIn("--sysroot=/", text)

    def test_busybox_is_minimal_static_ash_and_uses_generic_c_policy_audit(self):
        text = BUILD.read_text()
        self.assertIn("make ARCH=riscv allnoconfig", text)
        self.assertNotIn("make ARCH=riscv defconfig", text)
        for symbol in ("CONFIG_STATIC", "CONFIG_LFS", "CONFIG_ASH", "CONFIG_SH_IS_ASH", "CONFIG_ECHO", "CONFIG_PRINTF", "CONFIG_TEST"):
            self.assertIn(f"'{symbol}'", text)
        self.assertIn("minimal shell config unexpectedly enabled kbd_mode", text)
        self.assertIn("statically linked", text)
        self.assertIn("c_policy=(--forbid-c)", text)
        self.assertIn("c_policy=(--require-c)", text)
        self.assertIn("audit_riscv_profile busybox", text)
        self.assertIn("busybox_compressed_instructions=", text)
        self.assertIn("L32_BUSYBOX_BUILD_RESULT: status=PASS", text)
        self.assertNotIn("BusyBox retained unsupported F/D/C extension", text)

    def test_busybox_initramfs_consumes_one_profile_and_requalifies_kernel_c(self):
        text = INITRAMFS_BUILD.read_text()
        for required in (
            'source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"',
            'FROZEN_BUILD_DIR="${ROOT_DIR}/build/l32-linux"',
            'C_KERNEL_BUILD_DIR="${ROOT_DIR}/build/l32-linux-rv32imac"',
            'BUSYBOX_BUILD_DIR="${L32_USERSPACE_BUSYBOX_BUILD_DIR}"',
            'PROBE_BUILD_DIR="${L32_USERSPACE_RUNTIME_PROBE_BUILD_DIR}"',
            'REAL_BUILD_DIR="${L32_USERSPACE_REAL_PROGRAMS_BUILD_DIR}"',
            'BUILD_DIR="${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}"',
            "RV32C_LINUX_KERNEL_RESULT: status=PASS",
            'grep -qx "profile=${L32_USERSPACE_PROFILE}"',
            "c_config=(-d RISCV_ISA_C)",
            "c_config=(-e RISCV_ISA_C)",
            "CONFIG_RISCV_ISA_C=y",
            "# CONFIG_RISCV_ISA_C is not set",
            "linux_compressed=",
            "kernel-profile-audit.txt",
            "linux_config_riscv_isa_c=y",
            "linux_config_riscv_isa_c=n",
            "RV32IMAC initramfs kernel did not retain real compressed code",
            "historical RV32IMA initramfs kernel unexpectedly advertises RVC",
            "L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS",
            'echo "profile=${L32_USERSPACE_PROFILE}"',
            'echo "linux_compressed_instructions=${linux_compressed}"',
        ):
            self.assertIn(required, text)
        self.assertIn('[[ "${linux_flags}" == *RVC* && "${linux_compressed}" -gt 0 ]]', text)
        self.assertIn('[[ "${linux_flags}" != *RVC* ]]', text)
        self.assertNotIn('[[ "${linux_flags}" != *RVC* && "${linux_compressed}" -eq 0 ]]', text)
        for required in (
            "nod /dev/console 0600 0 0 c 5 1", "nod /dev/null 0666 0 0 c 1 3",
            "file /bin/busybox", "file /bin/l32-runtime-probe", "slink /bin/sh busybox",
            "file /opt/l32/lua", "file /opt/l32/sqlite-smoke", "file /opt/l32/bash",
            "file /opt/l32/busybox-real", "file /opt/l32/zlib-smoke", "file /opt/l32/libpng-smoke",
            "#!/bin/sh", '/bin/uname -a', 'echo "L32 BUSYBOX SHELL READY"', "exec /bin/sh -i",
            "BLK_DEV_INITRD", "INITRAMFS_SOURCE",
        ):
            self.assertIn(required, text)
        self.assertNotIn('rm -rf "${FROZEN_BUILD_DIR}"', text)
        self.assertNotIn("src/main/scala", text)

    def test_busybox_payload_preserves_handoff_and_proves_firmware_owned_c(self):
        text = PAYLOAD_BUILD.read_text()
        for required in (
            'source "${ROOT_DIR}/tools/ci/l32_userspace_profile.sh"',
            'LINUX_BUILD_DIR="${L32_USERSPACE_LINUX_BUSYBOX_BUILD_DIR}"',
            'BUILD_DIR="${L32_USERSPACE_PAYLOAD_BUILD_DIR}"',
            "L32_BUSYBOX_INITRAMFS_BUILD_RESULT: status=PASS",
            'grep -qx "profile=${L32_USERSPACE_PROFILE}"',
            "rdinit=/init",
            'FW_PAYLOAD_OFFSET="0x00400000"',
            'FW_PAYLOAD_ADDRESS="0x80400000"',
            'FW_PAYLOAD_FDT_ADDR="0x87f00000"',
            '--isa "${L32_USERSPACE_DTB_ISA}"',
            'PLATFORM_RISCV_ISA="${L32_USERSPACE_OPENSBI_ISA}"',
            "opensbi_arch=",
            "opensbi_flags=",
            "firmware_compressed=",
            "firmware-owned compressed code",
            "historical RV32IMA OpenSBI payload unexpectedly advertises RVC",
            "L32_BUSYBOX_SHELL_PAYLOAD_BUILD_RESULT: status=PASS",
            'echo "profile=${L32_USERSPACE_PROFILE}"',
            'echo "opensbi_elf_flags=${opensbi_flags}"',
            'echo "opensbi_firmware_compressed_instructions=${firmware_compressed}"',
            'echo "next_addr=${FW_PAYLOAD_ADDRESS}"',
            "next_mode=S-mode",
        ):
            self.assertIn(required, text)
        self.assertIn('[[ "${opensbi_arch}" =~ _c[0-9] && "${opensbi_flags}" == *RVC* && "${firmware_compressed}" -gt 0 ]]', text)
        self.assertIn('[[ ! "${opensbi_arch}" =~ _c[0-9] && "${opensbi_flags}" != *RVC* ]]', text)
        self.assertNotIn('[[ ! "${opensbi_arch}" =~ _c[0-9] && "${firmware_compressed}" -eq 0 ]]', text)

    def test_supervisor_ns16550_rx_is_architectural_not_host_side_shortcut(self):
        text = SIM_TOP.read_text()
        for required in ("Some(Module(new Queue(UInt(8.W), 16)))", "uartIer.get(0) && uartRxAvailable", "uartRxPop", "uartRxByte", '"h60".U(8.W) | uartRxAvailable.asUInt', "Mux(uartRxInterrupt, 4.U", "uartCombinedInterrupt", "io.uartRxInterrupt.get := uartRxInterrupt"):
            self.assertIn(required, text)

    def test_runner_requires_uart_rx_irq_and_post_input_seip(self):
        runner = RUNNER.read_text()
        runtime = RUNTIME.read_text()
        for required in (
            "const bool rxAccepted = step(",
            "top.io_uartRxInterrupt",
            "L32_UART_INPUT_START",
            "L32_UART_INPUT_COMPLETE",
            "L32_UART_RX_INTERRUPT",
            "L32_UART_INPUT_SEIP",
            "sawPostInputSeip",
            "inputSatisfied",
        ):
            self.assertIn(required, runner)
        for required in (
            "top.io_rxValid = rxValid;",
            "top.io_rxByte = rxValid ? rxByte : 0;",
            "const bool rxAccepted = top.io_rxValid && top.io_rxReady;",
            "return rxAccepted;",
        ):
            self.assertIn(required, runtime)
        makefile = LINUX_MAKEFILE.read_text()
        for required in ("UART_TRIGGER ?=", "UART_COMMAND ?=", "UART_COMMAND_FILE ?=", '"$(UART_TRIGGER)"', '"$$uart_command"', "L32_UART_INPUT_COMPLETE", "L32_UART_RX_INTERRUPT", "L32_UART_INPUT_SEIP"):
            self.assertIn(required, makefile)

    def test_historical_workflow_keeps_real_ttys0_command_round_trip(self):
        text = WORKFLOW.read_text()
        for required in (
            "tools/ci/l32_runtime_probe_build.sh", "tools/ci/l32_busybox_initramfs_build.sh", "tools/ci/l32_busybox_payload_build.sh",
            "l32_software_artifact_cache.sh runtime-probe", "tools/ci/l32_runtime_image_cache.sh", "l32_software_artifact_cache.sh busybox-payload",
            "MIN_INTERRUPTS=1", "MIN_SEIP=1", 'UART_TRIGGER="L32 BUSYBOX SHELL READY"', 'MILESTONE="L32 BUSYBOX PIPE PARENT OK"',
            'UART_COMMAND_FILE="$command_file"', "L32 BUSYBOX PIPE CHILD OK",
        ):
            self.assertIn(required, text)
        probe = text.index("          tools/ci/l32_software_artifact_cache.sh runtime-probe")
        initramfs = text.index("          tools/ci/l32_runtime_image_cache.sh")
        payload = text.index("          tools/ci/l32_software_artifact_cache.sh busybox-payload")
        runtime = text.index('            MILESTONE="L32 BUSYBOX PIPE PARENT OK"')
        self.assertLess(probe, initramfs)
        self.assertLess(initramfs, payload)
        self.assertLess(payload, runtime)


if __name__ == "__main__":
    unittest.main()
