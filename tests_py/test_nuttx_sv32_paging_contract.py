from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class NuttXSv32PagingContractTest(unittest.TestCase):
    def test_manifest_remains_pinned_to_nuttx_13(self):
        text = (ROOT / "software/nuttx/manifest.env").read_text()
        self.assertIn("NUTTX_VERSION=13.0.0", text)
        self.assertIn("NUTTX_COMMIT=273c77128b6698f0c95f0d7cde1d0bb803782021", text)
        self.assertIn("NUTTX_APPS_COMMIT=20ffb1a3a3b590d52890ee865a28442390e5d16c", text)

    def test_audit_is_upstream_first_and_fail_closed(self):
        text = (ROOT / "tools/ci/nuttx_sv32_paging_build_audit.sh").read_text()
        for required in (
            "rv-virt:${CONFIG_NAME}",
            "knsh_paging knsh32_paging",
            "CONFIG_BUILD_KERNEL",
            "CONFIG_ARCH_ADDRENV",
            "CONFIG_ARCH_USE_MMU",
            "CONFIG_PAGING",
            "riscv_fillpage",
            "up_addrenv_create",
            "runtime_qualification=not-yet-attempted",
        ):
            self.assertIn(required, text)

    def test_stage_does_not_apply_aethercore_overlay_yet(self):
        text = (ROOT / "tools/ci/nuttx_sv32_paging_build_audit.sh").read_text()
        self.assertNotIn("make_aethercore_nuttx_overlay.py", text)
        self.assertNotIn("make_aethercore_nuttx_protected_overlay.py", text)


if __name__ == "__main__":
    unittest.main()
