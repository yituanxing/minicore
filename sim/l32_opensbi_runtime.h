#pragma once

#include "verilated.h"

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace aethercore::l32sim {

constexpr std::uint64_t kRamBase = 0x80000000ULL;
constexpr std::size_t kRamSize = 256ULL * 1024ULL * 1024ULL;
constexpr std::uint32_t kEbreak = 0x00100073U;

// Chisel AtomicOp declaration order. Keep this transport-level map explicit so
// the host never re-decodes RISC-V instruction bits.
constexpr std::uint32_t kAtomicNone = 0;
constexpr std::uint32_t kAtomicLr = 1;
constexpr std::uint32_t kAtomicSc = 2;
constexpr std::uint32_t kAtomicSwap = 3;
constexpr std::uint32_t kAtomicAdd = 4;
constexpr std::uint32_t kAtomicXor = 5;
constexpr std::uint32_t kAtomicAnd = 6;
constexpr std::uint32_t kAtomicOr = 7;
constexpr std::uint32_t kAtomicMin = 8;
constexpr std::uint32_t kAtomicMax = 9;
constexpr std::uint32_t kAtomicMinu = 10;
constexpr std::uint32_t kAtomicMaxu = 11;

/**
 * Shared byte-addressed RAM for the OpenSBI/Linux simulation boundary.
 *
 * This owns only simulator transport semantics. Runtime milestones, commit
 * accounting, interrupt qualification and forkserver policy remain in their
 * scenario-specific runners. The historical l32sim namespace is retained as
 * a compatibility surface while the transport itself is XLEN-neutral.
 *
 * V2 may additionally expose AetherMem atomic metadata. In that case this RAM
 * is the final LR/SC reservation and AMO read-modify-write owner. V1 tops do
 * not expose those ports and continue through the byte-identical ordinary path.
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
    clearReservation();
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
#ifdef AETHERCORE_SIM_FIXED_WIDTH_RAM
    // Fixed-size memcpy lets GCC/Clang lower the common 2/4/8-byte simulator
    // reads to native unaligned loads while preserving aliasing correctness.
    // The byte-loop remains the default qualified path outside speed builds.
    const auto* src = bytes_.data() + offset;
    switch (size) {
      case 1:
        return src[0];
      case 2: {
        std::uint16_t value;
        std::memcpy(&value, src, sizeof(value));
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
        value = __builtin_bswap16(value);
#endif
        return value;
      }
      case 4: {
        std::uint32_t value;
        std::memcpy(&value, src, sizeof(value));
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
        value = __builtin_bswap32(value);
#endif
        return value;
      }
      case 8: {
        std::uint64_t value;
        std::memcpy(&value, src, sizeof(value));
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
        value = __builtin_bswap64(value);
#endif
        return value;
      }
      default:
        __builtin_unreachable();
    }
#else
    std::uint64_t value = 0;
    for (std::size_t i = 0; i < size; ++i)
      value |= std::uint64_t(bytes_[offset + i]) << (8 * i);
    return value;
#endif
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

  void clearReservation() {
    reservationValid_ = false;
    reservationAddress_ = 0;
    reservationSize_ = 0;
  }

  bool reservationMatches(std::uint64_t address, std::size_t size) const {
    return reservationValid_ && reservationAddress_ == address && reservationSize_ == size;
  }

  /** Combinational response value for one accepted-looking AetherMem atomic. */
  std::uint64_t atomicResponse(std::uint64_t address, std::size_t size,
                               std::uint32_t atomicOp) const {
    if (size != 4 && size != 8)
      throw std::runtime_error("AetherMem atomic width must be Word or DWord");
    if (atomicOp == kAtomicSc)
      return reservationMatches(address, size) ? 0 : 1;
    if (atomicOp == kAtomicLr ||
        (atomicOp >= kAtomicSwap && atomicOp <= kAtomicMaxu))
      return readData(address, size);
    throw std::runtime_error("unsupported AetherMem atomic operation");
  }

  /** Commit the memory-side consequence exactly once for an accepted request. */
  void commitAtomic(std::uint64_t address, std::size_t size,
                    std::uint64_t operand, std::uint64_t mask,
                    std::uint32_t atomicOp) {
    if (size != 4 && size != 8)
      throw std::runtime_error("AetherMem atomic width must be Word or DWord");

    if (atomicOp == kAtomicLr) {
      reservationValid_ = true;
      reservationAddress_ = address;
      reservationSize_ = size;
      return;
    }

    if (atomicOp == kAtomicSc) {
      const bool success = reservationMatches(address, size);
      clearReservation();
      if (success) writeMasked(address, operand, mask, size);
      return;
    }

    const std::uint64_t oldValue = readData(address, size);
    const std::uint64_t widthMask =
        size == 8 ? ~std::uint64_t{0} : std::uint64_t{0xffffffffULL};
    const std::uint64_t oldMasked = oldValue & widthMask;
    const std::uint64_t operandMasked = operand & widthMask;
    std::uint64_t newValue = operandMasked;

    switch (atomicOp) {
      case kAtomicSwap: newValue = operandMasked; break;
      case kAtomicAdd: newValue = (oldMasked + operandMasked) & widthMask; break;
      case kAtomicXor: newValue = oldMasked ^ operandMasked; break;
      case kAtomicAnd: newValue = oldMasked & operandMasked; break;
      case kAtomicOr: newValue = oldMasked | operandMasked; break;
      case kAtomicMin:
        if (size == 4) {
          const auto lhs = static_cast<std::int32_t>(static_cast<std::uint32_t>(oldMasked));
          const auto rhs = static_cast<std::int32_t>(static_cast<std::uint32_t>(operandMasked));
          newValue = static_cast<std::uint32_t>(lhs < rhs ? lhs : rhs);
        } else {
          const auto lhs = static_cast<std::int64_t>(oldMasked);
          const auto rhs = static_cast<std::int64_t>(operandMasked);
          newValue = static_cast<std::uint64_t>(lhs < rhs ? lhs : rhs);
        }
        break;
      case kAtomicMax:
        if (size == 4) {
          const auto lhs = static_cast<std::int32_t>(static_cast<std::uint32_t>(oldMasked));
          const auto rhs = static_cast<std::int32_t>(static_cast<std::uint32_t>(operandMasked));
          newValue = static_cast<std::uint32_t>(lhs > rhs ? lhs : rhs);
        } else {
          const auto lhs = static_cast<std::int64_t>(oldMasked);
          const auto rhs = static_cast<std::int64_t>(operandMasked);
          newValue = static_cast<std::uint64_t>(lhs > rhs ? lhs : rhs);
        }
        break;
      case kAtomicMinu: newValue = oldMasked < operandMasked ? oldMasked : operandMasked; break;
      case kAtomicMaxu: newValue = oldMasked > operandMasked ? oldMasked : operandMasked; break;
      default: throw std::runtime_error("unsupported AetherMem AMO operation");
    }

    writeMasked(address, newValue, mask, size);
    clearReservation();
  }

 private:
  std::size_t checkedOffset(std::uint64_t address, std::size_t size) const {
    if (!contains(address, size)) throw std::runtime_error("memory access outside RAM");
    return static_cast<std::size_t>(address - kRamBase);
  }

  std::vector<std::uint8_t> bytes_;
  bool reservationValid_ = false;
  std::uint64_t reservationAddress_ = 0;
  std::size_t reservationSize_ = 0;
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

  bool atomic = false;
  std::uint32_t atomicOp = kAtomicNone;
  if constexpr (requires { top.io_memAtomic; top.io_memAtomicOp; }) {
    atomic = static_cast<bool>(top.io_memAtomic);
    atomicOp = static_cast<std::uint32_t>(top.io_memAtomicOp);
  }

  top.io_memRdata = (!dvalid || dfault)
      ? 0
      : (atomic ? memory.atomicResponse(daddr, dbytes, atomicOp)
                : memory.readData(daddr, dbytes));

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
 * store/atomic into host RAM, then evaluate the rising edge and advance
 * Verilator time. The caller owns architectural observation after the edge.
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

  const bool acceptedMemory = !top.reset && top.io_memValid && top.io_memReady &&
      !top.io_memFault;
  bool atomic = false;
  std::uint32_t atomicOp = kAtomicNone;
  if constexpr (requires { top.io_memAtomic; top.io_memAtomicOp; }) {
    atomic = static_cast<bool>(top.io_memAtomic);
    atomicOp = static_cast<std::uint32_t>(top.io_memAtomicOp);
  }

  if (acceptedMemory && atomic) {
    const auto dbytes = dataBytesFromMemSize(
        static_cast<std::uint32_t>(top.io_memSize));
    memory.commitAtomic(
        static_cast<std::uint64_t>(top.io_memAddr),
        dbytes,
        static_cast<std::uint64_t>(top.io_memWdata),
        static_cast<std::uint64_t>(top.io_memWmask),
        atomicOp);
  } else if (acceptedMemory && top.io_memWrite) {
    const auto dbytes = dataBytesFromMemSize(
        static_cast<std::uint32_t>(top.io_memSize));
    memory.writeMasked(
        static_cast<std::uint64_t>(top.io_memAddr),
        static_cast<std::uint64_t>(top.io_memWdata),
        static_cast<std::uint64_t>(top.io_memWmask),
        dbytes);
    // Conservative single-hart rule: any accepted ordinary store invalidates
    // the host-side reservation. This never weakens SC correctness.
    memory.clearReservation();
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
