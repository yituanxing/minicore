#pragma once
// Opt-in host-side phase sampling for the GSIM PA32 runner.
// No guest registers, RAM, timing, or simulator inputs are modified.
// A prime stride avoids sampling only a single power-of-two timer phase.
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>

namespace aethercore::pa32sim {
class GsimPhaseProfile {
 public:
  using Clock = std::chrono::steady_clock;
  using TimePoint = Clock::time_point;
  static constexpr std::uint64_t kStride = 4093;

  GsimPhaseProfile() {
    const char* flag = std::getenv("AETHERCORE_GSIM_PHASE_PROFILE");
    if (!flag || std::strcmp(flag, "0") == 0) return;
    if (std::strcmp(flag, "1") != 0)
      throw std::invalid_argument("AETHERCORE_GSIM_PHASE_PROFILE must be 0 or 1");
    enabled_ = true;
  }

  bool sample(std::uint64_t cycle, bool reset) const {
    return enabled_ && !reset && cycle % kStride == 0;
  }

  void record(TimePoint beforeRequest, TimePoint beforeModel,
              TimePoint afterModel, TimePoint afterObserve) {
    ++samples_;
    requestNs_ += elapsed(beforeRequest, beforeModel);
    modelNs_ += elapsed(beforeModel, afterModel);
    observeNs_ += elapsed(afterModel, afterObserve);
  }

  void report(std::uint64_t guestCycles) const {
    if (!enabled_) return;
    const std::uint64_t total = requestNs_ + modelNs_ + observeNs_;
    const double requestPct = total ? 100.0 * requestNs_ / total : 0.0;
    const double modelPct = total ? 100.0 * modelNs_ / total : 0.0;
    const double observePct = total ? 100.0 * observeNs_ / total : 0.0;
    std::printf(
        "AETHERCORE_PA32_GSIM_PHASE_PROFILE guest_cycles=%llu "
        "sample_stride=%llu samples=%llu "
        "request_ns=%llu model_ns=%llu observe_ns=%llu "
        "request_pct=%.2f model_pct=%.2f observe_pct=%.2f "
        "model_includes_host_bridge=1 excludes_ram_digest=1\n",
        static_cast<unsigned long long>(guestCycles),
        static_cast<unsigned long long>(kStride),
        static_cast<unsigned long long>(samples_),
        static_cast<unsigned long long>(requestNs_),
        static_cast<unsigned long long>(modelNs_),
        static_cast<unsigned long long>(observeNs_),
        requestPct, modelPct, observePct);
    std::fflush(stdout);
  }

 private:
  static std::uint64_t elapsed(TimePoint start, TimePoint end) {
    return static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count());
  }
  bool enabled_ = false;
  std::uint64_t samples_ = 0;
  std::uint64_t requestNs_ = 0;
  std::uint64_t modelNs_ = 0;
  std::uint64_t observeNs_ = 0;
};
}  // namespace aethercore::pa32sim
