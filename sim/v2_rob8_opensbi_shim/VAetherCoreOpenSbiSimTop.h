#pragma once

#include "VAetherCoreV2OpenSbiRV64SimTop.h"
using VAetherCoreOpenSbiSimTop = VAetherCoreV2OpenSbiRV64SimTop;

#ifdef AETHERCORE_V2_PERF
// Reuse the qualified P8/adaptive-settle hook for every existing counter and
// host-memory ordering rule, then add only the ROB8 exact occupancy observer.
#include "../v2_rv64_opensbi_shim/v2_perf_host_hook.h"
#undef step

namespace aethercore::l32sim {
namespace rob8_detail {

struct State {
  std::size_t markerIndex = 0;
  std::uint64_t nextPeriodic = v2perf_detail::kPeriodicCycles;
  bool markerEmitted = false;
};

inline State& state() {
  static State instance;
  return instance;
}

template <typename Top>
void emitSnapshot(const Top& top, const char* reason) {
  std::cerr
      << "\nAETHERCORE_V2_ROB8_OCC reason=" << reason
      << " cycles=" << static_cast<std::uint64_t>(top.ioPerfCycles)
      << " rob_exact0=" << static_cast<std::uint64_t>(top.ioPerfRobExact0)
      << " rob_exact1=" << static_cast<std::uint64_t>(top.ioPerfRobExact1)
      << " rob_exact2=" << static_cast<std::uint64_t>(top.ioPerfRobExact2)
      << " rob_exact3=" << static_cast<std::uint64_t>(top.ioPerfRobExact3)
      << " rob_exact4=" << static_cast<std::uint64_t>(top.ioPerfRobExact4)
      << " rob_exact5=" << static_cast<std::uint64_t>(top.ioPerfRobExact5)
      << " rob_exact6=" << static_cast<std::uint64_t>(top.ioPerfRobExact6)
      << " rob_exact7=" << static_cast<std::uint64_t>(top.ioPerfRobExact7)
      << " rob_exact8=" << static_cast<std::uint64_t>(top.ioPerfRobExact8)
      << "\n";
}

template <typename Top>
void observeAfterStep(const Top& top) {
  if (top.reset) return;
  auto& s = state();
  const auto cycles = static_cast<std::uint64_t>(top.ioPerfCycles);

  if (cycles >= s.nextPeriodic) {
    emitSnapshot(top, "periodic");
    do {
      s.nextPeriodic += v2perf_detail::kPeriodicCycles;
    } while (cycles >= s.nextPeriodic);
  }

  if (top.io_uartValid && !s.markerEmitted) {
    const char byte = static_cast<char>(top.io_uartByte);
    if (byte == v2perf_detail::kMarker[s.markerIndex]) {
      ++s.markerIndex;
      if (v2perf_detail::kMarker[s.markerIndex] == '\0') {
        s.markerEmitted = true;
        s.markerIndex = 0;
        emitSnapshot(top, "marker");
      }
    } else {
      s.markerIndex = (byte == v2perf_detail::kMarker[0]) ? 1U : 0U;
    }
  }
}

}  // namespace rob8_detail

template <typename Top>
bool rob8PerfStep(Top& top, VerilatedContext& context, Memory& memory,
                  bool rxValid, std::uint8_t rxByte) {
  const bool accepted = v2PerfStep(top, context, memory, rxValid, rxByte);
  rob8_detail::observeAfterStep(top);
  return accepted;
}

}  // namespace aethercore::l32sim

#define step rob8PerfStep
#endif
