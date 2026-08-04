from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
ZEPHYR = ROOT / "software" / "zephyr"


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
            "0x80000000 0x04000000",
            "0x10000000 0x4",
            "0x10000100 0x10",
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

    def test_uart_driver_stays_polling_only_for_boot_milestone(self) -> None:
        source = (
            ZEPHYR / "drivers" / "serial" / "uart_aethercore.c"
        ).read_text(encoding="utf-8")
        for token in (
            "aethercore_uart_poll_in",
            "aethercore_uart_poll_out",
            "UART_ERROR_OVERRUN",
            "DT_INST_REG_ADDR_BY_IDX(inst, 0)",
            "DT_INST_REG_ADDR_BY_IDX(inst, 1)",
        ):
            self.assertIn(token, source)
        self.assertNotIn("CONFIG_UART_INTERRUPT_DRIVEN", source)
        self.assertNotIn("irq_connect_dynamic", source)

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
