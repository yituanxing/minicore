from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "tools/ci/l32_software_artifact_cache.sh"
EARLY = ROOT / ".github/workflows/l32-linux-boot.yml"
DEEPER = ROOT / ".github/workflows/l32-linux-deeper-boot.yml"
MINIMAL = ROOT / ".github/workflows/l32-minimal-initramfs.yml"
BUSYBOX = ROOT / ".github/workflows/l32-busybox-build.yml"


class L32SoftwareArtifactCacheContract(unittest.TestCase):
    def test_cache_fails_closed_on_inputs_pass_marker_and_output_hashes(self):
        text = CACHE.read_text()
        for required in (
            "L32_SOFTWARE_CACHE_HIT",
            "L32_SOFTWARE_CACHE_MISS",
            "L32_SOFTWARE_CACHE_MARK",
            "software-cache.txt",
            "input_key",
            "result_marker",
            "grep -qx",
            "sha256sum",
            "expected=",
            "actual=",
            "build did not produce its qualified PASS result",
            "build is missing qualified output",
        ):
            self.assertIn(required, text)

        for target in (
            "busybox)",
            "minimal-initramfs)",
            "busybox-initramfs)",
            "linux-payload)",
            "minimal-payload)",
            "busybox-payload)",
        ):
            self.assertIn(target, text)

    def test_cpu_validation_workflows_reuse_qualified_software(self):
        early = EARLY.read_text()
        deeper = DEEPER.read_text()
        minimal = MINIMAL.read_text()
        busybox = BUSYBOX.read_text()

        for text in (early, deeper, minimal, busybox):
            self.assertIn("tools/ci/l32_software_artifact_cache.sh", text)
            self.assertIn("bash -n tools/ci/l32_software_artifact_cache.sh", text)

        self.assertIn(
            "l32_software_artifact_cache.sh linux-payload tools/ci/l32_linux_payload_build.sh",
            early,
        )
        self.assertIn(
            "l32_software_artifact_cache.sh linux-payload tools/ci/l32_linux_payload_build.sh",
            deeper,
        )
        self.assertIn(
            "l32_software_artifact_cache.sh minimal-initramfs tools/ci/l32_minimal_initramfs_build.sh",
            minimal,
        )
        self.assertIn(
            "l32_software_artifact_cache.sh minimal-payload tools/ci/l32_minimal_init_payload_build.sh",
            minimal,
        )
        self.assertIn(
            "l32_software_artifact_cache.sh busybox \\\n            bash tools/ci/l32_busybox_build.sh",
            busybox,
        )
        self.assertIn(
            "l32_software_artifact_cache.sh busybox-initramfs tools/ci/l32_busybox_initramfs_build.sh",
            busybox,
        )
        self.assertIn(
            "l32_software_artifact_cache.sh busybox-payload tools/ci/l32_busybox_payload_build.sh",
            busybox,
        )


if __name__ == "__main__":
    unittest.main()
