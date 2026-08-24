#pragma once

// P8.2 Branch-exposure extension for the existing measured-v2 host hook.
// v2_perf_host_hook.h owns the qualified cycle/memory stepping and the frozen
// legacy P8 snapshot. This layer only observes the new host-visible counters
// after that step and emits the two Branch-specific snapshot lines.

#include <cstddef>
#include <cstdint>
#include <iostream>

namespace aethercore::l32sim {
namespace v2branch_detail {

constexpr char kMarker[] = "RV64 USER UART IRQ OK";

struct State {
  std::size_t markerIndex = 0;
  bool markerEmitted = false;
  bool exitEmitted = false;
};

inline State& state() {
  static State instance;
  return instance;
}

template <typename Top>
void emitSnapshot(const Top& top) {
  std::cerr
      << "\nAETHERCORE_V2_PERF"
      << " ready_younger_branch=" << static_cast<std::uint64_t>(top.ioPerfReadyYoungerBranch)
      << " branch_age1=" << static_cast<std::uint64_t>(top.ioPerfBranchAge1)
      << " branch_age2=" << static_cast<std::uint64_t>(top.ioPerfBranchAge2)
      << " branch_age3=" << static_cast<std::uint64_t>(top.ioPerfBranchAge3)
      << "\nAETHERCORE_V2_PERF"
      << " branch_opportunity=" << static_cast<std::uint64_t>(top.ioPerfBranchOpportunity)
      << " branch_opp_head_not_ready=" << static_cast<std::uint64_t>(top.ioPerfBranchOppHeadNotReady)
      << " branch_opp_compute_head=" << static_cast<std::uint64_t>(top.ioPerfBranchOppComputeHead)
      << " branch_opp_branch_head=" << static_cast<std::uint64_t>(top.ioPerfBranchOppBranchHead)
      << " branch_opp_memory_head=" << static_cast<std::uint64_t>(top.ioPerfBranchOppMemoryHead)
      << " branch_opp_system_head=" << static_cast<std::uint64_t>(top.ioPerfBranchOppSystemHead)
      << " branch_opp_other_head=" << static_cast<std::uint64_t>(top.ioPerfBranchOppOtherHead)
      << " branch_opp_lsu_busy=" << static_cast<std::uint64_t>(top.ioPerfBranchOppLsuBusy)
      << "\n";
}

template <typename Top>
void observeAfterStep(const Top& top) {
  if (top.reset) return;
  auto& s = state();

  if (top.io_uartValid && !s.markerEmitted) {
    const char byte = static_cast<char>(top.io_uartByte);
    if (byte == kMarker[s.markerIndex]) {
      ++s.markerIndex;
      if (kMarker[s.markerIndex] == '\0') {
        s.markerEmitted = true;
        s.markerIndex = 0;
        emitSnapshot(top);
      }
    } else {
      s.markerIndex = (byte == kMarker[0]) ? 1U : 0U;
    }
  }

  if (top.io_exitValid && !s.markerEmitted && !s.exitEmitted) {
    s.exitEmitted = true;
    emitSnapshot(top);
  }
}

}  // namespace v2branch_detail

template <typename Top>
bool v2BranchPerfStep(Top& top, VerilatedContext& context, Memory& memory,
                      bool rxValid, std::uint8_t rxByte) {
  const bool accepted = v2PerfStep(top, context, memory, rxValid, rxByte);
  v2branch_detail::observeAfterStep(top);
  return accepted;
}

}  // namespace aethercore::l32sim

// v2_perf_host_hook.h redirected the historical runner token to v2PerfStep.
// Extend that redirect without changing the underlying qualified step ordering.
#undef step
#define step v2BranchPerfStep
