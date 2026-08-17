from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
DTB_TOOL = ROOT / "tools" / "ci" / "make_l32_dtb.py"
BUILD_SCRIPT = ROOT / "tools" / "ci" / "l32_rv32c_kernel_build.sh"
HISTORICAL_PAYLOAD_BUILD = ROOT / "tools" / "ci" / "l32_linux_payload_build.sh"


def load_dtb_module():
    spec = importlib.util.spec_from_file_location("make_l32_dtb", DTB_TOOL)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Rv32CLinuxKernelContractTest(unittest.TestCase):
    def test_historical_dtb_default_remains_rv32ima(self) -> None:
        module = load_dtb_module()
        blob = module.build_l32_dtb()
        self.assertIn(b"rv32ima_zicsr_zifencei_sstc\0", blob)
        self.assertNotIn(b"rv32imac_zicsr_zifencei_sstc\0", blob)

    def test_rv32imac_dtb_is_an_explicit_peer_profile(self) -> None:
        module = load_dtb_module()
        blob = module.build_l32_dtb(isa="rv32imac_zicsr_zifencei_sstc")
        self.assertIn(b"rv32imac_zicsr_zifencei_sstc\0", blob)
        self.assertNotIn(b"rv32ima_zicsr_zifencei_sstc\0", blob)

    def test_non_rv32_dtb_isa_fails_closed(self) -> None:
        module = load_dtb_module()
        with self.assertRaises(ValueError):
            module.build_l32_dtb(isa="rv64imac_zicsr_zifencei")

    def test_kernel_probe_uses_isolated_c_build_namespace(self) -> None:
        text = BUILD_SCRIPT.read_text()
        self.assertIn('PROFILE=rv32imac', text)
        self.assertIn('BUILD_DIR="${ROOT_DIR}/build/l32-linux-${PROFILE}"', text)
        self.assertNotIn('BUILD_DIR="${ROOT_DIR}/build/l32-linux"', text)
        self.assertIn('-e RISCV_ISA_C', text)
        self.assertIn('LINUX_ISA=rv32imac_zicsr_zifencei', text)
        self.assertIn('DTB_ISA=rv32imac_zicsr_zifencei_sstc', text)

    def test_kernel_probe_preserves_frozen_linux_handoff_layout(self) -> None:
        text = BUILD_SCRIPT.read_text()
        historical = HISTORICAL_PAYLOAD_BUILD.read_text()
        bootargs = 'BOOTARGS="earlycon=uart8250,mmio,0x10000000 console=ttyS0,115200"'
        self.assertIn('FW_PAYLOAD_FDT_ADDR=0x87f00000', text)
        self.assertIn('FW_PAYLOAD_FDT_ADDR="0x87f00000"', historical)
        self.assertIn(bootargs, text)
        self.assertIn(bootargs, historical)
        self.assertIn('--bootargs "${BOOTARGS}"', text)
        self.assertIn('FW_PAYLOAD_FDT_ADDR="${FW_PAYLOAD_FDT_ADDR}"', text)
        self.assertIn('grep -Fxq "bootargs=${BOOTARGS}"', text)

    def test_kernel_probe_does_not_rewrite_historical_freeze(self) -> None:
        text = BUILD_SCRIPT.read_text()
        self.assertNotIn('L32_LINUX_VMLINUX_SHA256=', text)
        self.assertNotIn('L32_LINUX_IMAGE_SHA256=', text)
        self.assertNotIn('build/l32-linux"', text)
        self.assertIn('RV32C_LINUX_KERNEL_RESULT: status=PASS', text)

    def test_linked_linux_isa_oracle_uses_elf_semantics(self) -> None:
        text = BUILD_SCRIPT.read_text()
        # Linux's final vmlinux does not preserve Tag_RISCV_arch. The linked
        # image must instead prove C through RVC e_flags plus real instructions.
        self.assertIn('extract_flags()', text)
        self.assertIn('linux_flags="$(extract_flags "${VMLINUX}")"', text)
        self.assertIn('"${linux_flags}" == *RVC*', text)
        self.assertIn('linux_compressed="$(count_compressed "${VMLINUX}")"', text)
        self.assertIn('linux_m_instructions="$(count_mnemonic_family', text)
        self.assertIn('linux_atomic_instructions="$(count_mnemonic_family', text)
        self.assertNotIn('require_c_arch Linux', text)

    def test_opensbi_static_c_evidence_excludes_embedded_linux_payload(self) -> None:
        text = BUILD_SCRIPT.read_text()
        self.assertIn('require_c_arch OpenSBI "${opensbi_arch}"', text)
        self.assertIn(
            'opensbi_compressed="$(count_compressed "${FW_ELF}" "${L32_LINUX_PHYS_ENTRY}")"',
            text,
        )
        self.assertIn('firmware-owned 16-bit instruction', text)


if __name__ == "__main__":
    unittest.main()
