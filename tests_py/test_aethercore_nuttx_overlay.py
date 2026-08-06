from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
GENERATOR = ROOT / "tools" / "make_aethercore_nuttx_overlay.py"

START_WRAPPERS = """void riscv_earlyserialinit(void)
{
#ifdef CONFIG_16550_UART
  u16550_earlyserialinit();
#endif
}

void riscv_serialinit(void)
{
#ifdef CONFIG_16550_UART
  u16550_serialinit();
#endif
}
"""


class AetherCoreNuttxOverlayTest(unittest.TestCase):
    def make_fixture(self, directory: str) -> Path:
        root = Path(directory)
        chip = root / "arch/risc-v/src/qemu-rv"
        chip.mkdir(parents=True)
        (chip / "Kconfig").write_text("config ARCH_CHIP_QEMU_RV\n\tbool\n")
        (chip / "Make.defs").write_text(
            "include common/Make.defs\nCHIP_CSRCS = qemu_rv_start.c\n"
        )
        (chip / "qemu_rv_start.c").write_text(
            "/* start */\n" + START_WRAPPERS
        )
        (root / ".config").write_text(
            "CONFIG_16550_UART=y\n"
            "CONFIG_16550_UART0=y\n"
            "CONFIG_16550_UART0_SERIAL_CONSOLE=y\n"
            "CONFIG_FS_HOSTFS=y\n"
            "CONFIG_RISCV_SEMIHOSTING_HOSTFS=y\n"
            "# CONFIG_SUPPRESS_INTERRUPTS is not set\n"
            "CONFIG_RAM_START=0x80000000\n"
            "CONFIG_RAM_SIZE=33554432\n"
        )
        return root

    def run_generator(self, root: Path) -> subprocess.CompletedProcess[bytes]:
        return subprocess.run(
            [sys.executable, str(GENERATOR), str(root)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=5,
        )

    def test_installs_idempotent_native_uart_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self.make_fixture(directory)
            first = self.run_generator(root)
            self.assertEqual(first.returncode, 0, first.stderr.decode())
            second = self.run_generator(root)
            self.assertEqual(second.returncode, 0, second.stderr.decode())

            chip = root / "arch/risc-v/src/qemu-rv"
            serial = (chip / "aethercore_serial.c").read_text()
            self.assertIn("AETHERCORE_UART_TX_ADDR  0x10000000u", serial)
            self.assertIn("putreg8((uint8_t)ch", serial)
            self.assertIn('uart_register("/dev/console"', serial)
            self.assertIn('uart_register("/dev/ttyS0"', serial)
            self.assertIn("return false;", serial)

            kconfig = (chip / "Kconfig").read_text()
            make_defs = (chip / "Make.defs").read_text()
            start = (chip / "qemu_rv_start.c").read_text()
            self.assertEqual(kconfig.count("config AETHERCORE_UART"), 1)
            self.assertEqual(make_defs.count("CONFIG_AETHERCORE_UART"), 1)
            self.assertEqual(start.count("aethercore_earlyserialinit();"), 1)
            self.assertEqual(start.count("aethercore_serialinit();"), 1)

            config = (root / ".config").read_text().splitlines()
            self.assertIn("CONFIG_AETHERCORE_UART=y", config)
            self.assertIn("# CONFIG_16550_UART is not set", config)
            self.assertIn("# CONFIG_16550_UART0 is not set", config)
            self.assertIn(
                "# CONFIG_16550_UART0_SERIAL_CONSOLE is not set", config
            )
            self.assertIn("# CONFIG_FS_HOSTFS is not set", config)
            self.assertIn("# CONFIG_RISCV_SEMIHOSTING_HOSTFS is not set", config)
            self.assertIn("CONFIG_SERIAL=y", config)
            self.assertIn("CONFIG_DEV_CONSOLE=y", config)
            self.assertIn("CONFIG_SUPPRESS_INTERRUPTS=y", config)
            self.assertIn("CONFIG_RAM_START=0x80000000", config)
            self.assertIn("CONFIG_RAM_SIZE=67108856", config)

    def test_fails_closed_when_upstream_anchor_moves(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self.make_fixture(directory)
            start = root / "arch/risc-v/src/qemu-rv/qemu_rv_start.c"
            start.write_text("upstream changed\n")
            result = self.run_generator(root)
            self.assertEqual(result.returncode, 2)
            self.assertIn(b"expected patch anchor missing", result.stderr)

    def test_fails_closed_when_source_tree_is_incomplete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_generator(Path(directory))
            self.assertEqual(result.returncode, 2)
            self.assertIn(b"missing NuttX overlay inputs", result.stderr)


if __name__ == "__main__":
    unittest.main()
