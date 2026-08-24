#pragma once

// Extend the qualified P8 host hook with ROB8-only occupancy buckets. The base
// hook remains the owner of stepping, marker recognition and all frozen P8
// fields; this layer only appends rob5..rob8 to the same periodic/marker/exit
// snapshots after the base observation has run.
#include "v2_perf_host_hook.h"
#undef step

namespace aethercore::l32sim {
namespace v2rob8_perf_detail {

struct State {
  std::uint64_t nextPeriodic = v2perf_detail::kPeriodicCycles;
  bool markerEmitted = false;
  bool exitEmitted = false;
};

inline State& state() {
  static State instance;
  return instance;
}

template <typename Top>
void emitRob8(const Top& top) {
  std::cerr
      << "AETHERCORE_V2_PERF"
      << " rob5=" << static_cast<std::uint64_t>(top.ioPerfRob5)
      << " rob6=" << static_cast<std::uint64_t>(top.ioPerfRob6)
      << " rob7=" << static_cast<std::uint64_t>(top.ioPerfRob7)
      << " rob8=" << static_cast<std::uint64_t>(top.ioPerfRob8)
      << "\n";
}

template <typename Top>
void observeAfterBase(const Top& top) {
  if (top.reset) return;

  auto& s = state();
  const auto cycles = static_cast<std::uint64_t>(top.ioPerfCycles);

  if (cycles >= s.nextPeriodic) {
    emitRob8(top);
    do {
      s.nextPeriodic += v2perf_detail::kPeriodicCycles;
    } while (cycles >= s.nextPeriodic);
  }

  const auto& base = v2perf_detail::state();
  if (base.markerEmitted && !s.markerEmitted) {
    s.markerEmitted = true;
    emitRob8(top);
  }
  if (base.exitEmitted && !s.markerEmitted && !s.exitEmitted) {
    s.exitEmitted = true;
    emitRob8(top);
  }
}

}  // namespace v2rob8_perf_detail

template <typename Top>
bool v2Rob8PerfStep(Top& top, VerilatedContext& context, Memory& memory,
                    bool rxValid, std::uint8_t rxByte) {
  const bool accepted = v2PerfStep(top, context, memory, rxValid, rxByte);
  v2rob8_perf_detail::observeAfterBase(top);
  return accepted;
}

}  // namespace aethercore::l32sim

#define step v2Rob8PerfStep
