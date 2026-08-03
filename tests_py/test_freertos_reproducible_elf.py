from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAKEFILE = ROOT / "Makefile.freertos"


class FreeRtosReproducibleElfTest(unittest.TestCase):
    def test_build_directory_is_mapped_to_one_virtual_debug_root(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("DEBUG_PREFIX_ROOT := /aethercore/freertos-build", text)
        self.assertIn(
            "-ffile-prefix-map=$(abspath $(BUILD_DIR))=$(DEBUG_PREFIX_ROOT)", text
        )
        self.assertIn(
            "-fdebug-prefix-map=$(abspath $(BUILD_DIR))=$(DEBUG_PREFIX_ROOT)", text
        )
        self.assertIn(
            "-fmacro-prefix-map=$(abspath $(BUILD_DIR))=$(DEBUG_PREFIX_ROOT)", text
        )
        self.assertIn("COMMON_FLAGS := $(SYSROOT_FLAGS) $(ARCH_FLAGS) $(DEBUG_PREFIX_FLAGS)", text)
        self.assertIn("CFLAGS := $(COMMON_FLAGS)", text)
        self.assertIn("ASFLAGS := $(COMMON_FLAGS)", text)

    def test_debug_mapping_does_not_remove_debug_information(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("CFLAGS := $(COMMON_FLAGS) -std=c11 -Os -g", text)
        self.assertNotIn("--strip-debug", text)
        self.assertNotIn("strip $(ELF)", text)


if __name__ == "__main__":
    unittest.main()
