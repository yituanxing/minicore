"""Strict, cheap tests for the read-only SD1 host/card snapshot decoder."""

import hashlib
import struct
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools/ci"))
from product_sd1_card_snapshot import (
    CARD_PREFIX_BYTES, DDR_BYTES, card_state_from_host, decode_card_prefix,
)


class CardSnapshotTests(unittest.TestCase):
    def prefix(self, command_count=0, startup=True, media=bytes([42]) * 32):
        flags = bytes([1, 0, 0, 0, 0, 0, int(startup), 0, 1, 1])
        result = (
            struct.pack("<QII", 8192, 1, 8000) + media + flags +
            bytes([0, 0, 0, 255, 7]) + struct.pack("<IIQQQQQQI",
                0, 0, 0, 0, command_count, 0, 0, 74 if startup else 73, 0)
        )
        self.assertEqual(len(result), CARD_PREFIX_BYTES)
        return result

    def envelope(self, prefix, corrupt=False):
        body = prefix + bytes(6 + 514) + struct.pack("<IQ", 0, 0)
        digest = bytearray(hashlib.sha256(body).digest())
        if corrupt:
            digest[0] ^= 1
        return b"ACSDSP01" + struct.pack("<IQ", 1, len(body)) + bytes(digest) + body

    def host(self, card, media=bytes([42]) * 32):
        """Sparse DDR file; tests never allocate or read 256MiB RAM."""
        f = tempfile.NamedTemporaryFile(delete=False)
        self.addCleanup(lambda: Path(f.name).unlink(missing_ok=True))
        with f:
            f.write(b"AETSDCP1" + struct.pack("<Q", 1))
            for i in range(6):
                val = media.hex() if i == 3 else ("a" * 64)
                f.write(struct.pack("<Q", len(val)) + val.encode())
            for val in (b"", b""):
                f.write(struct.pack("<Q", len(val)) + val)
            f.write(struct.pack("<Q", 0) * 18)
            f.write(struct.pack("<Q", 0))  # empty UART
            f.write(struct.pack("<Q", 0) * 36)
            f.write(struct.pack("<Q", DDR_BYTES))
            f.seek(DDR_BYTES, 1)
            f.write(struct.pack("<Q", 0) * 3)
            f.write(struct.pack("<Q", len(card)))
            f.write(card)
        return Path(f.name)

    def test_zero_commands_not_a_cmd0_pass(self):
        decoded = decode_card_prefix(self.prefix())
        self.assertEqual(decoded["sd_command_count"], 0)
        self.assertIsNone(decoded["sd_last_command"])
        self.assertTrue(decoded["sd_74_clocks_seen"])
        self.assertTrue(decoded["sd_last_cs_high"])

    def test_valid_real_envelope_read_without_ddr(self):
        path = self.host(self.envelope(self.prefix(command_count=6)))
        decoded = card_state_from_host(path)
        self.assertEqual(decoded["sd_command_count"], 6)
        self.assertEqual(decoded["sd_cow_count"], 0)
        self.assertEqual(decoded["sd_media_sha256"], bytes([42]).hex() * 32)
        self.assertEqual(decoded["sd_card_schema_version"], 1)

    def test_detect_corrupted_sd_body_digest(self):
        path = self.host(self.envelope(self.prefix(), corrupt=True))
        with self.assertRaisesRegex(ValueError, "SHA256"):
            card_state_from_host(path)

    def test_reject_premature_74_clock_state(self):
        raw = bytearray(self.prefix(startup=False))
        # flags[6] is startup-clock complete at byte 54
        raw[54] = 0
        state = decode_card_prefix(raw)
        self.assertFalse(state["sd_74_clocks_seen"])
        self.assertEqual(state["sd_startup_valid_clocks"], 73)

    def test_reject_card_media_identity_difference(self):
        path = self.host(self.envelope(self.prefix()), media=bytes([99]) * 32)
        with self.assertRaisesRegex(ValueError, "media SHA"):
            card_state_from_host(path)


if __name__ == "__main__":
    unittest.main()
