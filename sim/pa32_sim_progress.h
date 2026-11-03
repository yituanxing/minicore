#pragma once
// Both RTL engines report guest progress at existing 100k-cycle checkpoints.
// Host-only reporting: never mutates the guest state or guest clock.
// Progress and COMMIT/CHECKPOINT must use the same stdout FILE* to prevent
// interleaved stderr from splitting a strict retirement record.
#include "pa32_firmware_milestone.h"
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>

namespace aethercore::pa32sim {
class SimProgress {
 public:
  using Clock = std::chrono::steady_clock;
  SimProgress(const char* engine, std::uint64_t budget, bool requireLinux,
              Clock::time_point start)
      : engine_(engine), budget_(budget), linux_(requireLinux),
        start_(start), interval_(readInterval()), next_(interval_) {
    std::fprintf(stdout,
        "AETHERCORE_PA32_SIM_STAGE engine=%s status=START mode=%s "
        "target_cycles=%llu interval=%llu\n",
        engine_, linux_ ? "linux-pid1" : "prefix",
        static_cast<unsigned long long>(budget_),
        static_cast<unsigned long long>(interval_));
    std::fflush(stdout);
  }
  void observe(std::uint64_t cycles, std::uint64_t retires,
               const FirmwareMilestone& state) {
    event(state.opensbiBanner, opensbi_, "OPENSBI", cycles);
    event(state.linuxBanner, kernel_, "LINUX", cycles);
    event(state.milestoneCycle != 0, pid1_, "PID1", cycles);
    event(state.stip != 0, stip_, "STIP", cycles);
    event(state.seip != 0, seip_, "SEIP", cycles);
    if (cycles < next_) return;
    const double seconds = std::chrono::duration<double>(
        Clock::now() - start_).count();
    const double cps = seconds > 0.0 ? cycles / seconds : 0.0;
    const double eta = cps > 0.0 && cycles < budget_
        ? (budget_ - cycles) / cps : 0.0;
    std::fprintf(stdout,
        "AETHERCORE_PA32_SIM_PROGRESS engine=%s cycles=%llu "
        "target_cycles=%llu percent=%.2f host_seconds=%.2f "
        "host_cps=%.1f budget_eta_seconds=%.1f retires=%llu "
        "opensbi=%u linux=%u pid1=%u stip=%llu seip=%llu\n",
        engine_, static_cast<unsigned long long>(cycles),
        static_cast<unsigned long long>(budget_),
        100.0 * cycles / budget_, seconds, cps, eta,
        static_cast<unsigned long long>(retires),
        static_cast<unsigned>(state.opensbiBanner),
        static_cast<unsigned>(state.linuxBanner),
        static_cast<unsigned>(state.milestoneCycle != 0),
        static_cast<unsigned long long>(state.stip),
        static_cast<unsigned long long>(state.seip));
    std::fflush(stdout);
    next_ = (cycles / interval_ + 1) * interval_;
  }
  void finish(std::uint64_t cycles, std::uint64_t retires,
              const FirmwareMilestone& state) {
    observe(cycles, retires, state);
    std::fprintf(stdout,
        "AETHERCORE_PA32_SIM_STAGE engine=%s status=%s "
        "guest_cycles=%llu retires=%llu opensbi=%u linux=%u "
        "pid1=%u stip=%llu seip=%llu\n",
        engine_, linux_ && !state.linuxPassed() ? "MILESTONE_MISSING" : "END",
        static_cast<unsigned long long>(cycles),
        static_cast<unsigned long long>(retires),
        static_cast<unsigned>(state.opensbiBanner),
        static_cast<unsigned>(state.linuxBanner),
        static_cast<unsigned>(state.milestoneCycle != 0),
        static_cast<unsigned long long>(state.stip),
        static_cast<unsigned long long>(state.seip));
    std::fflush(stdout);
  }
 private:
  static std::uint64_t readInterval() {
    const char* raw = std::getenv("AETHERCORE_SIM_PROGRESS_CYCLES");
    if (!raw || !*raw) return 5000000ULL;
    errno = 0;
    char* end = nullptr;
    const auto value = std::strtoull(raw, &end, 10);
    if (errno || end == raw || *end || value == 0 || raw[0] == '-')
      throw std::invalid_argument("invalid AETHERCORE_SIM_PROGRESS_CYCLES");
    return static_cast<std::uint64_t>(value);
  }
  void event(bool active, bool& seen, const char* name, std::uint64_t cycles) {
    if (!active || seen) return;
    seen = true;
    std::fprintf(stdout,
        "AETHERCORE_PA32_SIM_MILESTONE engine=%s name=%s cycle=%llu\n",
        engine_, name, static_cast<unsigned long long>(cycles));
    std::fflush(stdout);
  }
  const char* engine_;
  std::uint64_t budget_;
  bool linux_;
  Clock::time_point start_;
  std::uint64_t interval_, next_;
  bool opensbi_ = false, kernel_ = false, pid1_ = false;
  bool stip_ = false, seip_ = false;
};
}  // namespace aethercore::pa32sim
