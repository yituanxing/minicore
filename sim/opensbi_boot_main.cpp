#include "VAetherCoreOpenSbiSimTop.h"
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
constexpr const char* kDefaultMilestone = "Test payload running";

class Memory {
 public:
  Memory() : bytes_(kRamSize, 0) {}

  void loadAtBase(const std::string& path) {
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

void driveMemory(VAetherCoreOpenSbiSimTop& top, const Memory& memory) {
  const auto iaddr = static_cast<std::uint64_t>(top.io_imemAddr);
  const bool ifault = !memory.contains(iaddr, 4);
  top.io_imemFault = ifault;
  top.io_imemInst = ifault ? kEbreak : memory.read32(iaddr);

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
}
}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc < 2 || argc > 4)
      throw std::runtime_error(
          "usage: VAetherCoreOpenSbiSimTop FW_PAYLOAD.bin [MAX_CYCLES] [UART_MILESTONE]");

    const std::string image = argv[1];
    const std::uint64_t maxCycles =
        argc >= 3 ? std::stoull(argv[2], nullptr, 0) : 10000000ULL;
    const std::string milestone = argc >= 4 ? argv[3] : kDefaultMilestone;

    VerilatedContext context;
    context.commandArgs(argc, argv);
    VAetherCoreOpenSbiSimTop top{&context};
    Memory memory;
    memory.loadAtBase(image);

    std::uint64_t cycles = 0;
    std::uint64_t commits = 0;
    std::uint64_t exceptions = 0;
    std::uint64_t interrupts = 0;
    std::uint64_t lastExceptionCause = 0;
    std::uint64_t lastExceptionValue = 0;
    std::uint64_t lastExceptionPc = 0;
    std::string uart;
    bool sawOpenSbiBanner = false;
    std::array<std::uint64_t, kRecentCommitCount> recentPcs{};
    std::size_t recentCount = 0;
    std::size_t recentIndex = 0;

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
        if (!sawOpenSbiBanner && uart.find("OpenSBI v1.6") != std::string::npos) {
          sawOpenSbiBanner = true;
          std::cerr << "\nL32_OPENSBI_BANNER cycles=" << cycles
                    << " commits=" << commits << "\n";
        }
        if (uart.find(milestone) != std::string::npos) {
          if (milestone == kDefaultMilestone) {
            std::cerr << "\nL32_OPENSBI_TEST_PAYLOAD_PASS cycles=" << cycles
                      << " commits=" << commits
                      << " exceptions=" << exceptions
                      << " interrupts=" << interrupts
                      << " banner=" << (sawOpenSbiBanner ? 1 : 0) << "\n";
          }
          std::cerr << "\nL32_RUNTIME_MILESTONE_PASS cycles=" << cycles
                    << " commits=" << commits
                    << " exceptions=" << exceptions
                    << " interrupts=" << interrupts
                    << " banner=" << (sawOpenSbiBanner ? 1 : 0)
                    << " marker=" << milestone << "\n";
          return sawOpenSbiBanner ? 0 : 5;
        }
      }

      if (top.io_commit_valid) {
        ++commits;
        recentPcs[recentIndex] = static_cast<std::uint64_t>(top.io_commit_pc);
        recentIndex = (recentIndex + 1) % kRecentCommitCount;
        if (recentCount < kRecentCommitCount) ++recentCount;

        if (top.io_commit_exception) {
          ++exceptions;
          lastExceptionPc = static_cast<std::uint64_t>(top.io_commit_pc);
          lastExceptionCause = static_cast<std::uint64_t>(top.io_commit_exceptionCause);
          lastExceptionValue = static_cast<std::uint64_t>(top.io_commit_exceptionValue);
          if (exceptions == 1) {
            std::cerr << "\nL32_FIRST_EXCEPTION cycles=" << cycles
                      << " commits=" << commits
                      << " pc=0x" << std::hex << lastExceptionPc
                      << " inst=0x" << static_cast<std::uint32_t>(top.io_commit_inst)
                      << " cause=0x" << lastExceptionCause
                      << " value=0x" << lastExceptionValue << std::dec << "\n";
          }
        }
        if (top.io_commit_interrupt) ++interrupts;
      }
    }

    std::cerr << "\nL32_OPENSBI_TIMEOUT cycles=" << cycles
              << " commits=" << commits
              << " uart-bytes=" << uart.size()
              << " banner=" << (sawOpenSbiBanner ? 1 : 0)
              << " exceptions=" << exceptions
              << " interrupts=" << interrupts
              << " mtime=0x" << std::hex << static_cast<std::uint64_t>(top.io_mtime)
              << " mtimecmp=0x" << static_cast<std::uint64_t>(top.io_mtimecmp)
              << " last-exception-pc=0x" << lastExceptionPc
              << " last-exception-cause=0x" << lastExceptionCause
              << " last-exception-value=0x" << lastExceptionValue
              << " recent-pcs=";
    for (std::size_t i = 0; i < recentCount; ++i) {
      const auto slot = (recentIndex + kRecentCommitCount - recentCount + i) % kRecentCommitCount;
      if (i != 0) std::cerr << ',';
      std::cerr << "0x" << recentPcs[slot];
    }
    std::cerr << std::dec << "\n";
    return 2;
  } catch (const std::exception& error) {
    std::cerr << "L32_RUNNER_ERROR: " << error.what() << "\n";
    return 1;
  }
}
