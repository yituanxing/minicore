// PA32 canonical Verilator counterpart to the GSIM same-RAM bare probe.
// Same 256MB Memory, same 4 RV64I instruction sequence, reset and cycle count.
#include "verilated.h"
#include "VAetherCoreV2OpenSbiRV64SimTop.h"
#include "l32_opensbi_runtime.h"
#include "pa32_firmware_milestone.h"

#include <chrono>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <memory>
#include <string>

using aethercore::l32sim::Memory;
int main(int argc, char** argv) {
  if (argc != 2 && argc != 3 && argc != 4) return 2;
  try {
    const bool bare = argc == 2;
    const bool requireLinux = argc == 4 && std::string(argv[3]) == "--linux";
    if (argc == 4 && !requireLinux) return 2;
    const bool memoryProbe = argc == 3 && std::string(argv[1]) == "--memory";
    const auto guestCycles = std::stoull(argv[bare ? 1 : 2]);
    if (guestCycles < 1 || guestCycles > 1000000000ULL) return 3;
    Memory memory;
    constexpr std::uint32_t program[] = {
        0x00100093U, 0x00208113U, 0x00310193U, 0x0000006fU};
    if (bare) {
      for (unsigned i = 0; i < 4; ++i)
        memory.write32Masked(aethercore::l32sim::kRamBase + i*4,
                             program[i], 0xFU);
    } else if (memoryProbe) {
      constexpr std::uint32_t atomicProgram[] = {
          0x00000097U, 0x02a00113U, 0x08008393U, 0x0023a023U,
          0x0003a183U, 0x00118213U, 0x1003a2afU, 0x1843a32fU,
          0x0003a403U, 0x0023a4afU, 0x0003a503U, 0x0000006fU};
      for (std::size_t i = 0; i < sizeof(atomicProgram)/sizeof(atomicProgram[0]); ++i)
        memory.write32Masked(aethercore::l32sim::kRamBase + i*4,
                             atomicProgram[i], 0xFU);
    } else {
      memory.loadAtBase(argv[1]);
    }

    VerilatedContext context;
    auto dut = std::make_unique<VAetherCoreV2OpenSbiRV64SimTop>(&context);
    aethercore::l32sim::initialize(*dut, memory);
    std::uint64_t retires = 0, exceptions = 0, interrupts = 0;
    std::uint64_t digest = 14695981039346656037ULL;
    aethercore::pa32sim::FirmwareMilestone firmware;
    std::uint64_t lastPc = 0;
    std::uint64_t executedCycles = 0;
    const auto start=std::chrono::steady_clock::now();

    for (std::uint64_t cycle = 0; cycle < guestCycles + 32; ++cycle) {
      const bool reset = cycle < 32;
      dut->reset = reset;
      aethercore::l32sim::step(*dut, context, memory, false, 0);
      if (reset) continue;
      ++executedCycles;
      firmware.observeUart(dut->io_uartValid, dut->io_uartByte, executedCycles);
      firmware.observeInterrupt(dut->io_commit_interrupt,
                                dut->io_commit_interruptCause);
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
      if ((cycle - 31) % 100000 == 0) {
        std::printf("AETHERCORE_PA32_VERILATOR_CHECKPOINT cycle=%llu retires=%llu"
                    " exceptions=%llu interrupts=%llu mtime=%llu"
                    " last_pc=0x%llx commit_digest=0x%llx\n",
                    static_cast<unsigned long long>(cycle - 31),
                    static_cast<unsigned long long>(retires),
                    static_cast<unsigned long long>(exceptions),
                    static_cast<unsigned long long>(interrupts),
                    static_cast<unsigned long long>(dut->io_mtime),
                    static_cast<unsigned long long>(lastPc),
                    static_cast<unsigned long long>(digest));
      }
      if (requireLinux && firmware.linuxPassed()) break;
    }

    const double sec=std::chrono::duration<double>(
        std::chrono::steady_clock::now()-start).count();
    const std::uint64_t ramDigest = memory.stateDigest();
    std::printf("AETHERCORE_PA32_VERILATOR_PREFIX guest_cycles=%llu retires=%llu exceptions=%llu interrupts=%llu mtime=%llu last_pc=0x%llx commit_digest=0x%llx host_seconds=%.6f host_cps=%.1f ram_digest=0x%llx uart_bytes=%llu uart_digest=0x%llx opensbi_banner=%u linux_banner=%u milestone_cycle=%llu stip=%llu seip=%llu\n",
        static_cast<unsigned long long>(executedCycles),
        static_cast<unsigned long long>(retires),
        static_cast<unsigned long long>(exceptions),
        static_cast<unsigned long long>(interrupts),
        static_cast<unsigned long long>(dut->io_mtime),
        static_cast<unsigned long long>(lastPc),
        static_cast<unsigned long long>(digest),
        sec, sec>0 ? executedCycles/sec : 0.0,
        static_cast<unsigned long long>(ramDigest),
        static_cast<unsigned long long>(firmware.uartBytes),
        static_cast<unsigned long long>(firmware.uartDigest),
        static_cast<unsigned>(firmware.opensbiBanner),
        static_cast<unsigned>(firmware.linuxBanner),
        static_cast<unsigned long long>(firmware.milestoneCycle),
        static_cast<unsigned long long>(firmware.stip),
        static_cast<unsigned long long>(firmware.seip));
    if (requireLinux) {
      if (!firmware.linuxPassed()) {
        std::fprintf(stderr, "AETHERCORE_PA32_VERILATOR_LINUX_MILESTONE_FAIL cycles=%llu marker=%llu stip=%llu seip=%llu\n",
                     static_cast<unsigned long long>(executedCycles),
                     static_cast<unsigned long long>(firmware.milestoneCycle),
                     static_cast<unsigned long long>(firmware.stip),
                     static_cast<unsigned long long>(firmware.seip));
        return 14;
      }
      std::printf("AETHERCORE_PA32_VERILATOR_LINUX_MILESTONE_PASS cycles=%llu marker=RV64_USER_UART_IRQ_OK stip=%llu seip=%llu\n",
                  static_cast<unsigned long long>(executedCycles),
                  static_cast<unsigned long long>(firmware.stip),
                  static_cast<unsigned long long>(firmware.seip));
    }
    if (memoryProbe) {
      const std::uint32_t finalWord = memory.read32(aethercore::l32sim::kRamBase + 128);
      if (finalWord != 85 || exceptions != 0) {
        std::fprintf(stderr,
            "AETHERCORE_PA32_VERILATOR_MEMORY_FAIL final_word=%u exceptions=%llu\n",
            finalWord, static_cast<unsigned long long>(exceptions));
        return 13;
      }
      std::printf("AETHERCORE_PA32_VERILATOR_MEMORY_PASS final_word=%u\n", finalWord);
    }
    return 0;
  } catch (const std::exception& e) {
    std::fprintf(stderr,"AETHERCORE_PA32_VERILATOR_REFERENCE_FAIL %s\n",e.what());
    return 12;
  }
}
