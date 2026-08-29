#include "VAetherCoreSimTop.h"
#include "nemu_difftest.h"
#include "verilated.h"
#include "verilated_vcd_c.h"

#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr std::uint64_t kRamBase = 0x80000000ULL;
constexpr std::size_t kRamSize = 64ULL * 1024ULL * 1024ULL;
constexpr std::uint64_t kExpectedX3 = 12;
constexpr std::uint64_t kExpectedCommits = 7;
constexpr std::uint32_t kEbreak = 0x00100073U;

std::uint64_t parseInteger(const char* text) {
  std::size_t consumed = 0;
  const std::string value{text};
  const auto parsed = std::stoull(value, &consumed, 0);
  if (consumed != value.size()) throw std::runtime_error("invalid integer: " + value);
  return parsed;
}

struct Options {
  std::string image;
  std::uint64_t maxCycles = 1000000;
  std::uint64_t stallPeriod = 0;
  bool trace = false;
  bool commitTrace = false;
  bool selfCheckExit = false;
  bool requirePtw = false;
  std::optional<std::size_t> expectedPtwBytes;
  std::vector<std::uint8_t> rxBytes;
  std::uint64_t rxStartCycle = 0;
  std::uint64_t rxGapCycles = 1;
  std::optional<std::string> difftestSharedObject;
  std::optional<std::uint64_t> expectedExceptionPc;
  std::optional<std::uint32_t> expectedExceptionInst;
  std::optional<std::uint64_t> expectedCommits;
  std::optional<unsigned> forbiddenRd;
  std::optional<std::uint64_t> expectedMemoryAddress;
  std::optional<std::uint64_t> expectedMemoryValue;

  bool faultCheck() const { return expectedExceptionPc.has_value(); }
};

Options parseOptions(int argc, char** argv) {
  if (argc < 2) {
    throw std::runtime_error(
        "usage: VAetherCoreSimTop <image.bin> [--max-cycles N] [--stall-period N] "
        "[--trace] [--commit-trace] [--self-check-exit] "
        "[--require-ptw] [--expect-ptw-bytes 4|8] "
        "[--rx-byte N ... --rx-start-cycle N --rx-gap-cycles N] [--difftest NEMU_SO] "
        "[--expect-exception-pc N --expect-exception-inst N --expected-commits N] "
        "[--forbid-rd N] [--expect-memory64 ADDRESS VALUE]");
  }

  Options options;
  options.image = argv[1];
  for (int i = 2; i < argc; ++i) {
    const std::string arg = argv[i];
    if (arg == "--max-cycles" && i + 1 < argc) {
      options.maxCycles = parseInteger(argv[++i]);
    } else if (arg == "--stall-period" && i + 1 < argc) {
      options.stallPeriod = parseInteger(argv[++i]);
      if (options.stallPeriod == 1) {
        throw std::runtime_error("--stall-period 1 would block every memory cycle");
      }
    } else if (arg == "--trace") {
      options.trace = true;
    } else if (arg == "--commit-trace") {
      options.commitTrace = true;
    } else if (arg == "--self-check-exit") {
      options.selfCheckExit = true;
    } else if (arg == "--require-ptw") {
      options.requirePtw = true;
    } else if (arg == "--expect-ptw-bytes" && i + 1 < argc) {
      const auto bytes = parseInteger(argv[++i]);
      if (bytes != 4 && bytes != 8) {
        throw std::runtime_error("--expect-ptw-bytes must be 4 or 8");
      }
      options.expectedPtwBytes = static_cast<std::size_t>(bytes);
      options.requirePtw = true;
    } else if (arg == "--rx-byte" && i + 1 < argc) {
      const auto byte = parseInteger(argv[++i]);
      if (byte > 0xffU) throw std::runtime_error("--rx-byte must be in the range 0..255");
      options.rxBytes.push_back(static_cast<std::uint8_t>(byte));
    } else if (arg == "--rx-start-cycle" && i + 1 < argc) {
      options.rxStartCycle = parseInteger(argv[++i]);
    } else if (arg == "--rx-gap-cycles" && i + 1 < argc) {
      options.rxGapCycles = parseInteger(argv[++i]);
      if (options.rxGapCycles == 0) {
        throw std::runtime_error("--rx-gap-cycles must be non-zero");
      }
    } else if (arg == "--difftest" && i + 1 < argc) {
      options.difftestSharedObject = argv[++i];
    } else if (arg == "--expect-exception-pc" && i + 1 < argc) {
      options.expectedExceptionPc = parseInteger(argv[++i]);
    } else if (arg == "--expect-exception-inst" && i + 1 < argc) {
      options.expectedExceptionInst = static_cast<std::uint32_t>(parseInteger(argv[++i]));
    } else if (arg == "--expected-commits" && i + 1 < argc) {
      options.expectedCommits = parseInteger(argv[++i]);
    } else if (arg == "--forbid-rd" && i + 1 < argc) {
      const auto rd = parseInteger(argv[++i]);
      if (rd >= 32) throw std::runtime_error("--forbid-rd must name x0..x31");
      options.forbiddenRd = static_cast<unsigned>(rd);
    } else if (arg == "--expect-memory64" && i + 2 < argc) {
      options.expectedMemoryAddress = parseInteger(argv[++i]);
      options.expectedMemoryValue = parseInteger(argv[++i]);
    } else {
      throw std::runtime_error("unknown or incomplete argument: " + arg);
    }
  }

  if (options.faultCheck()) {
    if (!options.expectedExceptionInst || !options.expectedCommits) {
      throw std::runtime_error(
          "fault checking requires --expect-exception-pc, --expect-exception-inst and --expected-commits");
    }
    if (options.selfCheckExit) {
      throw std::runtime_error("fault checking and --self-check-exit are mutually exclusive");
    }
    if (options.difftestSharedObject) {
      throw std::runtime_error("precise-fault mode is not yet connected to external DiffTest");
    }
  }
  if (options.expectedMemoryAddress.has_value() != options.expectedMemoryValue.has_value()) {
    throw std::runtime_error("--expect-memory64 requires both address and value");
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

  std::uint32_t readInstruction(std::uint64_t address, std::size_t size) const {
    if (size != 2 && size != 4)
      throw std::runtime_error("instruction transaction must be 2 or 4 bytes");
    const auto offset = checkedOffset(address, size);
    std::uint32_t value = 0;
    for (std::size_t i = 0; i < size; ++i)
      value |= std::uint32_t(bytes_[offset + i]) << (8 * i);
    return value;
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

template <typename Top>
constexpr bool hasPtwPort() {
  return requires(Top& top) {
    top.io_ptwValid;
    top.io_ptwAddr;
    top.io_ptwReady;
    top.io_ptwRdata;
    top.io_ptwFault;
  };
}

template <typename Top>
std::size_t ptwPortBytes(const Top& top) {
  if constexpr (hasPtwPort<Top>()) {
    return sizeof(top.io_ptwRdata);
  }
  return 0;
}

template <typename Top>
void drivePtw(Top& top, const Memory& memory, bool memoryReady) {
  if constexpr (hasPtwPort<Top>()) {
    constexpr std::size_t pteBytes = sizeof(top.io_ptwRdata);
    static_assert(pteBytes == 4 || pteBytes == 8,
                  "AetherCore PTW response must carry one 32-bit or 64-bit PTE");
    const bool valid = top.io_ptwValid;
    const auto address = static_cast<std::uint64_t>(top.io_ptwAddr);
    const bool fault = valid && !memory.contains(address, pteBytes);
    top.io_ptwReady = memoryReady;
    top.io_ptwFault = fault;
    if (!valid || fault) {
      top.io_ptwRdata = 0;
    } else if constexpr (pteBytes == 4) {
      top.io_ptwRdata = memory.read32(address);
    } else {
      top.io_ptwRdata = memory.read64(address);
    }
  }
}

template <typename Top>
bool ptwRequestAccepted(const Top& top) {
  if constexpr (hasPtwPort<Top>()) {
    return top.io_ptwValid && top.io_ptwReady && !top.io_ptwFault;
  }
  return false;
}

template <typename Top>
void driveInputs(Top& top, const Memory& memory, bool memoryReady) {
  const bool ivalid = top.io_imemValid;
  const auto iaddr = static_cast<std::uint64_t>(top.io_imemAddr);
  const auto ibytes = static_cast<std::size_t>(top.io_imemBytes);
  const bool invalidInstructionWidth = ibytes != 2 && ibytes != 4;
  const bool ifault = ivalid &&
      (invalidInstructionWidth || !memory.contains(iaddr, ibytes));
  top.io_imemFault = ifault;
  top.io_imemInst =
      (!ivalid || ifault) ? 0 : memory.readInstruction(iaddr, ibytes);

  const auto daddr = static_cast<std::uint64_t>(top.io_memAddr);
  const bool dvalid = top.io_memValid;
  const bool dfault = dvalid && !memory.contains(daddr, 8);
  top.io_memReady = memoryReady;
  top.io_memFault = dfault;
  top.io_memRdata = (!dvalid || dfault) ? 0 : memory.read64(daddr);

  drivePtw(top, memory, memoryReady);
}

template <typename Top>
constexpr bool hasUartRxPort() {
  return requires(Top& top) {
    top.io_rxValid;
    top.io_rxByte;
    top.io_rxReady;
  };
}

template <typename Top>
void driveUartRx(Top& top, bool valid, std::uint8_t byte) {
  if constexpr (hasUartRxPort<Top>()) {
    top.io_rxValid = valid;
    top.io_rxByte = byte;
  } else if (valid) {
    throw std::runtime_error("UART RX injection requested for a top without RX ports");
  }
}

template <typename Top>
bool uartRxReady(const Top& top) {
  if constexpr (hasUartRxPort<Top>()) {
    return top.io_rxReady;
  }
  return false;
}

void dumpCommit(const VAetherCoreSimTop& top) {
  std::cout << "commit pc=0x" << std::hex << static_cast<std::uint64_t>(top.io_commit_pc)
            << " inst=0x" << static_cast<std::uint32_t>(top.io_commit_inst)
            << " raw=0x" << static_cast<std::uint32_t>(top.io_commit_rawInst)
            << " bytes=" << std::dec << static_cast<unsigned>(top.io_commit_instBytes)
            << " rd=" << static_cast<unsigned>(top.io_commit_rd)
            << " write=" << static_cast<unsigned>(top.io_commit_rdWrite)
            << " data=0x" << std::hex << static_cast<std::uint64_t>(top.io_commit_rdData)
            << (top.io_commit_exception ? " exception" : "") << std::dec << '\n';
}

DifftestCommit makeDifftestCommit(const VAetherCoreSimTop& top) {
  DifftestCommit commit;
  commit.pc = static_cast<std::uint64_t>(top.io_commit_pc);
  commit.inst = static_cast<std::uint32_t>(top.io_commit_inst);
  commit.rawInst = static_cast<std::uint32_t>(top.io_commit_rawInst);
  commit.instBytes = static_cast<std::uint8_t>(top.io_commit_instBytes);
  commit.rd = static_cast<std::uint8_t>(top.io_commit_rd);
  commit.rdWrite = top.io_commit_rdWrite;
  commit.rdData = static_cast<std::uint64_t>(top.io_commit_rdData);
  commit.memValid = top.io_commit_memValid;
  commit.memWrite = top.io_commit_memWrite;
  commit.memAddr = static_cast<std::uint64_t>(top.io_commit_memAddr);
  commit.memWdata = static_cast<std::uint64_t>(top.io_commit_memWdata);
  commit.memWmask = static_cast<std::uint8_t>(top.io_commit_memWmask);
  commit.exception = top.io_commit_exception;
  return commit;
}
}  // namespace

int main(int argc, char** argv) {
  try {
    const auto options = parseOptions(argc, argv);
    VerilatedContext context;
    context.commandArgs(argc, argv);

    VAetherCoreSimTop top{&context};
    if (!options.rxBytes.empty() && !hasUartRxPort<VAetherCoreSimTop>()) {
      throw std::runtime_error("UART RX injection requested for a top without RX ports");
    }
    if (options.requirePtw && !hasPtwPort<VAetherCoreSimTop>()) {
      throw std::runtime_error("PTW traffic required for a top without PTW ports");
    }
    if (options.expectedPtwBytes) {
      const auto actualBytes = ptwPortBytes(top);
      if (actualBytes != *options.expectedPtwBytes) {
        throw std::runtime_error(
            "PTW response width mismatch: expected " + std::to_string(*options.expectedPtwBytes) +
            " bytes, got " + std::to_string(actualBytes));
      }
    }

    Memory memory;
    memory.load(options.image);

    std::unique_ptr<NemuDifftest> difftest;
    if (options.difftestSharedObject) {
      difftest = std::make_unique<NemuDifftest>(
          *options.difftestSharedObject, options.image, kRamBase, kRamSize);
    }

    VerilatedVcdC* wave = nullptr;
    if (options.trace) {
      context.traceEverOn(true);
      wave = new VerilatedVcdC;
      top.trace(wave, 99);
      wave->open("build/aethercore.vcd");
    }

    std::uint64_t committed = 0;
    std::uint64_t exceptions = 0;
    std::uint64_t ptwReads = 0;
    std::uint64_t cycles = 0;
    std::uint64_t exitCode = 0;
    std::uint64_t exceptionPc = 0;
    std::uint32_t exceptionInst = 0;
    std::size_t rxIndex = 0;
    std::uint64_t nextRxCycle = options.rxStartCycle;
    bool sawX3 = false;
    bool exitRequested = false;
    bool forbiddenWriteSeen = false;
    std::string uart;

    top.reset = 1;
    top.clock = 0;
    driveInputs(top, memory, true);
    driveUartRx(top, false, 0);
    top.eval();

    for (; cycles < options.maxCycles && !top.io_halted && !exitRequested; ++cycles) {
      const bool memoryReady =
          options.stallPeriod == 0 || (cycles % options.stallPeriod) != 0;
      const bool rxValid = !top.reset && rxIndex < options.rxBytes.size() &&
                           cycles >= nextRxCycle;
      const std::uint8_t rxByte = rxValid ? options.rxBytes[rxIndex] : 0;

      top.clock = 0;
      driveInputs(top, memory, memoryReady);
      driveUartRx(top, rxValid, rxByte);
      top.eval();
      driveInputs(top, memory, memoryReady);
      driveUartRx(top, rxValid, rxByte);
      top.eval();
      const bool rxAccepted = rxValid && uartRxReady(top);
      const bool ptwAccepted = !top.reset && ptwRequestAccepted(top);

      if (wave) wave->dump(context.time());
      context.timeInc(1);

      if (!top.reset) {
        if (ptwAccepted) ++ptwReads;

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
          exitCode = static_cast<std::uint64_t>(top.io_exitCode);
          exitRequested = true;
        }

        if (top.io_commit_valid) {
          ++committed;
          if (difftest) difftest->check(makeDifftestCommit(top));

          if (top.io_commit_exception) {
            ++exceptions;
            exceptionPc = static_cast<std::uint64_t>(top.io_commit_pc);
            exceptionInst = static_cast<std::uint32_t>(top.io_commit_inst);
          }

          if (options.forbiddenRd && top.io_commit_rdWrite &&
              static_cast<unsigned>(top.io_commit_rd) == *options.forbiddenRd) {
            forbiddenWriteSeen = true;
          }

          if (top.io_commit_rdWrite && top.io_commit_rd == 3) {
            const auto value = static_cast<std::uint64_t>(top.io_commit_rdData);
            if (!options.selfCheckExit && !options.faultCheck() && value != kExpectedX3) {
              std::cerr << "\nFAIL: x3 committed 0x" << std::hex << value
                        << ", expected 0x" << kExpectedX3 << std::dec << '\n';
              return 3;
            }
            if (value == kExpectedX3) sawX3 = true;
          }

          if (options.commitTrace) dumpCommit(top);
        }
      }

      top.clock = 1;
      driveInputs(top, memory, memoryReady);
      driveUartRx(top, rxValid, rxByte);
      top.eval();
      if (wave) wave->dump(context.time());
      context.timeInc(1);

      if (rxAccepted) {
        ++rxIndex;
        nextRxCycle = cycles + options.rxGapCycles;
      }
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

    if (options.faultCheck()) {
      if (!top.io_halted) {
        std::cerr << "FAIL: fault test ended without the core halting\n";
        return 20;
      }
      if (exitRequested || !uart.empty()) {
        std::cerr << "FAIL: younger MMIO side effect escaped past the fault\n";
        return 21;
      }
      if (exceptions != 1) {
        std::cerr << "FAIL: observed " << exceptions << " exception commits, expected exactly one\n";
        return 22;
      }
      if (exceptionPc != *options.expectedExceptionPc ||
          exceptionInst != *options.expectedExceptionInst) {
        std::cerr << "FAIL: exception boundary pc=0x" << std::hex << exceptionPc
                  << " inst=0x" << exceptionInst << ", expected pc=0x"
                  << *options.expectedExceptionPc << " inst=0x"
                  << *options.expectedExceptionInst << std::dec << '\n';
        return 23;
      }
      if (committed != *options.expectedCommits) {
        std::cerr << "FAIL: retired " << committed << " instructions, expected "
                  << *options.expectedCommits << '\n';
        return 24;
      }
      if (forbiddenWriteSeen) {
        std::cerr << "FAIL: younger forbidden register x" << *options.forbiddenRd
                  << " committed after the fault boundary\n";
        return 25;
      }
      if (options.expectedMemoryAddress) {
        const auto observed = memory.read64(*options.expectedMemoryAddress);
        if (observed != *options.expectedMemoryValue) {
          std::cerr << "FAIL: memory[0x" << std::hex << *options.expectedMemoryAddress
                    << "] = 0x" << observed << ", expected 0x"
                    << *options.expectedMemoryValue << std::dec << '\n';
          return 26;
        }
      }

      std::cout << "PASS: precise fault pc=0x" << std::hex << exceptionPc
                << " inst=0x" << exceptionInst << std::dec << " after " << cycles
                << " cycles, " << committed << " committed instructions";
      if (options.stallPeriod != 0) std::cout << ", stall-period=" << options.stallPeriod;
      if (ptwReads != 0) std::cout << ", ptw-reads=" << ptwReads;
      std::cout << '\n';
      return 0;
    }

    if (options.selfCheckExit) {
      if (!exitRequested) {
        std::cerr << "FAIL: self-check program halted without writing the exit MMIO\n";
        return 9;
      }
      if (exitCode != 0) {
        std::cerr << "FAIL: self-check program returned code " << exitCode << '\n';
        return static_cast<int>(exitCode > 125 ? 125 : exitCode);
      }
      if (rxIndex != options.rxBytes.size()) {
        std::cerr << "FAIL: accepted " << rxIndex << " of " << options.rxBytes.size()
                  << " requested UART RX bytes\n";
        return 10;
      }
      if (options.requirePtw && ptwReads == 0) {
        std::cerr << "FAIL: self-check program completed without an accepted PTW read\n";
        return 11;
      }
      std::cout << "PASS: self-check exit=0 after " << cycles << " cycles, " << committed
                << " committed instructions";
      if (options.stallPeriod != 0) std::cout << ", stall-period=" << options.stallPeriod;
      if (!options.rxBytes.empty()) std::cout << ", rx-bytes=" << rxIndex;
      if (ptwReads != 0) std::cout << ", ptw-reads=" << ptwReads;
      if (options.expectedPtwBytes) std::cout << ", ptw-bytes=" << *options.expectedPtwBytes;
      if (difftest) std::cout << ", difftest=" << difftest->checkedCommits();
      std::cout << '\n';
      return 0;
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
