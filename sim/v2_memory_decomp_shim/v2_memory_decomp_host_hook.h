#pragma once

#include <cstddef>
#include <cstdint>
#include <iostream>

// v2_perf_host_hook.h is included immediately before this file by the shim. It
// leaves the runner's step token redirected to v2PerfStep; replace that token
// with one more observation-only wrapper while calling the qualified P8 step.
#ifdef step
#undef step
#endif

namespace aethercore::l32sim {
namespace v2memory_detail {

constexpr char kMarker[] = "RV64 USER UART IRQ OK";
constexpr std::uint64_t kPeriodicCycles = 10000000ULL;

struct State {
  std::size_t markerIndex = 0;
  std::uint64_t nextPeriodic = kPeriodicCycles;
  bool markerEmitted = false;
  bool exitEmitted = false;
};

inline State& state() {
  static State instance;
  return instance;
}

template <typename Top>
void emitSnapshot(const Top& top, const char* reason) {
  std::cerr
      << "\nAETHERCORE_V2_MEMORY reason=" << reason
      << " mem_head_load=" << static_cast<std::uint64_t>(top.ioMemHeadLoad)
      << " mem_head_store=" << static_cast<std::uint64_t>(top.ioMemHeadStore)
      << " mem_head_atomic=" << static_cast<std::uint64_t>(top.ioMemHeadAtomic)
      << " mem_issue_load=" << static_cast<std::uint64_t>(top.ioMemIssueLoad)
      << " mem_issue_store=" << static_cast<std::uint64_t>(top.ioMemIssueStore)
      << " mem_issue_atomic=" << static_cast<std::uint64_t>(top.ioMemIssueAtomic)
      << "\nAETHERCORE_V2_MEMORY"
      << " mem_head_lsu_busy=" << static_cast<std::uint64_t>(top.ioMemHeadLsuBusy)
      << " mem_head_ptw_active=" << static_cast<std::uint64_t>(top.ioMemHeadPtwActive)
      << " younger_ready_load=" << static_cast<std::uint64_t>(top.ioReadyYoungerLoad)
      << " younger_ready_load_age1=" << static_cast<std::uint64_t>(top.ioReadyYoungerLoadAge1)
      << " younger_ready_load_age2=" << static_cast<std::uint64_t>(top.ioReadyYoungerLoadAge2)
      << " younger_ready_load_age3=" << static_cast<std::uint64_t>(top.ioReadyYoungerLoadAge3)
      << " younger_ready_load_lsu_idle=" << static_cast<std::uint64_t>(top.ioReadyYoungerLoadLsuIdle)
      << "\nAETHERCORE_V2_MEMORY"
      << " younger_ready_load_compute_frontier="
      << static_cast<std::uint64_t>(top.ioReadyYoungerLoadComputeFrontier)
      << " younger_ready_load_compute_frontier_lsu_idle="
      << static_cast<std::uint64_t>(top.ioReadyYoungerLoadComputeFrontierLsuIdle)
      << " younger_ready_load_behind_memory_head="
      << static_cast<std::uint64_t>(top.ioReadyYoungerLoadBehindMemoryHead)
      << " younger_ready_load_behind_memory_head_lsu_busy="
      << static_cast<std::uint64_t>(top.ioReadyYoungerLoadBehindMemoryHeadLsuBusy)
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
      s.nextPeriodic += kPeriodicCycles;
    } while (cycles >= s.nextPeriodic);
  }

  if (top.io_uartValid && !s.markerEmitted) {
    const char byte = static_cast<char>(top.io_uartByte);
    if (byte == kMarker[s.markerIndex]) {
      ++s.markerIndex;
      if (kMarker[s.markerIndex] == '\0') {
        s.markerEmitted = true;
        s.markerIndex = 0;
        emitSnapshot(top, "marker");
      }
    } else {
      s.markerIndex = (byte == kMarker[0]) ? 1U : 0U;
    }
  }

  if (top.io_exitValid && !s.markerEmitted && !s.exitEmitted) {
    s.exitEmitted = true;
    emitSnapshot(top, "exit");
  }
}

}  // namespace v2memory_detail

template <typename Top>
bool v2MemoryDecompStep(Top& top, VerilatedContext& context, Memory& memory,
                        bool rxValid, std::uint8_t rxByte) {
  const bool accepted = v2PerfStep(top, context, memory, rxValid, rxByte);
  v2memory_detail::observeAfterStep(top);
  return accepted;
}

}  // namespace aethercore::l32sim

#define step v2MemoryDecompStep
