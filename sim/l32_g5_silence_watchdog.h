#pragma once

#include <algorithm>
#include <cstdint>
#include <ostream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace aethercore::l32sim {

// Observe the architectural PC interrupted by STIP, rather than the last
// retired instructions (which can all belong to the timer interrupt handler).
// This is host-only diagnostic state and never changes the RTL or guest.
class G5SilenceWatchdog {
 public:
  explicit G5SilenceWatchdog(std::uint64_t thresholdCycles)
      : thresholdCycles_(thresholdCycles) {}

  bool enabled() const { return thresholdCycles_ != 0; }
  bool anchored() const { return sawLateInitcall_; }
  std::uint64_t anchorCycle() const { return anchorCycle_; }
  std::uint64_t lastUartCycle() const { return lastUartCycle_; }
  std::uint64_t timerEvents() const { return timerEvents_; }

  void observeUart(std::uint64_t cycle, char byte) {
    if (!enabled()) return;
    lastUartCycle_ = cycle;
    timerPcCounts_.clear();
    timerEvents_ = 0;
    tail_.push_back(byte);
    constexpr char kLateInitcall[] = "clk: Disabling unused clocks";
    constexpr std::size_t kMaxTail = sizeof(kLateInitcall) - 1;
    if (tail_.size() > kMaxTail) tail_.erase(0, tail_.size() - kMaxTail);
    if (!sawLateInitcall_ && tail_.size() == kMaxTail && tail_ == kLateInitcall) {
      sawLateInitcall_ = true;
      anchorCycle_ = cycle;
    }
  }

  void observeTimerInterrupt(std::uint64_t interruptedPc) {
    if (!enabled() || !sawLateInitcall_) return;
    ++timerPcCounts_[interruptedPc];
    ++timerEvents_;
  }

  // A single clock-provider initcall banner is NOT proof of a deadlock. The
  // diagnostic budget is opt-in and only applies after that exact banner.
  bool expired(std::uint64_t cycle) const {
    return enabled() && sawLateInitcall_ && cycle >= lastUartCycle_ &&
        cycle - lastUartCycle_ >= thresholdCycles_;
  }

  void emit(std::ostream& out) const {
    std::vector<std::pair<std::uint64_t, std::uint64_t>> frequencies(
        timerPcCounts_.begin(), timerPcCounts_.end());
    std::sort(frequencies.begin(), frequencies.end(), [](const auto& lhs, const auto& rhs) {
      if (lhs.second != rhs.second) return lhs.second > rhs.second;
      return lhs.first < rhs.first;
    });
    out << " timer-boundary-pc-samples=" << timerEvents_
        << " timer-boundary-pc-distinct=" << frequencies.size()
        << " timer-boundary-pc-top=";
    constexpr std::size_t kMaxReport = 8;
    for (std::size_t i = 0; i < frequencies.size() && i < kMaxReport; ++i) {
      if (i != 0) out << ',';
      out << "0x" << std::hex << frequencies[i].first << ':' << std::dec
          << frequencies[i].second;
    }
    if (frequencies.empty()) out << "none";
  }

 private:
  std::uint64_t thresholdCycles_ = 0;
  bool sawLateInitcall_ = false;
  std::uint64_t anchorCycle_ = 0;
  std::uint64_t lastUartCycle_ = 0;
  std::uint64_t timerEvents_ = 0;
  std::string tail_;
  std::unordered_map<std::uint64_t, std::uint64_t> timerPcCounts_;
};

}  // namespace aethercore::l32sim
