"""Offline safety checks for the explicit long SD1 savable-state relay."""

import hashlib
import importlib.util
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
INSPECTOR = ROOT / "tools/ci/product_sd1_relay_state.py"
RELAY = ROOT / "tools/ci/product_sd1_checkpoint_relay.sh"
BACKGROUND = ROOT / "tools/ci/product_sd1_background_runner.sh"
CHECKPOINT = ROOT / "sim/product_rv64_checkpoint_sd1_main.cpp"
SHORT = ROOT / "tools/ci/product_checkpoint_sd1_short.sh"

spec = importlib.util.spec_from_file_location("sd1_relay_state", INSPECTOR)
sd1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sd1)


def u64(n):
    return struct.pack("<Q", n)


def string(value):
    return u64(len(value)) + value


def make_host(path: Path, uart: bytes, cycles=2200000000, commits=321):
    fnv = sd1.FNV_OFFSET
    for byte in uart:
        fnv = ((fnv ^ byte) * sd1.FNV_PRIME) & ((1 << 64) - 1)
    observer = [cycles, commits] + [0] * 11 + [fnv, 0]
    with path.open("wb") as stream:
        stream.write(sd1.MAGIC + u64(1))
        for _ in range(6):
            stream.write(string(b"a" * 64))
        stream.write(string(b""))  # UART trigger
        stream.write(string(b""))  # UART input
        stream.write(u64(0) + u64(cycles) + u64(0))
        for value in observer:
            stream.write(u64(value))
        stream.write(string(uart))
        for value in (1, int(b"login:" in uart), 0):
            stream.write(u64(value))
        # Sparse test fixture still exceeds the full DDR safety size gate.
        stream.truncate(268435457)


def write_ready(directory: Path, cycles):
    host = directory / "persistent.pre.host"
    content = [
        "snapshot_ready=1", "top=AetherCoreProductDebianSdRV64SimTop",
        "schema=AETSDCP1", "saves_full_rtl_ddr_sd=1",
        f"boot_cycles={cycles}",
        "snapshot_host_sha256=" + sd1.sha256(host),
    ] + [f"{name}_sha256=" + "a" * 64 for name in (
        "model", "firmware", "dtb", "sd_image", "linux_image", "rootfs_result")]
    (directory / ".snapshot-ready").write_text("\n".join(content) + "\n")


class ProductSd1RelayTest(unittest.TestCase):
    def test_host_observer_and_fatal_uart_detection(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            uart = (b"OpenSBI v1.6\nVFS: Mounted root (ext4 filesystem)\n"
                    b"Run /sbin/init as init process\nDebian login:")
            host = work / "persistent.pre.host"
            make_host(host, uart)
            write_ready(work, 2200000000)
            result = sd1.validate(host, work / ".snapshot-ready", 320)
            self.assertEqual(result["cycles"], 2200000000)
            self.assertTrue(result["login"])
            self.assertTrue(result["ext4_root_mounted"])
            self.assertTrue(result["native_init_started"])
            with self.assertRaisesRegex(ValueError, "did not advance"):
                sd1.validate(host, work / ".snapshot-ready", 321)
            make_host(host, b"Kernel panic - not syncing\n", commits=322)
            write_ready(work, 2200000000)
            with self.assertRaisesRegex(ValueError, "fatal diagnostic"):
                sd1.validate(host, work / ".snapshot-ready", 321)

    def test_host_snapshot_tampering_and_relay_default_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            host = work / "persistent.pre.host"
            make_host(host, b"OpenSBI v1.6\n")
            write_ready(work, 2200000000)
            self.assertEqual(sd1.validate(host, work / ".snapshot-ready", -1)["commits"], 321)
            with host.open("r+b") as stream:
                stream.seek(1024)
                stream.write(b"x")
            with self.assertRaisesRegex(ValueError, "SHA differs"):
                sd1.validate(host, work / ".snapshot-ready", -1)
        gate = subprocess.run(["bash", str(RELAY), "advance", "/unused", "2200000000"],
                              cwd=ROOT, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(gate.returncode, 0)
        self.assertIn("explicit full SD1", gate.stderr)

    def test_short_boundaries_preserved_with_explicit_four_billion_opt_in(self):
        cpp = CHECKPOINT.read_text()
        shell = SHORT.read_text()
        self.assertIn("SD1_FULL_DEBIAN_SIGNED_RELAY", cpp)
        self.assertIn("SD1_FULL_DEBIAN_SIGNED_RELAY", shell)
        self.assertIn("longRelay?4000000000ULL:1800000000ULL", cpp)
        self.assertIn("MAX_CYCLE=1800000000", shell)
        self.assertIn("NEXT-BOOT_CYCLES <= 20000000", shell)
        self.assertIn("next-previous <= 100000000", RELAY.read_text())
        background = BACKGROUND.read_text()
        self.assertIn("AETHERCORE_SD1_RELAY_STORE", background)
        self.assertIn("PRODUCT_SD1_BACKGROUND_CHECKPOINT_DURABLE", background)
        self.assertNotIn("upload-artifact", background)
        syntax = subprocess.run(["bash", "-n", str(RELAY), str(SHORT), str(BACKGROUND)],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(syntax.returncode, 0, syntax.stderr)


if __name__ == "__main__":
    unittest.main()
