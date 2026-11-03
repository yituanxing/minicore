// Exactly one PA32 virtual FPGA, identical all-zero external transport stimulus.
// This is a simulator-engine microbenchmark; no OpenSBI/Linux claim.
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <stdexcept>
#include <string>

#ifdef AETHERCORE_USE_GSIM
#include "pa32_gsim_port_adapter.h"
#else
#include "verilated.h"
#include "VAetherCoreV2OpenSbiRV64SimTop.h"
#endif

struct ArchitecturalState {
  std::uint64_t mtime = 0, commits = 0, exceptions = 0, interrupts = 0;
  std::uint64_t hash = 14695981039346656037ULL;
};
static void addCommit(ArchitecturalState& state,
                      bool valid, bool exception, bool interrupt,
                      std::uint64_t pc, std::uint32_t inst) {
  if (valid) {
    ++state.commits;
    if (exception) ++state.exceptions;
    state.hash ^= pc;
    state.hash *= 1099511628211ULL;
    state.hash ^= inst;
    state.hash *= 1099511628211ULL;
  }
  if (interrupt) ++state.interrupts;
}
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  try {
    const std::uint64_t n = std::stoull(argv[1]);
    if (n < 1000 || n > 100000000ULL) return 3;
    ArchitecturalState state;
#ifdef AETHERCORE_USE_GSIM
    using aethercore::gsim::Model;
    auto dut = std::make_unique<Model>();
    aethercore::gsim::Ports ports(*dut);
    ports.clock(true);
    ports.rx(false, 0);
    ports.respond({});
    ports.reset(true);
    for (unsigned i = 0; i < 32; ++i) dut->step();
    ports.reset(false);
    auto start = std::chrono::steady_clock::now();
    for (std::uint64_t i = 0; i < n; ++i) {
      dut->step();
      auto c = ports.commit();
      addCommit(state,c.valid,c.exception,c.interrupt,c.pc,c.inst);
    }
    auto end = std::chrono::steady_clock::now();
    state.mtime = ports.mtime();
    const double seconds = std::chrono::duration<double>(end-start).count();
    std::printf("AETHERCORE_PA32_ENGINE_RESULT engine=gsim guest_cycles=%llu mtime=%llu commits=%llu exceptions=%llu interrupts=%llu digest=0x%llx host_seconds=%.6f host_cps=%.2f\n",
        static_cast<unsigned long long>(n),
        static_cast<unsigned long long>(state.mtime),
        static_cast<unsigned long long>(state.commits),
        static_cast<unsigned long long>(state.exceptions),
        static_cast<unsigned long long>(state.interrupts),
        static_cast<unsigned long long>(state.hash),seconds,n/seconds);
#else
    VerilatedContext context;
    auto dut = std::make_unique<VAetherCoreV2OpenSbiRV64SimTop>(&context);
    dut->io_imemInst=0;
    dut->io_imemFault=0;
    dut->io_memReady=1;
    dut->io_memRdata=0;
    dut->io_memFault=0;
    dut->io_ptwReady=1;
    dut->io_ptwRdata=0;
    dut->io_ptwFault=0;
    dut->io_rxValid=0;
    dut->io_rxByte=0;
    auto tick = [&]() {
      dut->clock=0;
      dut->eval();
      dut->eval();
      dut->clock=1;
      dut->eval();
      context.timeInc(1);
    };
    dut->reset=1;
    for (unsigned i = 0; i < 32; ++i) tick();
    dut->reset=0;
    const auto start=std::chrono::steady_clock::now();
    for (std::uint64_t i=0;i<n;++i) {
      tick();
      addCommit(state,dut->io_commit_valid,dut->io_commit_exception,
                dut->io_commit_interrupt,dut->io_commit_pc,dut->io_commit_inst);
    }
    const auto end=std::chrono::steady_clock::now();
    state.mtime=dut->io_mtime;
    const double seconds=std::chrono::duration<double>(end-start).count();
    std::printf("AETHERCORE_PA32_ENGINE_RESULT engine=verilator-5.054 guest_cycles=%llu mtime=%llu commits=%llu exceptions=%llu interrupts=%llu digest=0x%llx host_seconds=%.6f host_cps=%.2f\n",
        static_cast<unsigned long long>(n),
        static_cast<unsigned long long>(state.mtime),
        static_cast<unsigned long long>(state.commits),
        static_cast<unsigned long long>(state.exceptions),
        static_cast<unsigned long long>(state.interrupts),
        static_cast<unsigned long long>(state.hash),seconds,n/seconds);
#endif
    return 0;
  } catch (const std::exception& ex) {
    std::fprintf(stderr,"AETHERCORE_PA32_ENGINE_EXCEPTION %s\n",ex.what());
    return 12;
  }
}
