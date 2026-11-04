"""G5 must execute genuine Debian riscv64 dynamically linked userspace.

Source checks prevent the Product Debian lane from accidentally reusing the
qualified but deliberately no-C/no-FPU G4 kernel or static assembly PID1.
Actual Chisel/Verilator/Linux correctness is only qualified by remote G5 CI.
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
TOP = ROOT / "src/main/scala/aethercore/sim/AetherCoreProductUnifiedMemoryCompatSimTop.scala"
G4 = ROOT / ".github/workflows/product-g4-linux-pid1.yml"
G5 = ROOT / ".github/workflows/product-g5-debian-rv64gc.yml"
ROOTFS = ROOT / "tools/ci/rv64_debian_rootfs_build.sh"
KERNEL = ROOT / "tools/ci/rv64_debian_linux_build.sh"
PAYLOAD = ROOT / "tools/ci/rv64_debian_payload_build.sh"
COMMON_PAYLOAD = ROOT / "tools/ci/rv64_minimal_init_payload_build.sh"
INIT = ROOT / "software/rv64_userspace/debian_dynamic_init.sh"
SHIM = ROOT / "sim/product_rv64_opensbi_shim/VAetherCoreOpenSbiSimTop.h"


class ProductDebianDistroContracts(unittest.TestCase):
    def test_debian_uses_exact_same_product_soc_with_gc_target_override(self):
        source = TOP.read_text()
        for fragment in (
            "val debianTarget: Boolean = false",
            "AetherCoreProductSoCProfiles.smallOpenSbiDistroTarget(PA)",
            "new AetherCoreProductUnifiedMemorySoC(",
            "productConfigOverride = requestedTarget",
            "class AetherCoreProductDebianRV64SimTop",
            "extends AetherCoreProductUnifiedMemoryCompatSimTop(debianTarget = true)",
            "object ElaborateProductDebianRV64SimTop",
        ):
            self.assertIn(fragment, source)
        self.assertIn("AETHERCORE_PRODUCT_DEBIAN", SHIM.read_text())

    def test_debian_is_official_riscv64_dynamic_lp64d_not_static_assembly(self):
        rootfs = ROOTFS.read_text()
        for fragment in (
            "debian:trixie-20261027-slim",
            "docker pull --platform linux/riscv64",
            "docker export",
            "actual_arch", "riscv64", "double-float ABI",
            "/lib/ld-linux-riscv64-lp64d.so.1", "debian-sh-readelf.txt",
            "RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS",
        ):
            self.assertIn(fragment, rootfs)
        init = INIT.read_text()
        self.assertIn("#!/bin/sh", init)
        self.assertIn("/usr/bin/dpkg --print-architecture", init)
        self.assertIn("DEBIAN_RISCV64_GLIBC_PID1_OK", init)
        self.assertNotIn("minimal_init.S", init)

    def test_distro_kernel_c_and_fpu_are_distinct_from_legacy_no_fp(self):
        kernel = KERNEL.read_text()
        for fragment in (
            "-e RISCV_ISA_C -e FPU", "-e BINFMT_ELF -e BINFMT_SCRIPT",
            "-e BLK_DEV_INITRD", "CONFIG_FPU=y", "CONFIG_RISCV_ISA_C=y",
            "RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS",
        ):
            self.assertIn(fragment, kernel)
        baseline = G4.read_text()
        self.assertIn("rv64_minimal_initramfs_build.sh", baseline)
        self.assertNotIn("rv64_debian_linux_build.sh", baseline)

    def test_one_firmware_build_path_and_explicit_gc_dtb(self):
        payload = PAYLOAD.read_text()
        for fragment in (
            "rv64imafdc_zicsr_zifencei", "RV64_PAYLOAD_ABI='lp64'",
            "rv64_minimal_init_payload_build.sh",
            "RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS",
        ):
            self.assertIn(fragment, payload)
        common = COMMON_PAYLOAD.read_text()
        self.assertIn("RV64_PAYLOAD_ISA", common)
        self.assertIn("--isa \"${PAYLOAD_ISA}\"", common)
        self.assertIn("firmware_bytes", common)
        self.assertIn("RV64_MINIMAL_INIT_PAYLOAD_BUILD_RESULT", common)

    def test_manual_and_initial_pr_gate_is_real_boot_not_proven_by_image(self):
        workflow = G5.read_text()
        for fragment in (
            "workflow_dispatch:",
            "AetherCoreProductDebianRV64SimTop",
            "ElaborateProductDebianRV64SimTop",
            "DEBIAN_QEMU_LP64D_ORACLE_PASS",
            "MAX_CYCLES=1800000000",
            "REQUIRE_LAYERED_COMPRESSED=1",
            "MILESTONE='DEBIAN_RISCV64_GLIBC_PID1_OK'",
            "grep -q '^L32_RUNTIME_MILESTONE_PASS '",
            "PRODUCT_G5_DEBIAN_DYNAMIC_PID1_PASS",
        ):
            self.assertIn(fragment, workflow)
        self.assertNotIn("product-g4-linux-pid1.yml'", workflow)
        runtime = (ROOT / "sim/opensbi_boot_main.cpp").read_text()
        self.assertIn("#ifdef AETHERCORE_PRODUCT_DEBIAN", runtime)
        self.assertIn("kLinuxPayloadBase = 0x80200000ULL", runtime)
        self.assertIn("kLinuxPayloadBase = 0x80400000ULL", runtime)


if __name__ == "__main__":
    unittest.main()
