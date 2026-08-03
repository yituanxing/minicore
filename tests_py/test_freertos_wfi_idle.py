from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "software" / "freertos" / "aethercore"


class FreeRtosWfiIdleTest(unittest.TestCase):
    def test_tickless_idle_replaces_the_one_tick_idle_hook(self) -> None:
        config = (APP / "FreeRTOSConfig.h").read_text(encoding="utf-8")
        main = (APP / "main.c").read_text(encoding="utf-8")
        platform = (APP / "platform.c").read_text(encoding="utf-8")

        self.assertIn("configUSE_IDLE_HOOK                     0", config)
        self.assertIn("configUSE_TICKLESS_IDLE                 1", config)
        self.assertNotIn("void vApplicationIdleHook( void )", main)
        self.assertIn("void vPortSuppressTicksAndSleep", platform)
        self.assertIn('"fence iorw, iorw\\n\\twfi"', platform)

    def test_workload_requires_a_complete_tickless_wake(self) -> None:
        text = (APP / "main.c").read_text(encoding="utf-8")
        self.assertIn("TICKLESS_PROOF_DELAY_TICKS 32U", text)
        self.assertIn("vTaskDelay( TICKLESS_PROOF_DELAY_TICKS )", text)
        self.assertIn("configASSERT( aetherTicklessEntries >= 1U )", text)
        self.assertIn("configASSERT( aetherTicklessWakeups >= 1U )", text)
        self.assertIn(
            "configASSERT( aetherTicklessSuppressedTicks >= MINIMUM_SUPPRESSED_TICKS )",
            text,
        )
        self.assertIn(
            "FREERTOS TICKLESS PASS sleep>=1 wake>=1 suppressed>=2", text
        )


if __name__ == "__main__":
    unittest.main()
