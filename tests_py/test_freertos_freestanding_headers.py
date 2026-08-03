from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "software" / "freertos" / "aethercore"
MAKEFILE = ROOT / "Makefile.freertos"
RUNTIME = APP / "runtime.c"
INSTALLER = ROOT / "tools" / "ensure_riscv_none_elf_gcc_15_2.sh"


class FreeRtosSysrootBoundaryTest(unittest.TestCase):
    def test_project_owned_standard_header_shims_are_removed(self) -> None:
        shim_dir = APP / "freestanding"
        self.assertFalse((shim_dir / "stdlib.h").exists())
        self.assertFalse((shim_dir / "string.h").exists())

    def test_makefile_uses_the_pinned_compiler_sysroot(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("CROSS_COMPILE ?= riscv-none-elf-", text)
        self.assertIn("TOOLCHAIN_SYSROOT := $(shell $(CC) --print-sysroot", text)
        self.assertIn("SYSROOT_FLAGS := --sysroot=$(TOOLCHAIN_SYSROOT)", text)
        self.assertIn("$(SYSROOT_FLAGS) $(ARCH_FLAGS)", text)
        self.assertIn('test -f "$(TOOLCHAIN_SYSROOT)/include/stdlib.h"', text)
        self.assertIn('test -f "$(TOOLCHAIN_SYSROOT)/include/string.h"', text)
        self.assertIn("-print-file-name=libc.a", text)
        self.assertIn("-print-libgcc-file-name", text)
        self.assertNotIn("FREESTANDING_DIR", text)
        self.assertNotIn("FREESTANDING_HEADERS", text)

    def test_installer_pins_and_probes_the_complete_newlib_toolchain(self) -> None:
        text = INSTALLER.read_text(encoding="utf-8")
        self.assertIn('version="15.2.0-1"', text)
        self.assertIn(
            'archive_sha256="aaaa8060c914851a3e5ee1ba82cc3d6f80972f90638a05c6e823a37557a33758"',
            text,
        )
        self.assertIn("xpack-dev-tools/riscv-none-elf-gcc-xpack/releases/download", text)
        self.assertIn("sourceforge.net/projects/riscv-none-elf-gcc-xpack", text)
        self.assertIn("--print-sysroot", text)
        self.assertIn("include/stdlib.h", text)
        self.assertIn("include/string.h", text)
        self.assertIn("-print-file-name=libc.a", text)
        self.assertIn("-march=rv32im_zicsr", text)
        self.assertIn("-mabi=ilp32", text)
        self.assertIn("ELF32", text)
        self.assertIn("Machine:[[:space:]]*RISC-V", text)

    def test_local_runtime_still_supplies_the_used_memory_primitives(self) -> None:
        runtime = RUNTIME.read_text(encoding="utf-8")
        for function in ("memcpy", "memmove", "memset", "memcmp", "strlen"):
            self.assertIn(f"{function}( ", runtime)

    def test_link_remains_freestanding_without_newlib_objects(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("-ffreestanding", text)
        self.assertIn("-fno-builtin", text)
        self.assertIn("-nostdlib -nostartfiles", text)
        self.assertIn("$(OBJECTS) -lgcc", text)
        self.assertNotIn("-lc", text)


if __name__ == "__main__":
    unittest.main()
