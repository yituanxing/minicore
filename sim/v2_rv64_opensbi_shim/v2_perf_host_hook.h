#pragma once

// This hook is included only by the v2 OpenSBI compatibility shim when the P8
// measurement build defines AETHERCORE_V2_PERF. Include the qualified runtime
// before redefining the runner's unqualified step token.
#include "../l32_opensbi_runtime.h"

#include <cstddef>
#include <cstdint>
#include <iostream>

namespace aethercore::l32sim {
namespace v2perf_detail {

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
      << "\nAETHERCORE_V2_PERF reason=" << reason
      << " cycles=" << static_cast<std::uint64_t>(top.ioPerfCycles)
      << " commits=" << static_cast<std::uint64_t>(top.ioPerfCommits)
      << " dispatch_accepted=" << static_cast<std::uint64_t>(top.ioPerfDispatchAccepted)
      << " dispatch_blocked=" << static_cast<std::uint64_t>(top.ioPerfDispatchBlocked)
      << "\nAETHERCORE_V2_PERF"
      << " rob0=" << static_cast<std::uint64_t>(top.ioPerfRob0)
      << " rob1=" << static_cast<std::uint64_t>(top.ioPerfRob1)
      << " rob2=" << static_cast<std::uint64_t>(top.ioPerfRob2)
      << " rob3=" << static_cast<std::uint64_t>(top.ioPerfRob3)
      << " rob4=" << static_cast<std::uint64_t>(top.ioPerfRob4)
      << "\nAETHERCORE_V2_PERF"
      << " issue_int=" << static_cast<std::uint64_t>(top.ioPerfIssueInt)
      << " issue_mul=" << static_cast<std::uint64_t>(top.ioPerfIssueMul)
      << " issue_div=" << static_cast<std::uint64_t>(top.ioPerfIssueDiv)
      << " issue_branch=" << static_cast<std::uint64_t>(top.ioPerfIssueBranch)
      << " issue_mem=" << static_cast<std::uint64_t>(top.ioPerfIssueMem)
      << " system_completion=" << static_cast<std::uint64_t>(top.ioPerfSystemCompletion)
      << "\nAETHERCORE_V2_PERF"
      << " selective_candidate=" << static_cast<std::uint64_t>(top.ioPerfSelectiveCandidate)
      << " selective_bypass=" << static_cast<std::uint64_t>(top.ioPerfSelectiveBypass)
      << " bypass_compute_head=" << static_cast<std::uint64_t>(top.ioPerfBypassComputeHead)
      << " bypass_branch_head=" << static_cast<std::uint64_t>(top.ioPerfBypassBranchHead)
      << " bypass_memory_head=" << static_cast<std::uint64_t>(top.ioPerfBypassMemoryHead)
      << " bypass_other_head=" << static_cast<std::uint64_t>(top.ioPerfBypassOtherHead)
      << " lsu_compute_overlap=" << static_cast<std::uint64_t>(top.ioPerfLsuComputeOverlap)
      << "\nAETHERCORE_V2_PERF"
      << " head_not_ready=" << static_cast<std::uint64_t>(top.ioPerfHeadNotReady)
      << " head_ready_not_issued=" << static_cast<std::uint64_t>(top.ioPerfHeadReadyNotIssued)
      << " commit_idle_nonempty=" << static_cast<std::uint64_t>(top.ioPerfCommitIdleNonempty)
      << " compute_head=" << static_cast<std::uint64_t>(top.ioPerfComputeHead)
      << " branch_head=" << static_cast<std::uint64_t>(top.ioPerfBranchHead)
      << " memory_head=" << static_cast<std::uint64_t>(top.ioPerfMemoryHead)
      << " system_head=" << static_cast<std::uint64_t>(top.ioPerfSystemHead)
      << " interrupt_hold=" << static_cast<std::uint64_t>(top.ioPerfInterruptHold)
      << " wfi_halted=" << static_cast<std::uint64_t>(top.ioPerfWfiHalted)
      << "\nAETHERCORE_V2_PERF"
      << " lsu_busy=" << static_cast<std::uint64_t>(top.ioPerfLsuBusy)
      << " memory_launch_blocked=" << static_cast<std::uint64_t>(top.ioPerfMemoryLaunchBlocked)
      << " mem_req=" << static_cast<std::uint64_t>(top.ioPerfMemReq)
      << " mem_resp=" << static_cast<std::uint64_t>(top.ioPerfMemResp)
      << " ptw_active=" << static_cast<std::uint64_t>(top.ioPerfPtwActive)
      << " completion_collision=" << static_cast<std::uint64_t>(top.ioPerfCompletionCollision)
      << " completion_backpressure=" << static_cast<std::uint64_t>(top.ioPerfCompletionBackpressure)
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

#ifdef AETHERCORE_SIM_ADAPTIVE_SETTLE
// Re-drive only the external memory feedback after the first low-phase eval.
// If every host-driven input remains bit-identical, another eval has no new
// external information to settle. If any input changes, retain the qualified
// second low-phase eval exactly.
template <typename Top>
bool redriveMemoryChanged(Top& top, const Memory& memory) {
  bool changed = false;
  auto assign = [&](auto& port, auto value) {
    const auto before = port;
    port = value;
    changed = changed || port != before;
  };

  const bool ivalid = top.io_imemValid;
  const auto iaddr = static_cast<std::uint64_t>(top.io_imemAddr);
  const auto ibytes = static_cast<std::size_t>(top.io_imemBytes);
  const bool invalidInstructionWidth = ibytes != 2 && ibytes != 4;
  const bool ifault = ivalid &&
      (invalidInstructionWidth || !memory.contains(iaddr, ibytes));
  assign(top.io_imemFault, ifault);
  assign(top.io_imemInst,
         (!ivalid || ifault) ? 0U : memory.readInstruction(iaddr, ibytes));

  const bool dvalid = top.io_memValid;
  const auto daddr = static_cast<std::uint64_t>(top.io_memAddr);
  const auto dbytes = dataBytesFromMemSize(static_cast<std::uint32_t>(top.io_memSize));
  const bool dfault = dvalid && !memory.contains(daddr, dbytes);
  assign(top.io_memReady, true);
  assign(top.io_memFault, dfault);

  bool atomic = false;
  std::uint32_t atomicOp = kAtomicNone;
  if constexpr (requires { top.io_memAtomic; top.io_memAtomicOp; }) {
    atomic = static_cast<bool>(top.io_memAtomic);
    atomicOp = static_cast<std::uint32_t>(top.io_memAtomicOp);
  }
  const std::uint64_t drdata = (!dvalid || dfault)
      ? 0ULL
      : (atomic ? memory.atomicResponse(daddr, dbytes, atomicOp)
                : memory.readData(daddr, dbytes));
  assign(top.io_memRdata, drdata);

  const bool ptwValid = top.io_ptwValid;
  const auto ptwAddr = static_cast<std::uint64_t>(top.io_ptwAddr);
  constexpr std::size_t ptwBytes = sizeof(top.io_ptwRdata);
  static_assert(ptwBytes == 4 || ptwBytes == 8,
                "page-table response port must carry a 4- or 8-byte PTE");
  const bool ptwFault = ptwValid && !memory.contains(ptwAddr, ptwBytes);
  assign(top.io_ptwReady, true);
  assign(top.io_ptwFault, ptwFault);
  assign(top.io_ptwRdata,
         (!ptwValid || ptwFault) ? 0ULL : memory.readData(ptwAddr, ptwBytes));
  return changed;
}

template <typename Top>
bool adaptiveStep(Top& top, VerilatedContext& context, Memory& memory,
                  bool rxValid, std::uint8_t rxByte) {
  top.clock = 0;
  top.io_rxValid = rxValid;
  top.io_rxByte = rxValid ? rxByte : 0;
  driveMemory(top, memory);
  top.eval();
  if (redriveMemoryChanged(top, memory)) top.eval();
  const bool rxAccepted = top.io_rxValid && top.io_rxReady;

  const bool acceptedMemory = !top.reset && top.io_memValid && top.io_memReady &&
      !top.io_memFault;
  bool atomic = false;
  std::uint32_t atomicOp = kAtomicNone;
  if constexpr (requires { top.io_memAtomic; top.io_memAtomicOp; }) {
    atomic = static_cast<bool>(top.io_memAtomic);
    atomicOp = static_cast<std::uint32_t>(top.io_memAtomicOp);
  }

  if (acceptedMemory && atomic) {
    const auto dbytes = dataBytesFromMemSize(static_cast<std::uint32_t>(top.io_memSize));
    memory.commitAtomic(
        static_cast<std::uint64_t>(top.io_memAddr), dbytes,
        static_cast<std::uint64_t>(top.io_memWdata),
        static_cast<std::uint64_t>(top.io_memWmask), atomicOp);
  } else if (acceptedMemory && top.io_memWrite) {
    const auto dbytes = dataBytesFromMemSize(static_cast<std::uint32_t>(top.io_memSize));
    memory.writeMasked(
        static_cast<std::uint64_t>(top.io_memAddr),
        static_cast<std::uint64_t>(top.io_memWdata),
        static_cast<std::uint64_t>(top.io_memWmask), dbytes);
    memory.clearReservation();
  }

  top.clock = 1;
  top.eval();
  context.timeInc(1);
  return rxAccepted;
}
#endif

}  // namespace v2perf_detail

template <typename Top>
bool v2PerfStep(Top& top, VerilatedContext& context, Memory& memory,
                bool rxValid, std::uint8_t rxByte) {
#ifdef AETHERCORE_SIM_ADAPTIVE_SETTLE
  const bool accepted = v2perf_detail::adaptiveStep(top, context, memory, rxValid, rxByte);
#else
  const bool accepted = step(top, context, memory, rxValid, rxByte);
#endif
  v2perf_detail::observeAfterStep(top);
  return accepted;
}

}  // namespace aethercore::l32sim

// opensbi_boot_main.cpp imports and calls the historical unqualified step name.
// Redirect only that P8 compilation unit after the original runtime has already
// been parsed, so every underlying cycle/host-memory ordering rule is unchanged.
#define step v2PerfStep
