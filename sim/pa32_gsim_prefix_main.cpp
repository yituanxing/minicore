// Experimental GSIM PA32 RISC-V transport probe using the SAME production
// simulator host Memory, LR/SC and AMO algorithms as the Verilator runner.
// This is not qualified Linux signoff until cycle/commit/interrupt differential.
#define AETHERCORE_GSIM_HOST_MEMORY_ONLY
#include "l32_opensbi_runtime.h"
#include "pa32_gsim_port_adapter.h"
#include "pa32_firmware_milestone.h"
#include "pa32_sim_progress.h"
#include "pa32_gsim_phase_profile.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <memory>
#include <string>

namespace {
using aethercore::gsim::Request;
using aethercore::gsim::Response;
using aethercore::gsim::Ports;
using aethercore::gsim::Model;
using aethercore::l32sim::Memory;
using aethercore::l32sim::dataBytesFromMemSize;

Response readResponses(const Request& request, const Memory& mem) {
  Response response{};
  if (request.imemValid) {
    const auto width = static_cast<std::size_t>(request.imemBytes);
    response.imemFault = (width != 2 && width != 4) ||
                         !mem.contains(request.imemAddr, width);
    if (!response.imemFault)
      response.imemInst = mem.readInstruction(request.imemAddr, width);
  }
  if (request.memValid) {
    const auto width = dataBytesFromMemSize(request.memSize);
    response.memFault = !mem.contains(request.memAddr, width);
    if (!response.memFault) {
      response.memRdata =
          request.memAtomic ?
              mem.atomicResponse(request.memAddr, width, request.memAtomicOp) :
              mem.readData(request.memAddr, width);
    }
  }
  if (request.ptwValid) {
    constexpr std::size_t width = 8;
    response.ptwFault = !mem.contains(request.ptwAddr, width);
    if (!response.ptwFault) response.ptwRdata = mem.readData(request.ptwAddr, width);
  }
  return response;
}

bool sameResponse(const Response& a, const Response& b) {
  return a.imemInst == b.imemInst && a.imemFault == b.imemFault &&
         a.memReady == b.memReady && a.memRdata == b.memRdata &&
         a.memFault == b.memFault && a.ptwReady == b.ptwReady &&
         a.ptwRdata == b.ptwRdata && a.ptwFault == b.ptwFault;
}

#ifdef AETHERCORE_GSIM_HOST_BRIDGE
struct HostBridgeContext {
  Ports* ports;
  const Memory* memory;
  std::uint64_t calls = 0;
  std::uint64_t validFetchCalls = 0;
  std::uint64_t nonzeroFetchReplies = 0;
};
void hostBridge(void* opaque) {
  auto& ctx = *static_cast<HostBridgeContext*>(opaque);
  const Request req = ctx.ports->request();
  const Response resp = readResponses(req, *ctx.memory);
  ++ctx.calls;
  if (req.imemValid) {
    ++ctx.validFetchCalls;
    if (!resp.imemFault && resp.imemInst) ++ctx.nonzeroFetchReplies;
  }
  if (ctx.calls <= 24 || (req.imemValid && ctx.validFetchCalls <= 12)) {
    std::printf("AETHERCORE_PA32_GSIM_BRIDGE_EVENT index=%llu"
                " imem_valid=%u imem_addr=0x%08x imem_inst=0x%08x"
                " ptw_valid=%u mem_valid=%u\n",
                static_cast<unsigned long long>(ctx.calls),
                static_cast<unsigned>(req.imemValid), req.imemAddr,
                resp.imemInst, static_cast<unsigned>(req.ptwValid),
                static_cast<unsigned>(req.memValid));
  }
  ctx.ports->respond(resp);
}
#endif

void commitAcceptedMemory(const Request& req, const Response& res,
                          Memory& memory, bool reset) {
  if (reset || !req.memValid || !res.memReady || res.memFault) return;
  const auto width = dataBytesFromMemSize(req.memSize);
  if (req.memAtomic) {
    memory.commitAtomic(req.memAddr, width, req.memWdata,
                        req.memWmask, req.memAtomicOp);
  } else if (req.memWrite) {
    memory.writeMasked(req.memAddr, req.memWdata, req.memWmask, width);
    memory.clearReservation();
  }
}
}

int main(int argc, char** argv) {
  if (argc != 3 && argc != 4) {
    std::fprintf(stderr, "usage: pa32_gsim_prefix_main [--bare|--memory|FIRMWARE] MAX_CYCLES [--linux]\n");
    return 2;
  }
  try {
    const bool requireLinux = argc == 4 && std::string(argv[3]) == "--linux";
    if (argc == 4 && !requireLinux) return 2;
    const auto total = std::stoull(argv[2]);
    if (total < 1 || total > 1000000000ULL) return 3;
    Memory memory;
    std::puts("AETHERCORE_PA32_GSIM_SINGLE_STEP_TIMING_UNQUALIFIED");
    const bool memoryProbe = std::string(argv[1]) == "--memory";
    const bool bareProbe = std::string(argv[1]) == "--bare";
    if (bareProbe) {
      // RV64I: addi x1,x0,1; addi x2,x1,2; addi x3,x2,3; jal x0,0.
      const std::uint32_t program[] = {
          0x00100093U, 0x00208113U, 0x00310193U, 0x0000006fU};
      for (unsigned i = 0; i < 4; ++i)
        memory.write32Masked(aethercore::l32sim::kRamBase + i*4,
                             program[i], 0xFU);
    } else if (memoryProbe) {
      // RV64IM/A transport: store -> load -> LR.W -> SC.W -> AMOADD.W.
      // Expected final word at 0x80000080: (42 + 1) + 42 = 85.
      constexpr std::uint32_t program[] = {
          0x00000097U, 0x02a00113U, 0x08008393U, 0x0023a023U,
          0x0003a183U, 0x00118213U, 0x1003a2afU, 0x1843a32fU,
          0x0003a403U, 0x0023a4afU, 0x0003a503U, 0x0000006fU};
      for (std::size_t i = 0; i < sizeof(program)/sizeof(program[0]); ++i)
        memory.write32Masked(aethercore::l32sim::kRamBase + i*4,
                             program[i], 0xFU);
    } else {
      memory.loadAtBase(argv[1]);
    }
    if (bareProbe) {
      for (unsigned i = 0; i < 4; ++i) {
        std::printf("AETHERCORE_PA32_GSIM_RAM_SEED addr=0x%08llx inst=0x%08x\n",
                    static_cast<unsigned long long>(
                        aethercore::l32sim::kRamBase + 4*i),
                    memory.read32(aethercore::l32sim::kRamBase + 4*i));
      }
    }
    auto top = std::make_unique<Model>();
    Ports ports(*top);
#ifdef AETHERCORE_GSIM_HOST_BRIDGE
    HostBridgeContext hostContext{&ports, &memory};
    top->pa32HostBridge = &hostBridge;
    top->pa32HostContext = &hostContext;
    std::puts("AETHERCORE_PA32_GSIM_BRIDGE_ENABLED");
#endif
    ports.rx(false, 0);
    ports.reset(true);
    ports.respond({});

    std::uint64_t retires = 0, interrupts = 0, exceptions = 0;
    std::uint64_t matchingBareInstructions = 0;
    constexpr std::uint32_t kBareInst[] = {0x00100093U, 0x00208113U, 0x00310193U};
    std::uint64_t digest = 14695981039346656037ULL;
    aethercore::pa32sim::FirmwareMilestone firmware;
    ports.clock(true);
    std::uint64_t lastPc = 0;
    std::uint64_t executedCycles = 0;
    const auto hostStart = std::chrono::steady_clock::now();
    aethercore::pa32sim::SimProgress progress("GSIM", total, requireLinux, hostStart);
    aethercore::pa32sim::GsimPhaseProfile phaseProfile;

    for (std::uint64_t cycle = 0; cycle < total + 32; ++cycle) {
      const bool reset = cycle < 32;
      ports.reset(reset);
      // GSIM step() computes AND commits one abstract tick; it is not a
      // combinational eval(). Do not call it twice/three times per guest cycle.
      // The host response observed from the previous tick is delivered to
      // the next one. This provisional timing requires Verilator differential.
      const bool sampleCycle = phaseProfile.sample(cycle, reset);
      const auto beforeRequest = sampleCycle
          ? aethercore::pa32sim::GsimPhaseProfile::Clock::now()
          : aethercore::pa32sim::GsimPhaseProfile::TimePoint{};
      const Request request = ports.request();
      const Response response = readResponses(request, memory);
      if (bareProbe && cycle >= 32 && cycle < 160) {
        std::printf(
            "AETHERCORE_PA32_GSIM_RAM_TRACE guest_cycle=%llu "
            "imem_valid=%u imem_addr=0x%08x imem_bytes=%u "
            "reply_inst=0x%08x reply_fault=%u "
            "mem_valid=%u mem_addr=0x%08x ptw_valid=%u ptw_addr=0x%08x\n",
            static_cast<unsigned long long>(cycle - 32),
            static_cast<unsigned>(request.imemValid), request.imemAddr,
            static_cast<unsigned>(request.imemBytes), response.imemInst,
            static_cast<unsigned>(response.imemFault),
            static_cast<unsigned>(request.memValid), request.memAddr,
            static_cast<unsigned>(request.ptwValid), request.ptwAddr);
      }
      ports.respond(response);
      commitAcceptedMemory(request, response, memory, reset);
      ports.clock(true);
      const auto beforeModel = sampleCycle
          ? aethercore::pa32sim::GsimPhaseProfile::Clock::now()
          : aethercore::pa32sim::GsimPhaseProfile::TimePoint{};
      top->step();
      const auto afterModel = sampleCycle
          ? aethercore::pa32sim::GsimPhaseProfile::Clock::now()
          : aethercore::pa32sim::GsimPhaseProfile::TimePoint{};
      if (reset) continue;
      ++executedCycles;

      const auto commit = ports.commit();
      firmware.observeUart(ports.uartValid(), ports.uartByte(), executedCycles);
      firmware.observeInterrupt(commit.interrupt, commit.interruptCause);
      if (commit.valid) {
        ++retires;
        lastPc = commit.pc;
        digest ^= commit.pc;
        digest *= 1099511628211ULL;
        digest ^= commit.inst;
        digest *= 1099511628211ULL;
        if (bareProbe &&
            commit.pc >= aethercore::l32sim::kRamBase &&
            commit.pc < aethercore::l32sim::kRamBase + sizeof(kBareInst) &&
            (commit.pc - aethercore::l32sim::kRamBase) % 4 == 0) {
          const auto idx = static_cast<std::size_t>(
              (commit.pc - aethercore::l32sim::kRamBase)/4);
          if (idx < 3 && commit.inst == kBareInst[idx] &&
              matchingBareInstructions == idx)
            ++matchingBareInstructions;
        }
        if (retires <= 16)
          std::printf("AETHERCORE_PA32_GSIM_COMMIT index=%llu pc=0x%llx inst=0x%08x exception=%u cause=0x%llx\n",
                      static_cast<unsigned long long>(retires),
                      static_cast<unsigned long long>(commit.pc), commit.inst,
                      static_cast<unsigned>(commit.exception),
                      static_cast<unsigned long long>(commit.exceptionCause));
        if (commit.exception) ++exceptions;
      }
      if (commit.interrupt) ++interrupts;
      if ((cycle - 31) % 100000 == 0) {
        progress.observe(executedCycles, retires, firmware);
        std::printf("AETHERCORE_PA32_GSIM_CHECKPOINT cycle=%llu retires=%llu"
                    " exceptions=%llu interrupts=%llu mtime=%llu"
                    " last_pc=0x%llx commit_digest=0x%llx\n",
                    static_cast<unsigned long long>(cycle - 31),
                    static_cast<unsigned long long>(retires),
                    static_cast<unsigned long long>(exceptions),
                    static_cast<unsigned long long>(interrupts),
                    static_cast<unsigned long long>(ports.mtime()),
                    static_cast<unsigned long long>(lastPc),
                    static_cast<unsigned long long>(digest));
      }
      if (sampleCycle)
        phaseProfile.record(
            beforeRequest, beforeModel, afterModel,
            aethercore::pa32sim::GsimPhaseProfile::Clock::now());
      if (requireLinux && firmware.linuxPassed()) break;
    }

    progress.finish(executedCycles, retires, firmware);
    const double elapsed = std::chrono::duration<double>(
        std::chrono::steady_clock::now() - hostStart).count();
    const std::uint64_t ramDigest = memory.stateDigest();
    phaseProfile.report(executedCycles);
    std::printf("AETHERCORE_PA32_GSIM_PREFIX guest_cycles=%llu retires=%llu exceptions=%llu interrupts=%llu mtime=%llu last_pc=0x%llx commit_digest=0x%llx host_seconds=%.6f host_cps=%.1f matched_rv64i=%llu ram_digest=0x%llx uart_bytes=%llu uart_digest=0x%llx opensbi_banner=%u linux_banner=%u milestone_cycle=%llu stip=%llu seip=%llu\n",
                static_cast<unsigned long long>(executedCycles),
                static_cast<unsigned long long>(retires),
                static_cast<unsigned long long>(exceptions),
                static_cast<unsigned long long>(interrupts),
                static_cast<unsigned long long>(ports.mtime()),
                static_cast<unsigned long long>(lastPc),
                static_cast<unsigned long long>(digest),
                elapsed, elapsed > 0 ? executedCycles / elapsed : 0.0,
                static_cast<unsigned long long>(matchingBareInstructions),
                static_cast<unsigned long long>(ramDigest),
                static_cast<unsigned long long>(firmware.uartBytes),
                static_cast<unsigned long long>(firmware.uartDigest),
                static_cast<unsigned>(firmware.opensbiBanner),
                static_cast<unsigned>(firmware.linuxBanner),
                static_cast<unsigned long long>(firmware.milestoneCycle),
                static_cast<unsigned long long>(firmware.stip),
                static_cast<unsigned long long>(firmware.seip));

#ifdef AETHERCORE_GSIM_HOST_BRIDGE
    std::printf("AETHERCORE_PA32_GSIM_BRIDGE_METRICS calls=%llu valid_fetch=%llu nonzero_fetch_replies=%llu\n",
                static_cast<unsigned long long>(hostContext.calls),
                static_cast<unsigned long long>(hostContext.validFetchCalls),
                static_cast<unsigned long long>(hostContext.nonzeroFetchReplies));
#endif
    if (bareProbe) {
      if (matchingBareInstructions != 3) {
        std::fprintf(stderr,
            "AETHERCORE_PA32_GSIM_BARE_FAIL matched_rv64i=%llu expected=3 "
            "real_RV64I_instructions_not_retired\n",
            static_cast<unsigned long long>(matchingBareInstructions));
        return 11;
      }
      std::puts("AETHERCORE_PA32_GSIM_BARE_PASS");
    } else if (memoryProbe) {
      const std::uint32_t finalWord = memory.read32(aethercore::l32sim::kRamBase + 128);
      if (finalWord != 85 || exceptions != 0) {
        std::fprintf(stderr,
            "AETHERCORE_PA32_GSIM_MEMORY_FAIL final_word=%u exceptions=%llu\n",
            finalWord, static_cast<unsigned long long>(exceptions));
        return 13;
      }
      std::printf("AETHERCORE_PA32_GSIM_MEMORY_PASS final_word=%u\n", finalWord);
    } else if (requireLinux) {
      if (!firmware.linuxPassed()) {
        std::fprintf(stderr, "AETHERCORE_PA32_GSIM_LINUX_MILESTONE_FAIL cycles=%llu marker=%llu stip=%llu seip=%llu\n",
                     static_cast<unsigned long long>(executedCycles),
                     static_cast<unsigned long long>(firmware.milestoneCycle),
                     static_cast<unsigned long long>(firmware.stip),
                     static_cast<unsigned long long>(firmware.seip));
        return 14;
      }
      std::printf("AETHERCORE_PA32_GSIM_LINUX_MILESTONE_PASS cycles=%llu marker=RV64_USER_UART_IRQ_OK stip=%llu seip=%llu\n",
                  static_cast<unsigned long long>(executedCycles),
                  static_cast<unsigned long long>(firmware.stip),
                  static_cast<unsigned long long>(firmware.seip));
    } else {
      std::puts("AETHERCORE_PA32_GSIM_FIRMWARE_PREFIX_OBSERVED_UNQUALIFIED");
    }
    return 0;
  } catch (const std::exception& e) {
    std::fprintf(stderr, "AETHERCORE_PA32_GSIM_TRANSPORT_ERROR %s\n", e.what());
    return 12;
  }
}
