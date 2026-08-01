import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOFTWARE = ROOT / "software" / "compiled"


class CompiledWorkloadSourceTest(unittest.TestCase):
    def test_startup_sets_stack_clears_bss_and_exits_through_mmio(self) -> None:
        startup = (SOFTWARE / "crt0.S").read_text(encoding="utf-8")
        self.assertIn("la sp, __stack_top", startup)
        self.assertIn("la t0, __bss_start", startup)
        self.assertIn("la t1, __bss_end", startup)
        self.assertIn("li t0, 0x10000008", startup)
        self.assertIn("sd a0, 0(t0)", startup)

    def test_linker_uses_the_simulator_ram_base_and_a_separate_stack(self) -> None:
        linker = (SOFTWARE / "linker.ld").read_text(encoding="utf-8")
        self.assertIn("ORIGIN = 0x80000000", linker)
        self.assertIn("LENGTH = 64M", linker)
        self.assertIn("__stack_top = ORIGIN(RAM) + 0x00100000", linker)
        self.assertIn("__bss_start", linker)
        self.assertIn("__bss_end", linker)

    def test_first_matrix_contains_stack_memory_and_m_extension_programs(self) -> None:
        builder = (ROOT / "tools" / "build_compiled_workloads.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("programs=(call_stack memory arithmetic)", builder)
        self.assertIn("-march=rv64im", builder)
        self.assertIn("-mabi=lp64", builder)
        self.assertIn("-ffreestanding", builder)
        self.assertIn("-nostdlib", builder)

        for name in ("call_stack", "memory", "arithmetic"):
            source = (SOFTWARE / f"{name}.c").read_text(encoding="utf-8")
            self.assertIn("int main(void)", source)
            self.assertIn("return 0;", source)


if __name__ == "__main__":
    unittest.main()
