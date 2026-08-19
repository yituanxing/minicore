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
 * Shared byte-addressed RAM for the OpenSBI/Linux simulation boundary.
 *
 * This owns only simulator transport semantics. Runtime milestones, commit
 * accounting, interrupt qualification and forkserver policy remain in their
 * scenario-specific runners. The historical l32sim namespace is retained as
 * a compatibility surface while the transport itself is XLEN-neutral.
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
    return static_cast<std::uint32_t>(readData(address, size));
  }

  std::uint64_t readData(std::uint64_t address, std::size_t size) const {
    if (size != 1 && size != 2 && size != 4 && size != 8)
      throw std::runtime_error("data transaction must be 1, 2, 4 or 8 bytes");
    const auto offset = checkedOffset(address, size);
    std::uint64_t value = 0;
    for (std::size_t i = 0; i < size; ++i)
      value |= std::uint64_t(bytes_[offset + i]) << (8 * i);
    return value;
  }

  std::uint32_t read32(std::uint64_t address) const {
    return static_cast<std::uint32_t>(readData(address, 4));
  }

  void writeMasked(std::uint64_t address, std::uint64_t data,
                   std::uint64_t mask, std::size_t size) {
    if (size != 1 && size != 2 && size != 4 && size != 8)
      throw std::runtime_error("store transaction must be 1, 2, 4 or 8 bytes");
    const std::uint64_t validMask =
        size == 8 ? 0xffULL : ((std::uint64_t{1} << size) - 1ULL);
    if ((mask & ~validMask) != 0)
      throw std::runtime_error("store byte mask exceeds architectural access width");
    const auto offset = checkedOffset(address, size);
    for (std::size_t i = 0; i < size; ++i) {
      if ((mask >> i) & 1ULL)
        bytes_[offset + i] = static_cast<std::uint8_t>(data >> (8 * i));
    }
  }

  void write32Masked(std::uint64_t address, std::uint32_t data, std::uint8_t mask) {
    writeMasked(address, data, mask, 4);
  }

 private:
  std::size_t checkedOffset(std::uint64_t address, std::size_t size) const {
    if (!contains(address, size)) throw std::runtime_error("memory access outside RAM");
    return static_cast<std::size_t>(address - kRamBase);
  }

  std::vector<std::uint8_t> bytes_;
};

/** Chisel MemSize encodes Byte/Half/Word/DWord in declaration order. */
inline std::size_t dataBytesFromMemSize(std::uint32_t encoded) {
  switch (encoded) {
    case 0: return 1;
    case 1: return 2;
    case 2: return 4;
    case 3: return 8;
    default: throw std::runtime_error("unknown architectural memory-size encoding");
  }
}

/** Drive the shared RV32/RV64 physical-memory boundary for one low phase. */
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
  const auto dbytes = dataBytesFromMemSize(
      static_cast<std::uint32_t>(top.io_memSize));
  const bool dfault = dvalid && !memory.contains(daddr, dbytes);
  top.io_memReady = true;
  top.io_memFault = dfault;
  top.io_memRdata =
      (!dvalid || dfault) ? 0 : memory.readData(daddr, dbytes);

  const bool ptwValid = top.io_ptwValid;
  const auto ptwAddr = static_cast<std::uint64_t>(top.io_ptwAddr);
  constexpr std::size_t ptwBytes = sizeof(top.io_ptwRdata);
  static_assert(ptwBytes == 4 || ptwBytes == 8,
                "page-table response port must carry a 4- or 8-byte PTE");
  const bool ptwFault = ptwValid && !memory.contains(ptwAddr, ptwBytes);
  top.io_ptwReady = true;
  top.io_ptwFault = ptwFault;
  top.io_ptwRdata =
      (!ptwValid || ptwFault) ? 0 : memory.readData(ptwAddr, ptwBytes);
}

/**
 * Execute one complete simulator cycle while preserving the qualified runtime
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
    const auto dbytes = dataBytesFromMemSize(
        static_cast<std::uint32_t>(top.io_memSize));
    memory.writeMasked(
        static_cast<std::uint64_t>(top.io_memAddr),
        static_cast<std::uint64_t>(top.io_memWdata),
        static_cast<std::uint64_t>(top.io_memWmask),
        dbytes);
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
