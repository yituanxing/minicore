from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "software" / "freertos" / "aethercore"
FREESTANDING = APP / "freestanding"
MAKEFILE = ROOT / "Makefile.freertos"
RUNTIME = APP / "runtime.c"


class FreeRtosFreestandingHeadersTest(unittest.TestCase):
    def test_stdlib_header_does_not_offer_hosted_heap_or_process_apis(self) -> None:
        text = (FREESTANDING / "stdlib.h").read_text(encoding="utf-8")
        self.assertIn("#include <stddef.h>", text)
        for forbidden in ("malloc", "calloc", "realloc", "free(", "exit(", "abort(", "system("):
            self.assertNotIn(forbidden, text)

    def test_string_header_matches_the_local_runtime_surface(self) -> None:
        header = (FREESTANDING / "string.h").read_text(encoding="utf-8")
        runtime = RUNTIME.read_text(encoding="utf-8")
        functions = ("memcpy", "memmove", "memset", "memcmp", "strlen")
        for function in functions:
            self.assertIn(f"{function}(", header)
            self.assertIn(f"{function}( ", runtime)
        self.assertNotIn("strcpy", header)
        self.assertNotIn("strcat", header)
        self.assertNotIn("strtok", header)

    def test_freestanding_headers_precede_all_upstream_include_paths(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("FREESTANDING_DIR := $(APP_DIR)/freestanding", text)
        include_line = next(line for line in text.splitlines() if line.startswith("INCLUDES :="))
        self.assertTrue(include_line.startswith("INCLUDES := -I$(FREESTANDING_DIR)"))
        self.assertLess(include_line.index("-I$(FREESTANDING_DIR)"), include_line.index("-I$(SOURCE_DIR)/include"))
        self.assertIn("FREESTANDING_HEADERS :=", text)
        self.assertIn("$(FREESTANDING_HEADERS)", text)

    def test_link_remains_explicitly_freestanding(self) -> None:
        text = MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("-ffreestanding", text)
        self.assertIn("-fno-builtin", text)
        self.assertIn("-nostdlib -nostartfiles", text)
        self.assertIn("$(OBJECTS) -lgcc", text)
        self.assertNotIn("-lc", text)


if __name__ == "__main__":
    unittest.main()
