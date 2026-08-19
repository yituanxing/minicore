from __future__ import annotations

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
ENV = ROOT / "software/rv64/opensbi_first_exec.env"
PAYLOAD = ROOT / "software/rv64/opensbi_smode_payload.S"
LINKER = ROOT / "software/rv64/opensbi_smode_payload.ld"
BUILD = ROOT / "tools/ci/rv64_opensbi_first_exec.sh"
ENSURE = ROOT / "tools/ensure_riscv64_linux_gcc_13_3.sh"
SHIM = ROOT / "sim/rv64_opensbi_shim/VAetherCoreOpenSbiSimTop.h"
WORKFLOW = ROOT / ".github/workflows/rv64-opensbi-first-exec.yml"


def parse_env(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        result[key] = value.strip("'\"")
    return result


class RV64OpenSbiFirstExecContract(unittest.TestCase):
    def test_frozen_unchanged_opensbi_identity_and_layout(self) -> None:
        env = parse_env(ENV)
        self.assertEqual(env["RV64_OPENSBI_VERSION"], "1.6")
        self.assertEqual(
            env["RV64_OPENSBI_COMMIT"],
            "bd613dd92113f683052acfb23d9dc8ba60029e0a",
        )
        self.assertEqual(env["RV64_OPENSBI_XLEN"], "64")
        self.assertEqual(env["RV64_OPENSBI_ISA"], "rv64ima_zicsr_zifencei")
        self.assertEqual(env["RV64_OPENSBI_ABI"], "lp64")
        self.assertEqual(env["RV64_OPENSBI_MMU"], "sv39")
        self.assertEqual(env["RV64_OPENSBI_FW_TEXT_START"], "0x80000000")
        self.assertEqual(env["RV64_OPENSBI_PAYLOAD_ADDR"], "0x80200000")
        self.assertEqual(env["RV64_OPENSBI_PAYLOAD_OFFSET"], "0x200000")
        self.assertEqual(env["RV64_OPENSBI_FDT_ADDR"], "0x87f00000")

        linker = LINKER.read_text()
        self.assertIn(". = 0x80200000;", linker)
        self.assertIn("ASSERT(__payload_end < 0x80300000", linker)

    def test_payload_proves_handoff_and_sbi_round_trip(self) -> None:
        source = PAYLOAD.read_text()
        self.assertIn("mv      s0, a0", source)
        self.assertIn("mv      s1, a1", source)
        self.assertIn("li      t0, 0x87f00000", source)
        self.assertIn("li      a7, 0x10", source)
        self.assertIn("li      a6, 0", source)
        self.assertIn("ecall", source)
        self.assertIn("RV64 OpenSBI S-mode payload PASS", source)
        self.assertIn("RV64 OpenSBI S-mode payload FAIL", source)
        self.assertIn("li      t0, 0x10000000", source)
        self.assertIn("wfi", source)

    def test_toolchain_is_pinned_pie_capable_linux_target(self) -> None:
        ensure = ENSURE.read_text()
        self.assertIn('release="2024.05-1"', ensure)
        self.assertIn(
            'archive_sha256="78e16f3def8b2ff3da09c16155f993ac7e4dc1791d0904ada03fcb2e04910aab"',
            ensure,
        )
        self.assertIn("riscv64-lp64d--glibc--stable-${release}.tar.xz", ensure)
        self.assertIn("find_cross_prefix", ensure)
        self.assertIn("riscv64*-gcc", ensure)
        self.assertIn("riscv64*linux*", ensure)
        self.assertIn('[[ "${fullversion}" == "13.3.0" ]]', ensure)
        self.assertIn("-fPIE -pie", ensure)
        self.assertIn("Type:[[:space:]]*DYN", ensure)

    def test_build_keeps_opensbi_source_unchanged(self) -> None:
        source = BUILD.read_text()
        self.assertIn('fetch --depth=1 origin "${RV64_OPENSBI_COMMIT}"', source)
        self.assertIn('git -C "${source}" status --porcelain --untracked-files=all', source)
        self.assertIn('PLATFORM_RISCV_XLEN="${RV64_OPENSBI_XLEN}"', source)
        self.assertIn('PLATFORM_RISCV_ISA="${RV64_OPENSBI_ISA}"', source)
        self.assertIn('FW_PAYLOAD_PATH="${PAYLOAD_BIN}"', source)
        self.assertIn('FW_PAYLOAD_FDT_ADDR="${RV64_OPENSBI_FDT_ADDR}"', source)
        self.assertIn('CROSS_COMPILE="${AETHERCORE_RV64_LINUX_CROSS_COMPILE:-}"', source)
        self.assertIn("toolchain-pie-probe.elf", source)
        self.assertIn("-fPIE -pie", source)
        self.assertNotIn("riscv-none-elf-", source)
        self.assertNotIn("LLVM=1", source)
        for forbidden in ("git apply", "patch -p", "sed -i", "perl -pi"):
            self.assertNotIn(forbidden, source)

    def test_runtime_reuses_existing_runner_with_real_rv64_top(self) -> None:
        shim = SHIM.read_text()
        self.assertIn('#include "VAetherCoreOpenSbiRV64SimTop.h"', shim)
        self.assertIn(
            "using VAetherCoreOpenSbiSimTop = VAetherCoreOpenSbiRV64SimTop;",
            shim,
        )

        workflow = WORKFLOW.read_text()
        self.assertIn("tools/ensure_riscv64_linux_gcc_13_3.sh", workflow)
        self.assertNotIn("tools/ensure_riscv_none_elf_gcc_15_2.sh", workflow)
        self.assertIn("--top-module AetherCoreOpenSbiRV64SimTop", workflow)
        self.assertIn("sim/opensbi_boot_main.cpp", workflow)
        self.assertIn("sim/rv64_opensbi_shim", workflow)
        self.assertIn("RV64 OpenSBI S-mode payload PASS", workflow)
        self.assertIn("OpenSBI v1.6", workflow)
        self.assertNotIn("Linux 6.6", workflow)
        self.assertNotIn("vmlinux", workflow)


if __name__ == "__main__":
    unittest.main()
