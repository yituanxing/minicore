"""Cheap SD1 transport A/B safety contract; no Verilator, Linux or Debian."""

import hashlib
import os
import pathlib
import struct
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD = ROOT / "tools/ci/product_sd1_transport_short_build.sh"
AB = ROOT / "tools/ci/product_sim_ab.sh"
SOURCE = ROOT / "software/rv64_userspace/product_sd1_transport_smoke.c"
LINKER = ROOT / "software/rv64_userspace/product_sd1_transport.ld"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ProductSd1TransportOnlyContract(unittest.TestCase):
    def test_wellformed_minimal_media_sha_and_tampering(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = pathlib.Path(temporary)
            firmware = base / "firmware.bin"
            dtb = base / "board.dtb"
            card = base / "card-64m.img"
            firmware.write_bytes(b"RV64 transport-only fixture")
            dtb.write_bytes(b"standalone DTB manifest SHA fixture")
            with card.open("wb") as fp:
                fp.truncate(64 * 1024 * 1024)
            mbr = bytearray(512)
            struct.pack_into("<BBBBBBBBII", mbr, 446,
                             0, 0, 2, 0, 0x83, 0, 0, 0, 2048, 129024)
            mbr[510:512] = b"\x55\xaa"
            with card.open("r+b") as fp:
                fp.write(mbr)
                fp.seek(2048 * 512 + 1024 + 56)
                fp.write(b"\x53\xef")
            manifest = base / "result.txt"
            manifest.write_text("\n".join([
                "qualification=PRODUCT_SD1_TRANSPORT_INPUTS_ONLY_NOT_LINUX_NOT_G5",
                f"source_sha256={sha(SOURCE)}",
                f"linker_sha256={sha(LINKER)}",
                f"build_script_sha256={sha(BUILD)}",
                f"firmware_path={firmware}",
                f"firmware_sha256={sha(firmware)}",
                f"dtb_path={dtb}",
                f"dtb_sha256={sha(dtb)}",
                f"sd_image_path={card}",
                f"sd_image_sha256={sha(card)}",
                "",
            ]))
            env = {"AETHERCORE_SD1_TRANSPORT_INPUT_ROOT": str(base)}
            run = subprocess.run(["bash", str(BUILD), "preflight"], cwd=ROOT,
                                 env={**os.environ, **env},
                                 text=True, capture_output=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn("PRODUCT_SD1_TRANSPORT_INPUT_PREFLIGHT_PASS", run.stdout)
            with card.open("r+b") as fp:
                fp.seek(510)
                fp.write(b"\x00")
            run = subprocess.run(["bash", str(BUILD), "preflight"], cwd=ROOT,
                                 env={**os.environ, **env},
                                 text=True, capture_output=True)
            self.assertNotEqual(run.returncode, 0)
            self.assertIn("REJECTED", run.stderr)

    def test_opt_in_requires_real_product_sd1_top_before_any_build(self):
        env = {**os.environ, "AETHERCORE_ALLOW_PRODUCT_AB": "1",
               "AETHERCORE_SD1_TRANSPORT_SHORT": "1",
               "TOP": "AetherCoreProductOpenSbiRV64SimTop"}
        run = subprocess.run(["bash", str(AB)], cwd=ROOT, env=env,
                             capture_output=True, text=True)
        self.assertEqual(run.returncode, 4, run.stderr)
        self.assertIn("requires explicit", run.stderr)

    def test_explicit_debian_qualification_is_isolated(self):
        script = AB.read_text()
        self.assertIn("if (( sd1 && ! sd1_transport )); then\n"
                      "  # A candidate is eligible for the MANUAL SD1 runner", script)
        self.assertIn("qualification=PRODUCT_SD1_TRANSPORT_SHORT_AB_IDENTICAL_NOT_LINUX_NOT_G5_ELIGIBLE", script)
        self.assertIn("linux_pid1=NOT_RUN", script)
        self.assertIn("g5_unlock=FORBIDDEN", script)
        self.assertIn("grep -Fq 'SD1 TRANSPORT SPI PLIC11 CMD17 OK'", script)
        self.assertIn("first_load_paddr == 0x80000000", BUILD.read_text())
        self.assertIn("KEEP(*(.text.start))", LINKER.read_text())
        for shell in (BUILD, AB):
            run = subprocess.run(["bash", "-n", str(shell)], cwd=ROOT,
                                 capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
        run = subprocess.run(["gcc", "-std=c11", "-ffreestanding", "-Wall",
                              "-Wextra", "-fsyntax-only", str(SOURCE)], cwd=ROOT,
                             capture_output=True, text=True)
        self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == "__main__":
    unittest.main()
