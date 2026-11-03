// PA32 canonical Verilator counterpart to the GSIM same-RAM bare probe.
// Same 256MB Memory, same 4 RV64I instruction sequence, reset and cycle count.
#include "verilated.h"
#include "VAetherCoreV2OpenSbiRV64SimTop.h"
#include "l32_opensbi_runtime.h"

#include <chrono>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <memory>
#include <string>

using aethercore::l32sim::Memory;
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  try {
    const auto guestCycles = std::stoull(argv[1]);
    if (guestCycles < 1 || guestCycles > 1000000000ULL) return 3;
    Memory memory;
    constexpr std::uint32_t program[] = {
        0x00100093U, 0x00208113U, 0x00310193U, 0x0000006fU};
    for (unsigned i = 0; i < 4; ++i)
      memory.write32Masked(aethercore::l32sim::kRamBase + i*4,
                           program[i], 0xFU);

    VerilatedContext context;
    auto dut = std::make_unique<VAetherCoreV2OpenSbiRV64SimTop>(&context);
    aethercore::l32sim::initialize(*dut, memory);
    std::uint64_t retires = 0, exceptions = 0, interrupts = 0;
    std::uint64_t digest = 14695981039346656037ULL;
    std::uint64_t lastPc = 0;
    const auto start=std::chrono::steady_clock::now();

    for (std::uint64_t cycle = 0; cycle < guestCycles + 32; ++cycle) {
      const bool reset = cycle < 32;
      dut->reset = reset;
      aethercore::l32sim::step(*dut, context, memory, false, 0);
      if (reset) continue;
      if (dut->io_commit_valid) {
        ++retires;
        lastPc = dut->io_commit_pc;
        digest ^= lastPc;
        digest *= 1099511628211ULL;
        digest ^= dut->io_commit_inst;
        digest *= 1099511628211ULL;
        if (retires <= 16)
          std::printf("AETHERCORE_PA32_VERILATOR_COMMIT index=%llu pc=0x%llx inst=0x%08x exception=%u cause=0x%llx\n",
                      static_cast<unsigned long long>(retires),
                      static_cast<unsigned long long>(dut->io_commit_pc),
                      dut->io_commit_inst,
                      static_cast<unsigned>(dut->io_commit_exception),
                      static_cast<unsigned long long>(dut->io_commit_exceptionCause));
        if (dut->io_commit_exception) ++exceptions;
      }
      if (dut->io_commit_interrupt) ++interrupts;
    }

    const double sec=std::chrono::duration<double>(
        std::chrono::steady_clock::now()-start).count();
    std::printf("AETHERCORE_PA32_VERILATOR_PREFIX guest_cycles=%llu retires=%llu exceptions=%llu interrupts=%llu mtime=%llu last_pc=0x%llx commit_digest=0x%llx host_seconds=%.6f host_cps=%.1f\n",
        static_cast<unsigned long long>(guestCycles),
        static_cast<unsigned long long>(retires),
        static_cast<unsigned long long>(exceptions),
        static_cast<unsigned long long>(interrupts),
        static_cast<unsigned long long>(dut->io_mtime),
        static_cast<unsigned long long>(lastPc),
        static_cast<unsigned long long>(digest),
        sec, sec>0 ? guestCycles/sec : 0.0);
    return 0;
  } catch (const std::exception& e) {
    std::fprintf(stderr,"AETHERCORE_PA32_VERILATOR_REFERENCE_FAIL %s\n",e.what());
    return 12;
  }
}
