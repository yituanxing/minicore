#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"ERROR: expected exactly one {label} anchor, found {count}")
    return text.replace(old, new, 1)


def adapt(text: str) -> str:
    helper_anchor = (
        "bool isZicsrInstruction(std::uint32_t instruction) {\n"
        "  return (instruction & 0x7fU) == kSystemOpcode && ((instruction >> 12) & 0x7U) != 0;\n"
        "}\n"
    )
    helper = helper_anchor + r'''
Rv32DifftestIsaProfile validateRv32DifftestIsaProfile(Rv32DifftestIsaProfile profile) {
  constexpr std::uint32_t kMxlMask = 0xc0000000U;
  constexpr std::uint32_t kRv32Mxl = 0x40000000U;
  constexpr std::uint32_t kCBit = 1U << 2;
  if ((profile.misa & kMxlMask) != kRv32Mxl) {
    throw std::runtime_error("RV32 timer DiffTest profile must describe RV32 MXL");
  }
  if (profile.ialignBytes != 2 && profile.ialignBytes != 4) {
    throw std::runtime_error("RV32 timer DiffTest IALIGN must be 2 or 4 bytes");
  }
  const bool hasCompressed = (profile.misa & kCBit) != 0;
  if ((hasCompressed && profile.ialignBytes != 2) ||
      (!hasCompressed && profile.ialignBytes != 4)) {
    throw std::runtime_error("RV32 timer DiffTest C bit and IALIGN disagree");
  }
  return profile;
}
'''
    text = replace_once(text, helper_anchor, helper, "RV32 profile helper")

    text = replace_once(
        text,
        "  Impl(const std::string& sharedObject, const std::string& imagePath,\n"
        "       std::uint64_t resetPc, std::uint64_t ramSize)\n"
        "      : resetPc_(checkedAddress(resetPc, \"reset PC\")),\n",
        "  Impl(const std::string& sharedObject, const std::string& imagePath,\n"
        "       std::uint64_t resetPc, std::uint64_t ramSize,\n"
        "       Rv32DifftestIsaProfile profile)\n"
        "      : profile_(validateRv32DifftestIsaProfile(profile)),\n"
        "        resetPc_(checkedAddress(resetPc, \"reset PC\")),\n",
        "timer DiffTest constructor profile",
    )

    text = replace_once(text, "      case kMisa: return kRv32ImMisa;\n",
                        "      case kMisa: return profile_.misa;\n", "misa profile read")
    text = replace_once(
        text,
        "        machine_.mepc = value & ~std::uint32_t{3};\n",
        "        machine_.mepc = alignInstructionAddress(value);\n",
        "mepc CSR write alignment",
    )
    text = replace_once(
        text,
        "    if ((before.pc & 3U) != 0U) {\n"
        "      fail(\"FreeRTOS ECALL trap PC is not 4-byte aligned\");\n"
        "    }\n",
        "    if (!isInstructionAddressAligned(before.pc)) {\n"
        "      fail(\"FreeRTOS ECALL trap PC violates active RV32 IALIGN\");\n"
        "    }\n",
        "ECALL IALIGN validation",
    )
    text = replace_once(
        text,
        "    machine_.mepc = before.pc & ~std::uint32_t{3};\n",
        "    machine_.mepc = alignInstructionAddress(before.pc);\n",
        "ECALL mepc alignment",
    )
    text = replace_once(
        text,
        "    if ((epc & 3U) != 0) fail(\"machine timer interrupt EPC is not 4-byte aligned\");\n"
        "    // This timer reference still models the fail-closed RV32IM profile;\n"
        "    // replay-target alignment becomes C-aware only when C is enabled.\n"
        "    (void)instructionAt(epc, 4);\n",
        "    if (!isInstructionAddressAligned(epc)) {\n"
        "      fail(\"machine timer interrupt EPC violates active RV32 IALIGN\");\n"
        "    }\n"
        "    (void)instructionAt(epc, profile_.ialignBytes);\n",
        "interrupt IALIGN validation",
    )
    text = replace_once(
        text,
        "    machine_.mepc = epc & ~std::uint32_t{3};\n",
        "    machine_.mepc = alignInstructionAddress(epc);\n",
        "interrupt mepc alignment",
    )

    private_anchor = " private:\n  static std::uint32_t checkedAddress(std::uint64_t value, const char* label) {\n"
    private_replacement = r''' private:
  bool isInstructionAddressAligned(std::uint32_t address) const {
    return (address & (static_cast<std::uint32_t>(profile_.ialignBytes) - 1U)) == 0U;
  }

  std::uint32_t alignInstructionAddress(std::uint32_t address) const {
    return address & ~(static_cast<std::uint32_t>(profile_.ialignBytes) - 1U);
  }

  static std::uint32_t checkedAddress(std::uint64_t value, const char* label) {
'''
    text = replace_once(text, private_anchor, private_replacement, "private profile helpers")

    text = replace_once(
        text,
        "  std::uint32_t resetPc_ = 0;\n",
        "  Rv32DifftestIsaProfile profile_ = Rv32DifftestIsaProfile::rv32im();\n"
        "  std::uint32_t resetPc_ = 0;\n",
        "stored RV32 profile",
    )

    ctor_old = (
        "NemuDifftest::NemuDifftest(const std::string& sharedObject, const std::string& imagePath,\n"
        "                           std::uint64_t resetPc, std::uint64_t ramSize)\n"
        "    : impl_(std::make_unique<Impl>(sharedObject, imagePath, resetPc, ramSize)) {}\n"
    )
    ctor_new = (
        "NemuDifftest::NemuDifftest(const std::string& sharedObject, const std::string& imagePath,\n"
        "                           std::uint64_t resetPc, std::uint64_t ramSize)\n"
        "    : NemuDifftest(sharedObject, imagePath, resetPc, ramSize,\n"
        "                   Rv32DifftestIsaProfile::rv32im()) {}\n\n"
        "NemuDifftest::NemuDifftest(const std::string& sharedObject, const std::string& imagePath,\n"
        "                           std::uint64_t resetPc, std::uint64_t ramSize,\n"
        "                           Rv32DifftestIsaProfile profile)\n"
        "    : impl_(std::make_unique<Impl>(sharedObject, imagePath, resetPc, ramSize, profile)) {}\n"
    )
    text = replace_once(text, ctor_old, ctor_new, "public RV32 profile constructor")
    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = adapt(args.source.read_text(encoding="utf-8"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(result, encoding="utf-8")
    print(f"wrote ISA-profile-aware RV32 DiffTest adapter: {args.output}")


if __name__ == "__main__":
    main()
