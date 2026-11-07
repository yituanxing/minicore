"""Guard deterministic embedded-Debian cpio metadata without mutating SD1."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
BUILD_SCRIPT = ROOT / "tools/ci/rv64_debian_rootfs_build.sh"
PINNED_EPOCH = 1786242883


class ProductDebianRootfsRepro(unittest.TestCase):
    def test_stage_and_timestamp_provenance_are_explicit(self):
        source = BUILD_SCRIPT.read_text()
        self.assertIn('if [[ "${STAGE}" != sd1 ]]; then', source)
        self.assertIn('RV64_LINUX_BUILD_TIMESTAMP', source)
        self.assertIn('initramfs_mtime_epoch=', source)
        self.assertIn('sudo find -P "${ROOTFS_DIR}" -xdev -depth', source)
        self.assertIn('touch -h -d "@${initramfs_mtime_epoch}" -- {} +', source)
        self.assertIn('[[ -d "${ROOTFS_DIR}" && ! -L "${ROOTFS_DIR}"', source)

    def test_normalization_touches_only_links_not_their_targets(self):
        # Run the exact path-preserving command without sudo on a disposable
        # tree.  The real staging script uses sudo only for Debian device
        # nodes and files imported with privileged numeric ownership.
        with tempfile.TemporaryDirectory() as temporary:
            scratch = Path(temporary)
            staged = scratch / "rootfs"
            nested = staged / "usr" / "lib"
            nested.mkdir(parents=True)
            regular = nested / "sample"
            regular.write_text("unchanged bytes")
            outside = scratch / "must-not-touch"
            outside.write_text("outside bytes")
            os.utime(outside, (1_650_000_000, 1_650_000_000))
            link = nested / "absolute-guest-link"
            link.symlink_to(outside)
            outside_before = outside.stat().st_mtime_ns

            subprocess.run(
                ["find", "-P", str(staged), "-xdev", "-depth", "-exec",
                 "touch", "-h", "-d", f"@{PINNED_EPOCH}", "--", "{}", "+"],
                check=True,
            )

            self.assertEqual(regular.stat().st_mtime_ns, PINNED_EPOCH * 10**9)
            self.assertEqual(link.lstat().st_mtime_ns, PINNED_EPOCH * 10**9)
            self.assertEqual(staged.stat().st_mtime_ns, PINNED_EPOCH * 10**9)
            self.assertEqual(outside.stat().st_mtime_ns, outside_before)
            self.assertEqual(regular.read_text(), "unchanged bytes")


if __name__ == "__main__":
    unittest.main()
