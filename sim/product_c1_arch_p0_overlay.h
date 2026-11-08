#pragma once

/* Host-only, deliberately isolated Product C1 P0 architectural boot overlay.
 *
 * This overlays *responses*, never the 256-MiB source RAM.  The M-mode ROM
 * reaches the shim at 0x80000000; its table loads must see the same overlay
 * that instruction fetch sees.  Disable it only after the shim's MRET retires.
 *
 * Not a Verilator checkpoint, a QEMU savevm reader, or a complete Linux
 * platform-state importer.  Do not use this shim with the official SD1 top.
 */

#include "l32_opensbi_runtime.h"

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <utility>
#include <vector>

namespace aethercore::c1p0 {

constexpr std::uint64_t kRestoreBase = 0x80000000ULL;
constexpr std::uint64_t kRestoreLimit = 0x80100000ULL;
constexpr std::uint64_t kGuestMinimumPc = kRestoreLimit;
constexpr std::size_t kStateTableBytes = 424;

class BootOverlay {
 public:
  BootOverlay(const l32sim::Memory& memory, std::vector<std::uint8_t> image,
              std::vector<std::uint8_t> table, std::size_t stateOffset)
      : memory_(memory), image_(std::move(image)) {
    if (image_.empty() || image_.size() > kRestoreLimit - kRestoreBase)
      throw std::runtime_error("P0 restore image must fit within isolated first MiB");
    if (table.size() != kStateTableBytes)
      throw std::runtime_error("P0 state table must be exactly 424 bytes");
    if (stateOffset > image_.size() ||
        table.size() > image_.size() - stateOffset)
      throw std::runtime_error("P0 state table placeholder exceeds restore image");
    for (std::size_t i = 0; i < table.size(); ++i)
      image_[stateOffset + i] = table[i];
  }

  bool active() const { return active_; }
  void retireMret() { active_ = false; }

  bool contains(std::uint64_t address, std::size_t bytes) const {
    return memory_.contains(address, bytes);
  }

  bool touchesImage(std::uint64_t address, std::size_t bytes) const {
    if (!active_ || bytes == 0 || address >= kRestoreBase + image_.size())
      return false;
    // The first source byte may lie below the overlay, but the request may
    // still straddle it. Avoid address+bytes overflow in this predicate.
    return address >= kRestoreBase ||
        (address < kRestoreBase && bytes > kRestoreBase - address);
  }

  std::uint64_t readData(std::uint64_t address, std::size_t bytes) const {
    if (!contains(address, bytes))
      throw std::runtime_error("P0 read outside 256-MiB Product DDR");
    if (!touchesImage(address, bytes)) return memory_.readData(address, bytes);
    if (address < kRestoreBase || address - kRestoreBase > image_.size() ||
        bytes > image_.size() - static_cast<std::size_t>(address - kRestoreBase))
      throw std::runtime_error("P0 read straddles boot overlay boundary");
    std::uint64_t value = 0;
    const auto offset = static_cast<std::size_t>(address - kRestoreBase);
    for (std::size_t i = 0; i < bytes; ++i)
      value |= std::uint64_t(image_[offset + i]) << (8 * i);
    return value;
  }

  std::uint32_t readInstruction(std::uint64_t address, std::size_t bytes) const {
    if (bytes != 2 && bytes != 4)
      throw std::runtime_error("P0 invalid instruction fetch width");
    return static_cast<std::uint32_t>(readData(address, bytes));
  }

  void rejectWrite(std::uint64_t address, std::size_t bytes) const {
    // The entire 1-MiB scratch aperture is read-only until MRET: a malformed
    // shim must never mutate the source DDR behind the host's back.
    if (active_ && address >= kRestoreBase &&
        address < kRestoreLimit && bytes != 0)
      throw std::runtime_error("P0 wrote into protected restore aperture");
    if (active_)
      throw std::runtime_error("P0 pre-resume memory write is forbidden");
  }

 private:
  const l32sim::Memory& memory_;
  std::vector<std::uint8_t> image_;
  bool active_ = true;
};

}  // namespace aethercore::c1p0
