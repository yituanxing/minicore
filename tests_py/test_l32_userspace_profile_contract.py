from __future__ import annotations

from pathlib import Path
import os
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "tools" / "ci" / "l32_userspace_profile.sh"


class L32UserspaceProfileContract(unittest.TestCase):
    def run_profile(self, profile: str | None) -> dict[str, str]:
        env = os.environ.copy()
        if profile is None:
            env.pop("AETHERCORE_L32_USERSPACE_PROFILE", None)
        else:
            env["AETHERCORE_L32_USERSPACE_PROFILE"] = profile
        completed = subprocess.run(
            ["bash", str(PROFILE)],
            cwd=ROOT,
            env=env,
            check=True,
            text=True,
            capture_output=True,
        )
        return dict(line.split("=", 1) for line in completed.stdout.splitlines())

    def test_default_profile_preserves_historical_rv32ima_identity(self) -> None:
        values = self.run_profile(None)
        self.assertEqual(values["profile"], "rv32ima")
        self.assertEqual(values["isa"], "rv32ima_zicsr_zifencei")
        self.assertEqual(values["abi"], "ilp32")
        self.assertEqual(values["require_c"], "0")
        self.assertEqual(values["build_suffix"], "")
        self.assertEqual(values["opensbi_isa"], "rv32ima_zicsr_zifencei")
        self.assertEqual(values["dtb_isa"], "rv32ima_zicsr_zifencei_sstc")
        self.assertTrue(values["busybox_build_dir"].endswith("/build/l32-busybox"))
        self.assertTrue(values["runtime_probe_build_dir"].endswith("/build/l32-runtime-probe"))
        self.assertTrue(values["real_programs_build_dir"].endswith("/build/l32-real-programs"))
        self.assertTrue(values["linux_busybox_build_dir"].endswith("/build/l32-linux-busybox"))
        self.assertTrue(values["payload_build_dir"].endswith("/build/l32-busybox-shell-boot"))

    def test_rv32imac_profile_uses_isolated_c_identity_and_namespaces(self) -> None:
        values = self.run_profile("rv32imac")
        self.assertEqual(values["profile"], "rv32imac")
        self.assertEqual(values["isa"], "rv32imac_zicsr_zifencei")
        self.assertEqual(values["abi"], "ilp32")
        self.assertEqual(values["require_c"], "1")
        self.assertEqual(values["build_suffix"], "-rv32imac")
        self.assertEqual(values["opensbi_isa"], "rv32imac_zicsr_zifencei")
        self.assertEqual(values["dtb_isa"], "rv32imac_zicsr_zifencei_sstc")
        for key in (
            "busybox_build_dir",
            "runtime_probe_build_dir",
            "real_programs_build_dir",
            "linux_busybox_build_dir",
            "payload_build_dir",
        ):
            self.assertTrue(values[key].endswith("-rv32imac"), (key, values[key]))

    def test_unknown_profile_fails_closed(self) -> None:
        env = os.environ.copy()
        env["AETHERCORE_L32_USERSPACE_PROFILE"] = "rv32gc"
        completed = subprocess.run(
            ["bash", str(PROFILE)],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
        )
        self.assertEqual(completed.returncode, 2)
        self.assertIn("unsupported L32 userspace profile", completed.stderr)


if __name__ == "__main__":
    unittest.main()
