"""Byte-level contract for an explicitly authorized, zero-cycle SD1 model migration.

The real migration lives in sim/product_rv64_checkpoint_sd1_main.cpp.  These
fixtures check the deliberately tiny binary exception: only the model SHA
field may change, while all serialized RTL and Host state must be identical.
No Debian guest, cache, or Verilator execution is required for these tests.
"""

import io
from pathlib import Path
import unittest


MAGIC = b"AETSDCP1"
VERSION = (1).to_bytes(8, "little")
SHA_SIZE = (64).to_bytes(8, "little")
MODEL_OFFSET = 24
MODEL_END = 88
OLD = b"5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc"
NEW = b"8" * 64


def assert_exact_model_only_transition(old_host: bytes, new_host: bytes,
                                       old_rtl: bytes, new_rtl: bytes) -> None:
    """Mirror the runner's streaming byte check without retaining 256 MiB DDR."""
    if len(old_host) != len(new_host) or len(old_host) < MODEL_END:
        raise ValueError("different Host extent")
    if old_host[:MODEL_OFFSET] != new_host[:MODEL_OFFSET]:
        raise ValueError("changed Host header")
    if old_host[:MODEL_OFFSET] != MAGIC + VERSION + SHA_SIZE:
        raise ValueError("invalid AETSDCP1 model field layout")
    if old_host[MODEL_OFFSET:MODEL_END] != OLD:
        raise ValueError("unknown frozen source model")
    if new_host[MODEL_OFFSET:MODEL_END] != NEW:
        raise ValueError("wrong candidate model")
    if old_rtl != new_rtl:
        raise ValueError("changed RTL")
    left = io.BytesIO(old_host)
    right = io.BytesIO(new_host)
    left.seek(MODEL_END)
    right.seek(MODEL_END)
    while True:
        lchunk, rchunk = left.read(64 * 1024), right.read(64 * 1024)
        if lchunk != rchunk:
            raise ValueError("changed Host state after model SHA")
        if not lchunk:
            return


class ModelMigrationV1Contract(unittest.TestCase):
    def setUp(self):
        self.tail = bytes(range(256)) * 1025  # Crosses several 64-KiB blocks.
        self.old = MAGIC + VERSION + SHA_SIZE + OLD + self.tail
        self.new = MAGIC + VERSION + SHA_SIZE + NEW + self.tail
        self.rtl = b"verilated-savable-state\x00" * 101

    def check(self, host=None, rtl=None):
        assert_exact_model_only_transition(
            self.old, self.new if host is None else host,
            self.rtl, self.rtl if rtl is None else rtl)

    def test_only_model_sha_changed_passes(self):
        self.check()

    def test_changed_ddr_or_sd_tail_rejected(self):
        data = bytearray(self.new)
        data[-32] ^= 0x20
        with self.assertRaisesRegex(ValueError, "changed Host state"):
            self.check(host=bytes(data))

    def test_changed_host_header_rejected(self):
        data = bytearray(self.new)
        data[15] ^= 0x01
        with self.assertRaisesRegex(ValueError, "changed Host header"):
            self.check(host=bytes(data))

    def test_changed_model_field_length_rejected(self):
        data = bytearray(self.new)
        data[16] = 63
        with self.assertRaisesRegex(ValueError, "changed Host header"):
            self.check(host=bytes(data))

    def test_truncated_host_rejected(self):
        with self.assertRaisesRegex(ValueError, "different Host extent"):
            self.check(host=self.new[:-1])

    def test_unexpected_candidate_sha_rejected(self):
        data = bytearray(self.new)
        data[MODEL_OFFSET] ^= 0x01
        with self.assertRaisesRegex(ValueError, "wrong candidate model"):
            self.check(host=bytes(data))

    def test_changed_rtl_rejected(self):
        with self.assertRaisesRegex(ValueError, "changed RTL"):
            self.check(rtl=self.rtl + b"\x00")

    def test_runner_keeps_separate_migrate_gate_and_atomic_publish(self):
        root = Path(__file__).resolve().parents[1]
        code = (root / "sim/product_rv64_checkpoint_sd1_main.cpp").read_text()
        for required in (
            'mode=="migrate-v1"',
            'AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM',
            'FROZEN_SIGNED_SD1_MODEL_MIGRATE_V1',
            'if (!migrating && currentModelSha!=id.model)',
            'readHost(prefix+".pre.host",id,policy,ctx,mem,transport,card,obs)',
            'readRtl(prefix+".pre.rtl",top)',
            'requireExactMigrationBytes(',
            'std::filesystem::rename(stagingDir,targetDir)',
        ):
            self.assertIn(required, code)


if __name__ == "__main__":
    unittest.main()
