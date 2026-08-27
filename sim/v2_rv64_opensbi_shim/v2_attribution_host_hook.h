#pragma once

// Layer causal P8 attribution on top of the already-qualified v2PerfStep hook.
// The legacy AETHERCORE_V2_PERF snapshot remains untouched; this wrapper only
// reads the additional host-visible counters after the same simulated cycle.

#include <cstddef>
#include <cstdint>
#include <iostream>

namespace aethercore::l32sim {
namespace v2attr_detail {

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
      << "\nAETHERCORE_V2_TOPDOWN reason=" << reason
      << " cycles=" << static_cast<std::uint64_t>(top.ioTopDownCycles)
      << " flow=" << static_cast<std::uint64_t>(top.ioTopDownFlow)
      << " frontend_bound=" << static_cast<std::uint64_t>(top.ioTopDownFrontendBound)
      << " backend_bound=" << static_cast<std::uint64_t>(top.ioTopDownBackendBound)
      << "\nAETHERCORE_V2_CRITICAL"
      << " retire=" << static_cast<std::uint64_t>(top.ioCriticalRetire)
      << " rob_empty=" << static_cast<std::uint64_t>(top.ioCriticalRobEmpty)
      << " compute=" << static_cast<std::uint64_t>(top.ioCriticalCompute)
      << " branch=" << static_cast<std::uint64_t>(top.ioCriticalBranch)
      << " memory=" << static_cast<std::uint64_t>(top.ioCriticalMemory)
      << " system=" << static_cast<std::uint64_t>(top.ioCriticalSystem)
      << " other=" << static_cast<std::uint64_t>(top.ioCriticalOther)
      << "\nAETHERCORE_V2_MEMORY"
      << " lsu_busy=" << static_cast<std::uint64_t>(top.ioCausalLsuBusy)
      << " kind_load=" << static_cast<std::uint64_t>(top.ioMemoryKindLoad)
      << " kind_store=" << static_cast<std::uint64_t>(top.ioMemoryKindStore)
      << " kind_atomic=" << static_cast<std::uint64_t>(top.ioMemoryKindAtomic)
      << " kind_other=" << static_cast<std::uint64_t>(top.ioMemoryKindOther)
      << "\nAETHERCORE_V2_MEMORY"
      << " stage_resolve=" << static_cast<std::uint64_t>(top.ioMemoryStageResolve)
      << " stage_permit=" << static_cast<std::uint64_t>(top.ioMemoryStagePermit)
      << " stage_req_backpressure=" << static_cast<std::uint64_t>(top.ioMemoryStageRequestBackpressure)
      << " stage_req_fire=" << static_cast<std::uint64_t>(top.ioMemoryStageRequestFire)
      << " stage_response=" << static_cast<std::uint64_t>(top.ioMemoryStageResponse)
      << " stage_completion=" << static_cast<std::uint64_t>(top.ioMemoryStageCompletion)
      << " stage_other=" << static_cast<std::uint64_t>(top.ioMemoryStageOther)
      << "\nAETHERCORE_V2_MEMORY"
      << " resolve_load=" << static_cast<std::uint64_t>(top.ioMemoryResolveLoad)
      << " resolve_store=" << static_cast<std::uint64_t>(top.ioMemoryResolveStore)
      << " resolve_atomic=" << static_cast<std::uint64_t>(top.ioMemoryResolveAtomic)
      << " response_load=" << static_cast<std::uint64_t>(top.ioMemoryResponseLoad)
      << " response_store=" << static_cast<std::uint64_t>(top.ioMemoryResponseStore)
      << " response_atomic=" << static_cast<std::uint64_t>(top.ioMemoryResponseAtomic)
      << " completion_load=" << static_cast<std::uint64_t>(top.ioMemoryCompletionLoad)
      << " completion_store=" << static_cast<std::uint64_t>(top.ioMemoryCompletionStore)
      << " completion_atomic=" << static_cast<std::uint64_t>(top.ioMemoryCompletionAtomic)
      << " permit_store=" << static_cast<std::uint64_t>(top.ioMemoryPermitStore)
      << " permit_atomic=" << static_cast<std::uint64_t>(top.ioMemoryPermitAtomic)
      << "\nAETHERCORE_V2_ATTR_V11 reason=" << reason
      << " cycles=" << static_cast<std::uint64_t>(top.ioV11Cycles)
      << " branch_resolved=" << static_cast<std::uint64_t>(top.ioV11BranchResolved)
      << " branch_taken=" << static_cast<std::uint64_t>(top.ioV11BranchTaken)
      << " branch_recovery=" << static_cast<std::uint64_t>(top.ioV11BranchRecovery)
      << " branch_squashed_uops=" << static_cast<std::uint64_t>(top.ioV11BranchSquashedUops)
      << " issue_launch=" << static_cast<std::uint64_t>(top.ioV11IssueLaunch)
      << " issue_idle_launchable=" << static_cast<std::uint64_t>(top.ioV11IssueIdleLaunchable)
      << " issue_idle_no_launchable=" << static_cast<std::uint64_t>(top.ioV11IssueIdleNoLaunchable)
      << " issue_inactive=" << static_cast<std::uint64_t>(top.ioV11IssueInactive)
      << " shadow_compute_ready=" << static_cast<std::uint64_t>(top.ioV11ShadowComputeReady)
      << " dual_compute_candidate=" << static_cast<std::uint64_t>(top.ioV11DualComputeCandidate)
      << " frontend_second_parcel=" << static_cast<std::uint64_t>(top.ioV11FrontendSecondParcel)
      << " frontend_bound_second_parcel=" << static_cast<std::uint64_t>(top.ioV11FrontendBoundSecondParcel)
      << " memory_terminal_valid=" << static_cast<std::uint64_t>(top.ioV11MemoryTerminalValid)
      << " memory_terminal_fire=" << static_cast<std::uint64_t>(top.ioV11MemoryTerminalFire)
      << " memory_terminal_hold=" << static_cast<std::uint64_t>(top.ioV11MemoryTerminalHold);

  if constexpr (requires {
      top.ioV13Cycles;
      top.ioV13ConditionalResolved;
      top.ioV13ConditionalRecovery;
      top.ioV13DirectResolved;
      top.ioV13DirectRecovery;
      top.ioV13IndirectResolved;
      top.ioV13IndirectRecovery;
      top.ioV13CompletedStoreBarrier;
      top.ioV13IncompleteStoreBarrier;
  }) {
    std::cerr
        << "\nAETHERCORE_V2_ATTR_V13 reason=" << reason
        << " cycles=" << static_cast<std::uint64_t>(top.ioV13Cycles)
        << " conditional_resolved=" << static_cast<std::uint64_t>(top.ioV13ConditionalResolved)
        << " conditional_recovery=" << static_cast<std::uint64_t>(top.ioV13ConditionalRecovery)
        << " direct_resolved=" << static_cast<std::uint64_t>(top.ioV13DirectResolved)
        << " direct_recovery=" << static_cast<std::uint64_t>(top.ioV13DirectRecovery)
        << " indirect_resolved=" << static_cast<std::uint64_t>(top.ioV13IndirectResolved)
        << " indirect_recovery=" << static_cast<std::uint64_t>(top.ioV13IndirectRecovery)
        << " completed_store_barrier=" << static_cast<std::uint64_t>(top.ioV13CompletedStoreBarrier)
        << " incomplete_store_barrier=" << static_cast<std::uint64_t>(top.ioV13IncompleteStoreBarrier);
  }
  std::cerr << "\n";
}

template <typename Top>
void observeAfterStep(const Top& top) {
  if (top.reset) return;
  auto& s = state();
  const auto cycles = static_cast<std::uint64_t>(top.ioTopDownCycles);

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

}  // namespace v2attr_detail

template <typename Top>
bool v2AttributedStep(Top& top, VerilatedContext& context, Memory& memory,
                      bool rxValid, std::uint8_t rxByte) {
  const bool accepted = v2PerfStep(top, context, memory, rxValid, rxByte);
  v2attr_detail::observeAfterStep(top);
  return accepted;
}

}  // namespace aethercore::l32sim

// v2_perf_host_hook.h redirected the historical step token to v2PerfStep.
// Replace only that macro alias; the underlying qualified implementation is
// still called exactly once per simulated cycle.
#undef step
#define step v2AttributedStep