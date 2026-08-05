from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
ZEPHYR = ROOT / "software" / "zephyr"
PLIC_MMIO = ROOT / "src" / "main" / "scala" / "aethercore" / "core" / "MachinePlicMmio.scala"
PLIC_SPEC = ROOT / "src" / "test" / "scala" / "aethercore" / "MachinePlicMmioSpec.scala"
PLATFORM_SPEC = ROOT / "src" / "test" / "scala" / "aethercore" / "MachineInterruptPlatformSpec.scala"
FREERTOS_PLATFORM = ROOT / "software" / "freertos" / "aethercore" / "platform.c"


class ZephyrPlatformShapeTest(unittest.TestCase):
    def test_module_registers_only_host_build_roots(self) -> None:
        text = (ROOT / "zephyr" / "module.yml").read_text(encoding="utf-8")
        self.assertIn("board_root: software/zephyr", text)
        self.assertIn("dts_root: software/zephyr", text)
        self.assertIn("soc_root: software/zephyr", text)

    def test_soc_matches_the_frozen_aethercore_memory_map(self) -> None:
        text = (
            ZEPHYR / "dts" / "riscv" / "aethercore" / "aethercore32.dtsi"
        ).read_text(encoding="utf-8")
        for token in (
            'riscv,isa = "rv32im_zicsr_zifencei"',
            "0x80000000 0x03fffff8",
            "0x10000000 0x4",
            "0x10000100 0x10",
            "test-exit@10000008",
            'compatible = "zephyr,aethercore-exit"',
            "0x10000008 0x4",
            "0x0c000000 0x00400000",
            "0x02000000 0x00010000",
            "riscv,ndev = <2>",
            "Source zero is reserved; source one is UART RX",
            "timebase-frequency = <1000000>",
        ):
            self.assertIn(token, text)
        self.assertNotIn("rv32ima", text)
        self.assertNotIn("rv32imc", text)

    def test_soc_kconfig_does_not_claim_unimplemented_extensions(self) -> None:
        family = (ZEPHYR / "soc" / "aethercore" / "Kconfig").read_text(
            encoding="utf-8"
        )
        profile = (
            ZEPHYR / "soc" / "aethercore" / "aethercore32" / "Kconfig"
        ).read_text(encoding="utf-8")
        defaults = (
            ZEPHYR / "soc" / "aethercore" / "Kconfig.defconfig"
        ).read_text(encoding="utf-8")
        self.assertIn("select ATOMIC_OPERATIONS_C", family)
        self.assertIn("select RISCV_HAS_PLIC", family)
        self.assertIn("select RISCV_ISA_EXT_M", profile)
        self.assertIn("select RISCV_ISA_EXT_ZICSR", profile)
        self.assertIn("select RISCV_ISA_EXT_ZIFENCEI", profile)
        self.assertIn("config MAX_IRQ_PER_AGGREGATOR\n\tdefault 2", defaults)
        self.assertIn("config NUM_IRQS\n\tdefault 13", defaults)
        self.assertNotIn("RISCV_ISA_EXT_A", family + profile)
        self.assertNotIn("RISCV_ISA_EXT_C", family + profile)

    def test_plic_mmio_uses_architectural_one_based_register_bits(self) -> None:
        mmio = PLIC_MMIO.read_text(encoding="utf-8")
        mmio_spec = PLIC_SPEC.read_text(encoding="utf-8")
        platform_spec = PLATFORM_SPEC.read_text(encoding="utf-8")
        freertos = FREERTOS_PLATFORM.read_text(encoding="utf-8")

        for token in (
            "val priorityZeroHit",
            "priorityZeroHit || priorityHit",
            "val shifted = Cat(value, 0.U(1.W))",
            "plic.io.enableWriteData := enableMerged(sourceCount, 1)",
            "sourceCount <= 31",
        ):
            self.assertIn(token, mmio)

        self.assertIn("read(dut, MachinePlicMmioMap.Enable) shouldBe 0x0e", mmio_spec)
        self.assertIn("read(dut, MachinePlicMmioMap.Pending) shouldBe 0x0e", mmio_spec)
        self.assertIn("read(dut, MachinePlicMmioMap.Enable) shouldBe 0x14", mmio_spec)
        self.assertIn("MachinePlicMmioMap.Enable, 2", platform_spec)
        self.assertIn("MachinePlicMmioMap.Pending) shouldBe 2", platform_spec)
        self.assertIn("1UL << AETHERCORE_UART_RX_SOURCE_ID", freertos)
        self.assertNotIn("AETHERCORE_UART_RX_SOURCE_ID - 1UL", freertos)

    def test_uart_driver_preserves_polling_boot_and_adds_bounded_z4_irq(self) -> None:
        source = (
            ZEPHYR / "drivers" / "serial" / "uart_aethercore.c"
        ).read_text(encoding="utf-8")
        z2_config = (
            ZEPHYR / "apps" / "kernel_smoke" / "prj.conf"
        ).read_text(encoding="utf-8")
        z4_config = (
            ZEPHYR / "apps" / "uart_irq_smoke" / "prj.conf"
        ).read_text(encoding="utf-8")
        z4_app = (
            ZEPHYR / "apps" / "uart_irq_smoke" / "src" / "main.c"
        ).read_text(encoding="utf-8")

        for token in (
            "aethercore_uart_poll_in",
            "aethercore_uart_poll_out",
            "UART_ERROR_OVERRUN",
            "DT_INST_REG_ADDR_BY_IDX(inst, 0)",
            "DT_INST_REG_ADDR_BY_IDX(inst, 1)",
            "CONFIG_UART_INTERRUPT_DRIVEN",
            "aethercore_uart_fifo_read",
            "aethercore_uart_irq_rx_enable",
            "aethercore_uart_irq_callback_set",
            "IRQ_CONNECT",
        ):
            self.assertIn(token, source)

        self.assertNotIn("CONFIG_UART_INTERRUPT_DRIVEN=y", z2_config)
        self.assertIn("CONFIG_UART_INTERRUPT_DRIVEN=y", z4_config)
        for token in (
            "uart_irq_callback_user_data_set",
            "uart_irq_rx_enable",
            "uart_fifo_read",
            "k_is_in_isr",
            "k_work_submit",
            "AETHERCORE ZEPHYR IRQ PASS",
        ):
            self.assertIn(token, z4_app)
        self.assertNotIn("irq_connect_dynamic", source)

    def test_exit_service_hides_mmio_address_from_the_application(self) -> None:
        source = (ZEPHYR / "platform" / "exit.c").read_text(encoding="utf-8")
        header = (ZEPHYR / "include" / "aethercore" / "exit.h").read_text(
            encoding="utf-8"
        )
        kconfig = (ZEPHYR / "platform" / "Kconfig").read_text(encoding="utf-8")
        cmake = (ZEPHYR / "CMakeLists.txt").read_text(encoding="utf-8")
        app = (
            ZEPHYR / "apps" / "kernel_smoke" / "src" / "main.c"
        ).read_text(encoding="utf-8")

        self.assertIn("DT_INST_REG_ADDR(0)", source)
        self.assertIn("sys_write32(code", source)
        self.assertIn('volatile ("wfi"', source)
        self.assertIn("void aethercore_exit(uint32_t code)", header)
        self.assertIn("config AETHERCORE_SIM_EXIT", kconfig)
        self.assertIn("add_subdirectory(platform)", cmake)
        self.assertIn("zephyr_include_directories(include)", cmake)
        self.assertIn("aethercore_exit(0U)", app)
        self.assertNotIn("0x10000008", app)

    def test_board_selects_console_without_self_hosted_runner_hook(self) -> None:
        board = (
            ZEPHYR / "boards" / "others" / "aethercore_sim" / "aethercore_sim.dts"
        ).read_text(encoding="utf-8")
        runner = (
            ZEPHYR / "boards" / "others" / "aethercore_sim" / "board.cmake"
        ).read_text(encoding="utf-8")
        self.assertIn("zephyr,console = &uart0", board)
        self.assertIn('status = "okay"', board)
        self.assertNotIn("self-hosted", runner)
        self.assertNotIn("runner_args", runner)


if __name__ == "__main__":
    unittest.main()
