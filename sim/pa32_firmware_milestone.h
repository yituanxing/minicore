#pragma once
// Shared, bounded UART and interrupt observation for the two PA32 simulator
// engines. Keeping this outside either engine makes Linux signoff symmetric.
#include <cstdint>
#include <string>
#include <string_view>

namespace aethercore::pa32sim {
class FirmwareMilestone {
 public:
  static constexpr std::string_view kLinuxPid1 = "RV64 USER UART IRQ OK";

  void observeUart(bool valid, std::uint8_t byte, std::uint64_t guestCycle) {
    if (!valid) return;
    ++uartBytes;
    uartDigest ^= byte;
    uartDigest *= 1099511628211ULL;
    tail_.push_back(static_cast<char>(byte));
    if (tail_.size() > 64) tail_.erase(0, tail_.size() - 64);
    if (!opensbiBanner && contains("OpenSBI v1.6")) opensbiBanner = true;
    if (!linuxBanner && contains("Linux version 6.6.143")) linuxBanner = true;
    if (!milestoneCycle && contains(kLinuxPid1)) milestoneCycle = guestCycle;
  }

  void observeInterrupt(bool valid, std::uint64_t cause) {
    if (!valid) return;
    if (cause == (0x80000000ULL | 5ULL) ||
        cause == (0x8000000000000000ULL | 5ULL)) ++stip;
    if (cause == (0x80000000ULL | 9ULL) ||
        cause == (0x8000000000000000ULL | 9ULL)) ++seip;
  }

  bool linuxPassed() const {
    return opensbiBanner && linuxBanner && milestoneCycle != 0 &&
           stip != 0 && seip != 0;
  }

  std::uint64_t uartBytes = 0;
  std::uint64_t uartDigest = 14695981039346656037ULL;
  std::uint64_t milestoneCycle = 0;
  std::uint64_t stip = 0;
  std::uint64_t seip = 0;
  bool opensbiBanner = false;
  bool linuxBanner = false;

 private:
  bool contains(std::string_view value) const {
    return tail_.find(value) != std::string::npos;
  }
  std::string tail_;
};
}  // namespace aethercore::pa32sim
