"""Guard the diagnostic G5-D2-kernel / tiny-rootfs comparison boundary.

The real Image/RTL proof belongs to a separately opted-in long-running CI.
These fast tests prevent the diagnostic from silently becoming a G4 kernel
test or being mistaken for upstream Debian userspace qualification.
"""

from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
BUILDER = ROOT / "tools/ci/product_g5_tiny_control_build.sh"
WORKFLOW = ROOT / ".github/workflows/product-g5-tiny-control.yml"


class ProductG5TinyControlContract(unittest.TestCase):
    def test_builder_uses_g5_d2_objects_and_just_changes_cpio_source(self):
        source = BUILDER.read_text()
        self.assertIn('FULL="$ROOT/build/rv64-debian-linux"', source)
        self.assertIn('[[ "$(field "$FULL/result.txt" stage)" == d2', source)
        self.assertIn('cp -a --reflink=auto "$FULL/obj" "$OBJ"', source)
        self.assertIn('set-str INITRAMFS_SOURCE "$SPEC"', source)
        self.assertIn('sed \'/^CONFIG_INITRAMFS_SOURCE=/d\'', source)
        self.assertIn('cmp "$EVIDENCE/d2-config-normalized.txt"', source)
        self.assertNotIn('rv64_minimal_initramfs_build.sh', source)

    def test_tiny_pid1_but_same_product_platform_and_bootargs(self):
        source = BUILDER.read_text()
        self.assertIn('software/rv64_userspace/minimal_init.S', source)
        self.assertIn('RV64_PAYLOAD_ISA=\'rv64imafdc_zicsr_zifencei\'', source)
        self.assertIn('RV64_PAYLOAD_PRODUCT_BOARD=1', source)
        self.assertIn('RV64_PAYLOAD_BOOTARGS="$(field "$FULL_BOOT/result.txt" bootargs)"', source)
        self.assertIn('cmp "$FULL_BOOT/aethercore-rv64-initramfs.dtb"', source)
        self.assertIn('Debian_PID1_qualified=0', source)

    def test_workflow_uses_real_g5_rtl_and_is_explicit_only(self):
        content = WORKFLOW.read_text()
        self.assertIn("types: [synchronize]", content)
        self.assertIn("'[g5-tiny-run]'", content)
        self.assertIn("workflow_dispatch:", content)
        self.assertIn("if: steps.selected.outputs.run == '1'", content)
        self.assertIn("TOP=AetherCoreProductDebianRV64SimTop", content)
        self.assertIn("ELABORATE_MAIN=aethercore.sim.ElaborateProductDebianRV64SimTop", content)
        self.assertIn("SIM_SETTLE_MODE=reference", content)
        self.assertIn("VERILATOR_VERSION: '5.054'", content)
        self.assertIn("MILESTONE='RV64 USER UART IRQ OK'", content)
        self.assertIn("not Debian userland acceptance", content)
        self.assertNotIn("DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK", content)

    def test_reuses_existing_g5_toolchain_and_model_cache(self):
        content = WORKFLOW.read_text()
        g5 = (ROOT / ".github/workflows/product-g5-debian-rv64gc.yml").read_text()
        for suffix in (
            "verilator-5.024",
            "src/verilator-522bead374d6b7b2adb316304126e5361b18bcf1",
            "build/verilator-522bead374d6b7b2adb316304126e5361b18bcf1",
            "verilator-5.054",
            "src/verilator-bbb60a403932a9f695cb6ff001ceb73de6ad9234",
            "build/verilator-bbb60a403932a9f695cb6ff001ceb73de6ad9234",
        ):
            path = "~/.cache/aethercore/toolchains/" + suffix
            self.assertIn(path, g5)
            self.assertIn(path, content)
        self.assertIn("$RUNNER_TEMP/aethercore-product-g5-debian/model", content)
        self.assertIn("${{ runner.temp }}/aethercore-product-g5-debian/model", content)
        self.assertNotIn("aethercore-g5-tiny/model", content)

    def test_builder_is_valid_shell(self):
        subprocess.run(["bash", "-n", str(BUILDER)], check=True)


if __name__ == "__main__":
    unittest.main()
