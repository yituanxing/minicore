// Experimental GSIM PA32 RISC-V transport probe using the SAME production
// simulator host Memory, LR/SC and AMO algorithms as the Verilator runner.
// This is not qualified Linux signoff until cycle/commit/interrupt differential.
#define AETHERCORE_GSIM_HOST_MEMORY_ONLY
#include "l32_opensbi_runtime.h"
#include "pa32_gsim_port_adapter.h"

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
  if (argc != 3) {
    std::fprintf(stderr, "usage: pa32_gsim_prefix_main [--bare|FIRMWARE] CYCLES\n");
    return 2;
  }
  try {
    const auto total = std::stoull(argv[2]);
    if (total < 1 || total > 1000000000ULL) return 3;
    Memory memory;
    std::puts("AETHERCORE_PA32_GSIM_SINGLE_STEP_TIMING_UNQUALIFIED");
    if (std::string(argv[1]) == "--bare") {
      // RV64I: addi x1,x0,1; addi x2,x1,2; addi x3,x2,3; jal x0,0.
      const std::uint32_t program[] = {
          0x00100093U, 0x00208113U, 0x00310193U, 0x0000006fU};
      for (unsigned i = 0; i < 4; ++i)
        memory.write32Masked(aethercore::l32sim::kRamBase + i*4,
                             program[i], 0xFU);
    } else {
      memory.loadAtBase(argv[1]);
    }
    auto top = std::make_unique<Model>();
    Ports ports(*top);
    ports.rx(false, 0);
    ports.reset(true);
    ports.respond({});

    std::uint64_t retires = 0, interrupts = 0, exceptions = 0;
    std::uint64_t digest = 14695981039346656037ULL;
    ports.clock(true);
    std::uint64_t lastPc = 0;
    const auto hostStart = std::chrono::steady_clock::now();

    for (std::uint64_t cycle = 0; cycle < total + 32; ++cycle) {
      const bool reset = cycle < 32;
      ports.reset(reset);
      // GSIM step() computes AND commits one abstract tick; it is not a
      // combinational eval(). Do not call it twice/three times per guest cycle.
      // The host response observed from the previous tick is delivered to
      // the next one. This provisional timing requires Verilator differential.
      const Request request = ports.request();
      const Response response = readResponses(request, memory);
      ports.respond(response);
      commitAcceptedMemory(request, response, memory, reset);
      ports.clock(true);
      top->step();
      if (reset) continue;

      const auto commit = ports.commit();
      if (commit.valid) {
        ++retires;
        lastPc = commit.pc;
        digest ^= commit.pc;
        digest *= 1099511628211ULL;
        digest ^= commit.inst;
        digest *= 1099511628211ULL;
        if (retires <= 8)
          std::printf("AETHERCORE_PA32_GSIM_COMMIT index=%llu pc=0x%llx inst=0x%08x\n",
                      static_cast<unsigned long long>(retires),
                      static_cast<unsigned long long>(commit.pc), commit.inst);
        if (commit.exception) ++exceptions;
      }
      if (commit.interrupt) ++interrupts;
    }

    const double elapsed = std::chrono::duration<double>(
        std::chrono::steady_clock::now() - hostStart).count();
    std::printf("AETHERCORE_PA32_GSIM_PREFIX guest_cycles=%llu retires=%llu exceptions=%llu interrupts=%llu mtime=%llu last_pc=0x%llx commit_digest=0x%llx host_seconds=%.6f host_cps=%.1f\n",
                static_cast<unsigned long long>(total),
                static_cast<unsigned long long>(retires),
                static_cast<unsigned long long>(exceptions),
                static_cast<unsigned long long>(interrupts),
                static_cast<unsigned long long>(ports.mtime()),
                static_cast<unsigned long long>(lastPc),
                static_cast<unsigned long long>(digest),
                elapsed, elapsed > 0 ? total / elapsed : 0.0);

    if (std::string(argv[1]) == "--bare") {
      if (retires < 3 || lastPc < aethercore::l32sim::kRamBase) {
        std::fprintf(stderr, "AETHERCORE_PA32_GSIM_BARE_FAIL no real RV64 instruction retirement\n");
        return 11;
      }
      std::puts("AETHERCORE_PA32_GSIM_BARE_PASS");
    } else {
      std::puts("AETHERCORE_PA32_GSIM_FIRMWARE_PREFIX_OBSERVED_UNQUALIFIED");
    }
    return 0;
  } catch (const std::exception& e) {
    std::fprintf(stderr, "AETHERCORE_PA32_GSIM_TRANSPORT_ERROR %s\n", e.what());
    return 12;
  }
}
