from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
PLATFORM = ROOT / "software" / "freertos" / "aethercore" / "platform.c"
CONFIG = ROOT / "software" / "freertos" / "aethercore" / "FreeRTOSConfig.h"


class FreeRtosTicklessTimerBoundaryTest(unittest.TestCase):
    def test_timer_operations_are_shared_by_periodic_and_tickless_paths(self) -> None:
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

    def test_riscv_port_is_explicitly_connected_to_the_application_sleep_hook(self) -> None:
        config = CONFIG.read_text(encoding="utf-8")
        self.assertIn(
            "void vPortSuppressTicksAndSleep( uint32_t expectedIdleTicks )", config
        )
        self.assertIn("portSUPPRESS_TICKS_AND_SLEEP( expectedIdleTicks )", config)
        self.assertIn(
            "vPortSuppressTicksAndSleep( ( uint32_t ) ( expectedIdleTicks ) )",
            config,
        )

    def test_tickless_mode_disables_mie_closes_the_race_and_steps_ticks(self) -> None:
        config = CONFIG.read_text(encoding="utf-8")
        text = PLATFORM.read_text(encoding="utf-8")
        function = text[text.index("void vPortSuppressTicksAndSleep") :]

        self.assertIn("configUSE_TICKLESS_IDLE                 1", config)
        self.assertIn("configEXPECTED_IDLE_TIME_BEFORE_SLEEP  2", config)
        self.assertIn("configUSE_IDLE_HOOK                     0", config)
        self.assertIn("aether_disable_machine_interrupts()", function)
        self.assertIn("eTaskConfirmSleepModeStatus() == eAbortSleep", function)
        self.assertIn("now >= nextPeriodicDeadline", function)
        self.assertIn("expectedIdleTicks - 1U", function)
        self.assertIn("aether_write_mtimecmp( compare, sleepDeadline )", function)
        self.assertIn("ullNextTime = sleepDeadline + tickCounts", function)
        self.assertIn('"fence iorw, iorw\\n\\twfi"', function)
        self.assertIn("vTaskStepTick( suppressedTicks )", function)
        self.assertIn("aether_restore_machine_interrupts( previousMstatus )", function)

    def test_timer_wake_leaves_the_final_tick_to_the_normal_isr(self) -> None:
        text = PLATFORM.read_text(encoding="utf-8")
        function = text[text.index("void vPortSuppressTicksAndSleep") :]
        timer_path = function[function.index("if( now >= sleepDeadline )") :]

        self.assertIn("suppressedTicks = expectedIdleTicks - 1U", timer_path)
        self.assertIn("vTaskStepTick( suppressedTicks )", timer_path)
        self.assertIn("final", timer_path)
        self.assertNotIn("xTaskIncrementTick();", timer_path)

    def test_early_wake_restores_the_first_future_periodic_deadline(self) -> None:
        text = PLATFORM.read_text(encoding="utf-8")
        function = text[text.index("void vPortSuppressTicksAndSleep") :]
        early = function[function.index("else\n    {", function.index("if( now >= sleepDeadline )")) :]

        self.assertIn("elapsedTicks", early)
        self.assertIn("restoredDeadline = nextPeriodicDeadline +", early)
        self.assertIn("aether_write_mtimecmp( compare, restoredDeadline )", early)
        self.assertIn("ullNextTime = restoredDeadline + tickCounts", early)
        self.assertIn("aetherTicklessEarlyWakeups++", early)


if __name__ == "__main__":
    unittest.main()
