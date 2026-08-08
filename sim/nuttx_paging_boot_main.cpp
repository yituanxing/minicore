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

void driveMemory(VAetherCoreNuttXPagingSimTop& top, const Memory& memory) {
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
    if (argc < 2 || argc > 3)
      throw std::runtime_error("usage: VAetherCoreNuttXPagingSimTop IMAGE.bin [MAX_CYCLES]");

    const std::string image = argv[1];
    const std::uint64_t maxCycles =
        argc == 3 ? std::stoull(argv[2], nullptr, 0) : 5000000ULL;

    VerilatedContext context;
    context.commandArgs(argc, argv);
    VAetherCoreNuttXPagingSimTop top{&context};
    Memory memory;
    memory.load(image);

    std::uint64_t cycles = 0;
    std::uint64_t commits = 0;
    std::string uart;
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
        if (uart.find("nsh>") != std::string::npos) {
          std::cerr << "\nN5C_BOOT_REACHED_NSH cycles=" << cycles
                    << " commits=" << commits << "\n";
          return 0;
        }
      }

      if (top.io_commit_valid) {
        ++commits;
        recentPcs[recentIndex] = static_cast<std::uint64_t>(top.io_commit_pc);
        recentIndex = (recentIndex + 1) % kRecentCommitCount;
        if (recentCount < kRecentCommitCount) ++recentCount;

        if (top.io_commit_exception) {
          std::cerr << "\nN5C_FIRST_EXCEPTION cycles=" << cycles
                    << " commits=" << commits
                    << " pc=0x" << std::hex
                    << static_cast<std::uint64_t>(top.io_commit_pc)
                    << " inst=0x" << static_cast<std::uint32_t>(top.io_commit_inst)
                    << " cause=0x" << static_cast<std::uint64_t>(top.io_commit_exceptionCause)
                    << " value=0x" << static_cast<std::uint64_t>(top.io_commit_exceptionValue)
                    << std::dec << "\n";
          return 0;
        }
      }
    }

    std::cerr << "\nN5C_PROBE_TIMEOUT cycles=" << cycles
              << " commits=" << commits
              << " last-pc=0x" << std::hex
              << static_cast<std::uint64_t>(top.io_commit_pc)
              << " mtime=0x" << static_cast<std::uint64_t>(top.io_mtime)
              << " mtimecmp=0x" << static_cast<std::uint64_t>(top.io_mtimecmp)
              << std::dec << " timer-irq=" << static_cast<unsigned>(top.io_timerInterrupt)
              << " halted=" << static_cast<unsigned>(top.io_halted)
              << " uart-bytes=" << uart.size() << " recent-pcs=";
    for (std::size_t i = 0; i < recentCount; ++i) {
      const auto slot = (recentIndex + kRecentCommitCount - recentCount + i) % kRecentCommitCount;
      if (i != 0) std::cerr << ',';
      std::cerr << "0x" << std::hex << recentPcs[slot];
    }
    std::cerr << std::dec << "\n";
    return 2;
  } catch (const std::exception& error) {
    std::cerr << "N5C_RUNNER_ERROR: " << error.what() << "\n";
    return 1;
  }
}
