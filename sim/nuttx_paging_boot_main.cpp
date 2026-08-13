#include "VAetherCoreNuttXPagingSimTop.h"
#include "verilated.h"

#include <array>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr std::uint64_t kRamBase = 0x80000000ULL;
constexpr std::size_t kRamSize = 256ULL * 1024ULL * 1024ULL;
constexpr std::uint32_t kEbreak = 0x00100073U;
constexpr std::size_t kRecentCommitCount = 16;
constexpr std::size_t kRecentStoreCount = 8;
constexpr std::uint64_t kSupervisorTimerInterruptCode = 5ULL;
constexpr std::uint64_t kUserEnvironmentCall = 8ULL;
constexpr std::uint64_t kInstructionPageFault = 12ULL;
constexpr std::uint64_t kLoadPageFault = 13ULL;
constexpr std::uint64_t kStorePageFault = 15ULL;
constexpr std::uint64_t kMaxSamePagingFaultRepeats = 8ULL;

struct StoreTrace {
  std::uint64_t address = 0;
  std::uint8_t mask = 0;
};

class Memory {
 public:
  Memory() : bytes_(kRamSize, 0) {}

  void load(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error("cannot open image: " + path);
    input.read(reinterpret_cast<char*>(bytes_.data()),
               static_cast<std::streamsize>(bytes_.size()));
    if (input.bad()) throw std::runtime_error("failed while reading image: " + path);
  }

  bool contains(std::uint64_t address, std::size_t size = 1) const {
    return address >= kRamBase && address - kRamBase <= bytes_.size() - size;
  }

  std::uint32_t read32(std::uint64_t address) const {
    const auto offset = checkedOffset(address, 4);
    std::uint32_t value = 0;
    for (unsigned i = 0; i < 4; ++i)
      value |= std::uint32_t(bytes_[offset + i]) << (8 * i);
    return value;
  }

  void write32Masked(std::uint64_t address, std::uint32_t data, std::uint8_t mask) {
    const auto offset = checkedOffset(address, 4);
    for (unsigned i = 0; i < 4; ++i) {
      if ((mask >> i) & 1U)
        bytes_[offset + i] = static_cast<std::uint8_t>(data >> (8 * i));
    }
  }

 private:
  std::size_t checkedOffset(std::uint64_t address, std::size_t size) const {
    if (!contains(address, size)) throw std::runtime_error("memory access outside RAM");
    return static_cast<std::size_t>(address - kRamBase);
  }

  std::vector<std::uint8_t> bytes_;
};

bool isExpectedPagingFault(std::uint64_t cause) {
  return cause == kInstructionPageFault || cause == kLoadPageFault ||
         cause == kStorePageFault;
}

void driveMemory(VAetherCoreNuttXPagingSimTop& top, const Memory& memory) {
  const bool ivalid = top.io_imemValid;
  const auto iaddr = static_cast<std::uint64_t>(top.io_imemAddr);
  const bool ifault = ivalid && !memory.contains(iaddr, 4);
  top.io_imemFault = ifault;
  top.io_imemInst = (!ivalid || ifault) ? 0 : memory.read32(iaddr);

  const bool dvalid = top.io_memValid;
  const auto daddr = static_cast<std::uint64_t>(top.io_memAddr);
  const bool dfault = dvalid && !memory.contains(daddr, 4);
  top.io_memReady = true;
  top.io_memFault = dfault;
  top.io_memRdata = (!dvalid || dfault) ? 0 : memory.read32(daddr);

  const bool ptwValid = top.io_ptwValid;
  const auto ptwAddr = static_cast<std::uint64_t>(top.io_ptwAddr);
  const bool ptwFault = ptwValid && !memory.contains(ptwAddr, 4);
  top.io_ptwReady = true;
  top.io_ptwFault = ptwFault;
  top.io_ptwRdata = (!ptwValid || ptwFault) ? 0 : memory.read32(ptwAddr);

  // N5 currently validates boot-time PLIC MMIO only. Keep the QEMU UART RX
  // source quiescent until Supervisor external interrupt delegation is frozen.
  top.io_rxValid = 0;
  top.io_rxByte = 0;
}
}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc < 2 || argc > 3)
      throw std::runtime_error("usage: VAetherCoreNuttXPagingSimTop IMAGE.bin [MAX_CYCLES]");

    const std::string image = argv[1];
    const std::uint64_t maxCycles =
        argc == 3 ? std::stoull(argv[2], nullptr, 0) : 50000000ULL;

    VerilatedContext context;
    context.commandArgs(argc, argv);
    VAetherCoreNuttXPagingSimTop top{&context};
    Memory memory;
    memory.load(image);

    std::uint64_t cycles = 0;
    std::uint64_t commits = 0;
    std::uint64_t interrupts = 0;
    std::uint64_t supervisorTimerInterrupts = 0;
    std::uint64_t lastInterruptCause = 0;
    std::uint64_t lastInterruptPc = 0;
    std::uint64_t exceptions = 0;
    std::uint64_t pagingFaults = 0;
    std::uint64_t userEcalls = 0;
    std::uint64_t samePagingFaultRepeats = 0;
    std::uint64_t lastPagingFaultCause = ~0ULL;
    std::uint64_t lastPagingFaultPc = ~0ULL;
    std::uint64_t lastPagingFaultValue = ~0ULL;
    std::string uart;
    std::array<std::uint64_t, kRecentCommitCount> recentPcs{};
    std::size_t recentCount = 0;
    std::size_t recentIndex = 0;
    std::array<StoreTrace, kRecentStoreCount> recentStores{};
    std::size_t recentStoreCount = 0;
    std::size_t recentStoreIndex = 0;
    std::uint64_t committedStores = 0;

    top.reset = 1;
    top.clock = 0;
    driveMemory(top, memory);
    top.eval();

    for (; cycles < maxCycles; ++cycles) {
      top.clock = 0;
      driveMemory(top, memory);
      top.eval();
      driveMemory(top, memory);
      top.eval();

      if (!top.reset && top.io_memValid && top.io_memWrite && top.io_memReady &&
          !top.io_memFault) {
        memory.write32Masked(
            static_cast<std::uint64_t>(top.io_memAddr),
            static_cast<std::uint32_t>(top.io_memWdata),
            static_cast<std::uint8_t>(top.io_memWmask));
      }

      top.clock = 1;
      top.eval();
      context.timeInc(1);

      if (cycles == 3) top.reset = 0;
      if (top.reset) continue;

      if (top.io_uartValid) {
        const char byte = static_cast<char>(top.io_uartByte);
        uart.push_back(byte);
        std::cout << byte << std::flush;
        if (uart.find("nsh>") != std::string::npos) {
          std::cerr << "\nN5C_BOOT_REACHED_NSH cycles=" << cycles
                    << " commits=" << commits
                    << " exceptions=" << exceptions
                    << " paging-faults=" << pagingFaults
                    << " user-ecalls=" << userEcalls
                    << " interrupts=" << interrupts
                    << " supervisor-timer-interrupts=" << supervisorTimerInterrupts
                    << "\n";
          return 0;
        }
      }

      if (top.io_commit_valid) {
        ++commits;
        recentPcs[recentIndex] = static_cast<std::uint64_t>(top.io_commit_pc);
        recentIndex = (recentIndex + 1) % kRecentCommitCount;
        if (recentCount < kRecentCommitCount) ++recentCount;

        if (top.io_commit_memValid && top.io_commit_memWrite) {
          ++committedStores;
          recentStores[recentStoreIndex] = StoreTrace{
              static_cast<std::uint64_t>(top.io_commit_memAddr),
              static_cast<std::uint8_t>(top.io_commit_memWmask)};
          recentStoreIndex = (recentStoreIndex + 1) % kRecentStoreCount;
          if (recentStoreCount < kRecentStoreCount) ++recentStoreCount;
        }

        if (top.io_commit_interrupt) {
          ++interrupts;
          lastInterruptCause = static_cast<std::uint64_t>(top.io_commit_interruptCause);
          lastInterruptPc = static_cast<std::uint64_t>(top.io_commit_interruptPc);
          const auto code = lastInterruptCause & 0x7fffffffULL;
          if (code == kSupervisorTimerInterruptCode) ++supervisorTimerInterrupts;
        }

        if (top.io_commit_exception) {
          ++exceptions;
          const auto cause = static_cast<std::uint64_t>(top.io_commit_exceptionCause);
          const auto pc = static_cast<std::uint64_t>(top.io_commit_pc);
          const auto value = static_cast<std::uint64_t>(top.io_commit_exceptionValue);

          if (isExpectedPagingFault(cause)) {
            ++pagingFaults;
            if (pagingFaults == 1) {
              std::cerr << "\nN5C_FIRST_EXPECTED_PAGE_FAULT cycles=" << cycles
                        << " commits=" << commits
                        << " pc=0x" << std::hex << pc
                        << " inst=0x" << static_cast<std::uint32_t>(top.io_commit_inst)
                        << " cause=0x" << cause
                        << " value=0x" << value << std::dec << "\n";
            }

            if (cause == lastPagingFaultCause && pc == lastPagingFaultPc &&
                value == lastPagingFaultValue) {
              ++samePagingFaultRepeats;
            } else {
              lastPagingFaultCause = cause;
              lastPagingFaultPc = pc;
              lastPagingFaultValue = value;
              samePagingFaultRepeats = 1;
            }

            if (samePagingFaultRepeats > kMaxSamePagingFaultRepeats) {
              std::cerr << "\nN5C_PAGE_FAULT_LIVELOCK cycles=" << cycles
                        << " commits=" << commits
                        << " repeats=" << samePagingFaultRepeats
                        << " pc=0x" << std::hex << pc
                        << " cause=0x" << cause
                        << " value=0x" << value << std::dec
                        << " paging-faults=" << pagingFaults
                        << " supervisor-timer-interrupts=" << supervisorTimerInterrupts
                        << "\n";
              return 4;
            }
          } else if (cause == kUserEnvironmentCall) {
            ++userEcalls;
            if (userEcalls == 1) {
              std::cerr << "\nN5C_FIRST_EXPECTED_USER_ECALL cycles=" << cycles
                        << " commits=" << commits
                        << " pc=0x" << std::hex << pc
                        << " inst=0x" << static_cast<std::uint32_t>(top.io_commit_inst)
                        << std::dec << "\n";
            }
          } else {
            std::cerr << "\nN5C_FIRST_UNEXPECTED_EXCEPTION cycles=" << cycles
                      << " commits=" << commits
                      << " pc=0x" << std::hex << pc
                      << " inst=0x" << static_cast<std::uint32_t>(top.io_commit_inst)
                      << " cause=0x" << cause
                      << " value=0x" << value << std::dec
                      << " exceptions=" << exceptions
                      << " paging-faults=" << pagingFaults
                      << " user-ecalls=" << userEcalls
                      << " interrupts=" << interrupts
                      << " supervisor-timer-interrupts=" << supervisorTimerInterrupts
                      << "\n";
            return 3;
          }
        }
      }
    }

    std::cerr << "\nN5C_PROBE_TIMEOUT cycles=" << cycles
              << " commits=" << commits
              << " last-pc=0x" << std::hex
              << static_cast<std::uint64_t>(top.io_commit_pc)
              << " mtime=0x" << static_cast<std::uint64_t>(top.io_mtime)
              << " machine-mtimecmp=0x" << static_cast<std::uint64_t>(top.io_mtimecmp)
              << std::dec
              << " machine-timer-irq=" << static_cast<unsigned>(top.io_timerInterrupt)
              << " external-irq=" << static_cast<unsigned>(top.io_externalInterrupt)
              << " halted=" << static_cast<unsigned>(top.io_halted)
              << " uart-bytes=" << uart.size()
              << " exceptions=" << exceptions
              << " paging-faults=" << pagingFaults
              << " user-ecalls=" << userEcalls
              << " interrupts=" << interrupts
              << " supervisor-timer-interrupts=" << supervisorTimerInterrupts
              << " committed-stores=" << committedStores;
    if (interrupts != 0) {
      std::cerr << " last-interrupt-cause=0x" << std::hex << lastInterruptCause
                << " last-interrupt-pc=0x" << lastInterruptPc << std::dec;
    }
    std::cerr << " recent-pcs=";
    for (std::size_t i = 0; i < recentCount; ++i) {
      const auto slot = (recentIndex + kRecentCommitCount - recentCount + i) % kRecentCommitCount;
      if (i != 0) std::cerr << ',';
      std::cerr << "0x" << std::hex << recentPcs[slot];
    }
    std::cerr << " recent-stores=";
    for (std::size_t i = 0; i < recentStoreCount; ++i) {
      const auto slot =
          (recentStoreIndex + kRecentStoreCount - recentStoreCount + i) % kRecentStoreCount;
      if (i != 0) std::cerr << ',';
      std::cerr << "0x" << std::hex << recentStores[slot].address
                << "/0x" << static_cast<unsigned>(recentStores[slot].mask);
    }
    std::cerr << std::dec << "\n";
    return 2;
  } catch (const std::exception& error) {
    std::cerr << "N5C_RUNNER_ERROR: " << error.what() << "\n";
    return 1;
  }
}
