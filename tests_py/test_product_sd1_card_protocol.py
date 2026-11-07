"""Run the real Product SDHC pin model without RTL or a Debian rootfs.

The sparse fixture contains only an MBR and the ext4 magic at the correct
partition-relative byte offset. The host C++ protocol probe must exercise
real SPI edges, CMD0/CMD8/ACMD41, CSD, CRC, single/multiblock read/write and
CMD12/stop-token handling through a session-local COW overlay.
"""

from pathlib import Path
import hashlib
import shutil
import struct
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / "tools/ci/product_sim_sd1_card_probe.cpp"
SIM = ROOT / "sim"


class ProductSd1CardProtocol(unittest.TestCase):
    @unittest.skipUnless(shutil.which("c++"), "C++ compiler is unavailable")
    def test_pin_level_card_protocol_keeps_source_image_immutable(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            raw_image = work / "card-64m.img"
            binary = work / "card-probe"
            first_lba = 2048
            sectors = (64 * 1024 * 1024) // 512
            with raw_image.open("wb") as disk:
                disk.truncate(64 * 1024 * 1024)
            mbr = bytearray(512)
            struct.pack_into("<BBBBBBBBII", mbr, 446,
                             0, 0, 2, 0, 0x83, 0xfe, 0xff, 0xff,
                             first_lba, sectors - first_lba)
            mbr[510:512] = b"\x55\xaa"
            with raw_image.open("r+b") as disk:
                disk.write(mbr)
                disk.seek(first_lba * 512 + 1024 + 56)
                disk.write(b"\x53\xef")

            input_sha = hashlib.sha256(raw_image.read_bytes()).hexdigest()
            compile_result = subprocess.run(
                ["c++", "-std=c++20", "-O2", "-Wall", "-Wextra", "-Werror",
                 f"-I{SIM}", str(PROBE), "-o", str(binary)],
                capture_output=True, text=True, check=False, timeout=90,
            )
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            result = subprocess.run(
                [str(binary), str(raw_image)], capture_output=True,
                text=True, check=False, timeout=20,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("PRODUCT_SD1_CARD_MULTIBLOCK_CRC_STOP_PREFLIGHT_PASS", result.stdout)
            self.assertIn("PRODUCT_SD1_CARD_PIN_PREFLIGHT_PASS", result.stdout)
            self.assertEqual(hashlib.sha256(raw_image.read_bytes()).hexdigest(), input_sha,
                             "SD model must not write to its pinned raw source image")

            # A corrupt card must fail at host preflight rather than make a
            # subsequent full Product Linux run hang during MMC enumeration.
            with raw_image.open("r+b") as disk:
                disk.seek(510)
                disk.write(b"\x00")
            rejected = subprocess.run(
                [str(binary), str(raw_image)], capture_output=True,
                text=True, check=False, timeout=5,
            )
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("Product SD1 SD image must have an MBR", rejected.stderr)


if __name__ == "__main__":
    unittest.main()
