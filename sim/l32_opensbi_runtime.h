#pragma once

#include "verilated.h"

#include <cstddef>
#include <cstdint>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace aethercore::l32sim {

constexpr std::uint64_t kRamBase = 0x80000000ULL;
constexpr std::size_t kRamSize = 256ULL * 1024ULL * 1024ULL;
constexpr std::uint32_t kEbreak = 0x00100073U;

/**
 * Shared byte-addressed RAM for the L32 OpenSBI/Linux simulation boundary.
 *
 * This owns only simulator transport semantics. Runtime milestones, commit
 * accounting, interrupt qualification and forkserver policy remain in their
 * scenario-specific runners.
 */
class Memory {
 public:
  Memory() : bytes_(kRamSize, 0) {}

  void loadAtBase(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error("cannot open image: " + path);
    input.read(reinterpret_cast<char*>(bytes_.data()),
               static_cast<std::streamsize>(bytes_.size()));
    if (input.bad()) throw std::runtime_error("failed while reading image: " + path);
  }

  bool contains(std::uint64_t address, std::size_t size = 1) const {
    return size <= bytes_.size() && address >= kRamBase &&
           address - kRamBase <= bytes_.size() - size;
  }

  std::uint32_t readInstruction(std::uint64_t address, std::size_t size) const {
    if (size != 2 && size != 4)
      throw std::runtime_error("instruction transaction must be 2 or 4 bytes");
    const auto offset = checkedOffset(address, size);
    std::uint32_t value = 0;
    for (std::size_t i = 0; i < size; ++i)
      value |= std::uint32_t(bytes_[offset + i]) << (8 * i);
    return value;
  }

  std::uint32_t read32(std::uint64_t address) const {
    const auto offset = checkedOffset(address, 4);
    return std::uint32_t(bytes_[offset]) |
           (std::uint32_t(bytes_[offset + 1]) << 8) |
           (std::uint32_t(bytes_[offset + 2]) << 16) |
           (std::uint32_t(bytes_[offset + 3]) << 24);
  }

  void write32Masked(std::uint64_t address, std::uint32_t data, std::uint8_t mask) {
    const auto offset = checkedOffset(address, 4);
    for (unsigned i = 0; i < 4; ++i) {
      if ((mask >> i) & 1U)
        bytes_[offset + i] = static_cast<std::uint8_t>(data >> (8 * i));
    }
  }

 private:
  std::size_t checkedOffset(std::uint64_t address, std::size_t size) const {
    if (!contains(address, size)) throw std::runtime_error("memory access outside RAM");
    return static_cast<std::size_t>(address - kRamBase);
  }

  std::vector<std::uint8_t> bytes_;
};

/** Drive the complete current L32 physical-memory boundary for one low phase. */
template <typename Top>
void driveMemory(Top& top, const Memory& memory) {
  const bool ivalid = top.io_imemValid;
  const auto iaddr = static_cast<std::uint64_t>(top.io_imemAddr);
  const auto ibytes = static_cast<std::size_t>(top.io_imemBytes);
  const bool invalidInstructionWidth = ibytes != 2 && ibytes != 4;
  const bool ifault = ivalid &&
      (invalidInstructionWidth || !memory.contains(iaddr, ibytes));
  top.io_imemFault = ifault;
  top.io_imemInst =
      (!ivalid || ifault) ? 0 : memory.readInstruction(iaddr, ibytes);

  const bool dvalid = top.io_memValid;
  const auto daddr = static_cast<std::uint64_t>(top.io_memAddr);
  const bool dfault = dvalid && !memory.contains(daddr, 4);
  top.io_memReady = true;
  top.io_memFault = dfault;
  top.io_memRdata = (!dvalid || dfault) ? 0 : memory.read32(daddr);

  const bool ptwValid = top.io_ptwValid;
  const auto ptwAddr = static_cast<std::uint64_t>(top.io_ptwAddr);
  const bool ptwFault = ptwValid && !memory.contains(ptwAddr, 4);
  top.io_ptwReady = true;
  top.io_ptwFault = ptwFault;
  top.io_ptwRdata = (!ptwValid || ptwFault) ? 0 : memory.read32(ptwAddr);
}

/**
 * Execute one complete simulator cycle while preserving the qualified L32
 * ordering: drive/evaluate twice at clock-low, commit an accepted physical
 * store into host RAM, then evaluate the rising edge and advance Verilator
 * time. The caller owns architectural observation after the rising edge.
 */
template <typename Top>
bool step(Top& top, VerilatedContext& context, Memory& memory,
          bool rxValid, std::uint8_t rxByte) {
  top.clock = 0;
  top.io_rxValid = rxValid;
  top.io_rxByte = rxValid ? rxByte : 0;
  driveMemory(top, memory);
  top.eval();
  driveMemory(top, memory);
  top.eval();
  const bool rxAccepted = top.io_rxValid && top.io_rxReady;

  if (!top.reset && top.io_memValid && top.io_memWrite && top.io_memReady &&
      !top.io_memFault) {
    memory.write32Masked(
        static_cast<std::uint64_t>(top.io_memAddr),
        static_cast<std::uint32_t>(top.io_memWdata),
        static_cast<std::uint8_t>(top.io_memWmask));
  }

  top.clock = 1;
  top.eval();
  context.timeInc(1);
  return rxAccepted;
}

template <typename Top>
void initialize(Top& top, Memory& memory) {
  top.reset = 1;
  top.clock = 0;
  top.io_rxValid = 0;
  top.io_rxByte = 0;
  driveMemory(top, memory);
  top.eval();
}

}  // namespace aethercore::l32sim
