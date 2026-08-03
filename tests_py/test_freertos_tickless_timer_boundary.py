from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
PLATFORM = ROOT / "software" / "freertos" / "aethercore" / "platform.c"
CONFIG = ROOT / "software" / "freertos" / "aethercore" / "FreeRTOSConfig.h"


class FreeRtosTicklessTimerBoundaryTest(unittest.TestCase):
    def test_timer_operations_are_isolated_before_tick_suppression(self) -> None:
        text = PLATFORM.read_text(encoding="utf-8")
        self.assertIn("static uint64_t aether_read_mtime( void )", text)
        self.assertIn(
            "static volatile uint32_t * aether_mtimecmp_for_current_hart( void )",
            text,
        )
        self.assertIn("static void aether_write_mtimecmp", text)
        self.assertIn("highBefore = time[ 1 ]", text)
        self.assertIn("low = time[ 0 ]", text)
        self.assertIn("highAfter = time[ 1 ]", text)
        self.assertIn("while( highBefore != highAfter )", text)
        self.assertIn('csrr %0, mhartid', text)

        first = text.index("compare[ 0 ] = UINT32_MAX")
        high = text.index("compare[ 1 ] =", first)
        low = text.index("compare[ 0 ] = ( uint32_t ) deadline", high)
        self.assertLess(first, high)
        self.assertLess(high, low)

    def test_periodic_setup_uses_the_same_future_tickless_primitives(self) -> None:
        text = PLATFORM.read_text(encoding="utf-8")
        setup = text[text.index("void vPortSetupTimerInterrupt") :]
        self.assertIn("const uint64_t now = aether_read_mtime()", setup)
        self.assertIn(
            "volatile uint32_t * const compare = aether_mtimecmp_for_current_hart()",
            setup,
        )
        self.assertIn("aether_write_mtimecmp( compare, firstDeadline )", setup)
        self.assertIn(
            "ullNextTime = firstDeadline + ( uint64_t ) uxTimerIncrementsForOneTick",
            setup,
        )
        self.assertEqual(setup.count("compare[ 0 ]"), 0)
        self.assertEqual(setup.count("compare[ 1 ]"), 0)

    def test_tickless_mode_is_not_enabled_by_the_refactor_alone(self) -> None:
        text = CONFIG.read_text(encoding="utf-8")
        self.assertIn("configUSE_TICKLESS_IDLE                 0", text)
        self.assertNotIn("vPortSuppressTicksAndSleep", PLATFORM.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
