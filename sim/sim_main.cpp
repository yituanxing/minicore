#include "VAetherCoreSimTop.h"
#include "verilated.h"
#include "verilated_vcd_c.h"

#include <cstdint>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr std::uint64_t kRamBase = 0x80000000ULL;
constexpr std::size_t kRamSize = 64ULL * 1024ULL * 1024ULL;
constexpr std::uint64_t kExpectedX3 = 12;
constexpr std::uint64_t kExpectedCommits = 7;
constexpr std::uint32_t kEbreak = 0x00100073U;

struct Options {
  std::string image;
  std::uint64_t maxCycles = 1000000;
  bool trace = false;
  bool commitTrace = false;
};

Options parseOptions(int argc, char** argv) {
  if (argc < 2) {
    throw std::runtime_error(
        "usage: VAetherCoreSimTop <image.bin> [--max-cycles N] [--trace] [--commit-trace]");
  }

  Options options;
  options.image = argv[1];
  for (int i = 2; i < argc; ++i) {
    const std::string arg = argv[i];
    if (arg == "--max-cycles" && i + 1 < argc) {
      options.maxCycles = std::stoull(argv[++i]);
    } else if (arg == "--trace") {
      options.trace = true;
    } else if (arg == "--commit-trace") {
      options.commitTrace = true;
    } else {
      throw std::runtime_error("unknown argument: " + arg);
    }
  }
  return options;
}

class Memory {
 public:
  Memory() : bytes_(kRamSize, 0) {}

  void load(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error("cannot open image: " + path);
    input.read(reinterpret_cast<char*>(bytes_.data()), static_cast<std::streamsize>(bytes_.size()));
  }

  std::uint32_t read32(std::uint64_t address) const {
    const auto offset = checkedOffset(address, 4);
    std::uint32_t value = 0;
    for (unsigned i = 0; i < 4; ++i) value |= std::uint32_t(bytes_[offset + i]) << (8 * i);
    return value;
  }

  std::uint64_t read64(std::uint64_t address) const {
    const auto offset = checkedOffset(address, 8);
    std::uint64_t value = 0;
    for (unsigned i = 0; i < 8; ++i) value |= std::uint64_t(bytes_[offset + i]) << (8 * i);
    return value;
  }

  void writeMasked(std::uint64_t address, std::uint64_t data, std::uint8_t mask) {
    const auto offset = checkedOffset(address, 8);
    for (unsigned i = 0; i < 8; ++i) {
      if ((mask >> i) & 1U) bytes_[offset + i] = static_cast<std::uint8_t>(data >> (8 * i));
    }
  }

  bool contains(std::uint64_t address, std::size_t size = 1) const {
    return address >= kRamBase && address - kRamBase <= bytes_.size() - size;
  }

 private:
  std::size_t checkedOffset(std::uint64_t address, std::size_t size) const {
    if (!contains(address, size)) throw std::runtime_error("memory access outside RAM");
    return static_cast<std::size_t>(address - kRamBase);
  }

  std::vector<std::uint8_t> bytes_;
};

void driveInputs(VAetherCoreSimTop& top, const Memory& memory) {
  const auto iaddr = static_cast<std::uint64_t>(top.io_imemAddr);
  const bool ifault = !memory.contains(iaddr, 4);
  top.io_imemFault = ifault;
  top.io_imemInst = ifault ? kEbreak : memory.read32(iaddr);

  const auto daddr = static_cast<std::uint64_t>(top.io_memAddr);
  const bool dvalid = top.io_memValid;
  const bool dfault = dvalid && !memory.contains(daddr, 8);
  top.io_memReady = 1;
  top.io_memFault = dfault;
  top.io_memRdata = (!dvalid || dfault) ? 0 : memory.read64(daddr);
}

void dumpCommit(const VAetherCoreSimTop& top) {
  std::cout << "commit pc=0x" << std::hex << static_cast<std::uint64_t>(top.io_commit_pc)
            << " inst=0x" << static_cast<std::uint32_t>(top.io_commit_inst)
            << " rd=" << std::dec << static_cast<unsigned>(top.io_commit_rd)
            << " write=" << static_cast<unsigned>(top.io_commit_rdWrite)
            << " data=0x" << std::hex << static_cast<std::uint64_t>(top.io_commit_rdData)
            << (top.io_commit_exception ? " exception" : "") << std::dec << '\n';
}
}  // namespace

int main(int argc, char** argv) {
  try {
    const auto options = parseOptions(argc, argv);
    VerilatedContext context;
    context.commandArgs(argc, argv);

    VAetherCoreSimTop top{&context};
    Memory memory;
    memory.load(options.image);

    VerilatedVcdC* wave = nullptr;
    if (options.trace) {
      context.traceEverOn(true);
      wave = new VerilatedVcdC;
      top.trace(wave, 99);
      wave->open("build/aethercore.vcd");
    }

    std::uint64_t committed = 0;
    std::uint64_t exceptions = 0;
    std::uint64_t cycles = 0;
    bool sawX3 = false;
    bool exitRequested = false;
    std::string uart;

    top.reset = 1;
    top.clock = 0;
    driveInputs(top, memory);
    top.eval();

    for (; cycles < options.maxCycles && !top.io_halted && !exitRequested; ++cycles) {
      // Settle the combinational logic at clock-low. Architectural events are
      // sampled here because they are accepted by the upcoming rising edge.
      top.clock = 0;
      driveInputs(top, memory);
      top.eval();
      driveInputs(top, memory);
      top.eval();

      if (wave) wave->dump(context.time());
      context.timeInc(1);

      if (!top.reset) {
        if (top.io_memValid && top.io_memWrite && top.io_memReady && !top.io_memFault) {
          memory.writeMasked(top.io_memAddr, top.io_memWdata,
                             static_cast<std::uint8_t>(top.io_memWmask));
        }

        if (top.io_uartValid) {
          const char byte = static_cast<char>(top.io_uartByte);
          uart.push_back(byte);
          std::cout << byte << std::flush;
        }

        if (top.io_exitValid) {
          std::cout << "\nEXIT MMIO: " << static_cast<std::uint64_t>(top.io_exitCode) << '\n';
          exitRequested = true;
        }

        if (top.io_commit_valid) {
          ++committed;
          if (top.io_commit_exception) ++exceptions;

          if (top.io_commit_rdWrite && top.io_commit_rd == 3) {
            const auto value = static_cast<std::uint64_t>(top.io_commit_rdData);
            if (value != kExpectedX3) {
              std::cerr << "\nFAIL: x3 committed 0x" << std::hex << value
                        << ", expected 0x" << kExpectedX3 << std::dec << '\n';
              return 3;
            }
            sawX3 = true;
          }

          if (options.commitTrace) dumpCommit(top);
        }
      }

      top.clock = 1;
      driveInputs(top, memory);
      top.eval();
      if (wave) wave->dump(context.time());
      context.timeInc(1);

      // Hold reset for five complete rising edges. Do not sample any
      // architectural event until the next clock-low phase.
      if (cycles == 4) top.reset = 0;
    }

    top.final();
    if (wave) {
      wave->close();
      delete wave;
    }

    if (!top.io_halted && cycles >= options.maxCycles) {
      std::cerr << "FAIL: timeout after " << cycles << " cycles\n";
      return 2;
    }
    if (!top.io_halted && !exitRequested) {
      std::cerr << "FAIL: simulation ended without halt or exit\n";
      return 4;
    }
    if (uart != "A") {
      std::cerr << "\nFAIL: UART output was " << std::quoted(uart) << ", expected \"A\"\n";
      return 5;
    }
    if (!sawX3) {
      std::cerr << "\nFAIL: no architectural commit wrote x3 = 12\n";
      return 6;
    }
    if (committed != kExpectedCommits) {
      std::cerr << "\nFAIL: retired " << committed << " instructions, expected "
                << kExpectedCommits << '\n';
      return 7;
    }
    if (exceptions != 1) {
      std::cerr << "\nFAIL: observed " << exceptions << " exceptions, expected one final ebreak\n";
      return 8;
    }

    std::cout << "\nPASS: halted after " << cycles << " cycles, " << committed
              << " committed instructions, x3=12, UART=\"A\"\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "ERROR: " << error.what() << '\n';
    return 1;
  }
}
