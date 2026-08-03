from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "software" / "freertos" / "aethercore"


class FreeRtosWfiIdleTest(unittest.TestCase):
    def test_idle_hook_is_enabled_without_tickless_idle(self) -> None:
        text = (APP / "FreeRTOSConfig.h").read_text(encoding="utf-8")
        self.assertIn("configUSE_IDLE_HOOK                     1", text)
        self.assertIn("configUSE_TICKLESS_IDLE                 0", text)

    def test_idle_hook_executes_wfi_and_requires_a_complete_wake(self) -> None:
        text = (APP / "main.c").read_text(encoding="utf-8")
        self.assertIn("void vApplicationIdleHook( void )", text)
        self.assertIn('__asm__ volatile ( "wfi" ::: "memory" );', text)
        self.assertIn("idleWfiEntries++", text)
        self.assertIn("idleWfiWakeups++", text)
        self.assertIn("IDLE_WAKE_ATTEMPTS 4U", text)
        self.assertIn("attempt < IDLE_WAKE_ATTEMPTS", text)
        self.assertIn("vTaskDelay( 1 )", text)
        self.assertIn("configASSERT( idleWfiEntries >= 1U )", text)
        self.assertIn("configASSERT( idleWfiWakeups >= 1U )", text)
        self.assertIn("FREERTOS IDLE PASS wfi>=1 wake>=1", text)


if __name__ == "__main__":
    unittest.main()
