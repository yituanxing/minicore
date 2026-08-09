#include "VAetherCoreOpenSbiSimTop.h"
#include "verilated.h"

#include <array>
#include <chrono>
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
constexpr std::uint64_t kSupervisorExternalInterruptCause = 0x80000009ULL;

bool endsWith(const std::string& text, const std::string& suffix) {
  return !suffix.empty() && text.size() >= suffix.size() &&
      text.compare(text.size() - suffix.size(), suffix.size(), suffix) == 0;
}

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

  std::uint32_t read32Unchecked(std::uint64_t address) const {
    const auto offset = static_cast<std::size_t>(address - kRamBase);
    return std::uint32_t(bytes_[offset]) |
        (std::uint32_t(bytes_[offset + 1]) << 8) |
        (std::uint32_t(bytes_[offset + 2]) << 16) |
        (std::uint32_t(bytes_[offset + 3]) << 24);
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
  top.io_imemInst = ifault ? kEbreak : memory.read32Unchecked(iaddr);

  const bool dvalid = top.io_memValid;
  const auto daddr = static_cast<std::uint64_t>(top.io_memAddr);
  const bool dfault = dvalid && !memory.contains(daddr, 4);
  top.io_memReady = true;
  top.io_memFault = dfault;
  top.io_memRdata = (!dvalid || dfault) ? 0 : memory.read32Unchecked(daddr);

  const bool ptwValid = top.io_ptwValid;
  const auto ptwAddr = static_cast<std::uint64_t>(top.io_ptwAddr);
  const bool ptwFault = ptwValid && !memory.contains(ptwAddr, 4);
  top.io_ptwReady = true;
  top.io_ptwFault = ptwFault;
  top.io_ptwRdata = (!ptwValid || ptwFault) ? 0 : memory.read32Unchecked(ptwAddr);
}
}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc < 2 || argc > 10)
      throw std::runtime_error(
          "usage: VAetherCoreOpenSbiSimTop FW_PAYLOAD.bin [MAX_CYCLES] [UART_MILESTONE] [MIN_INTERRUPTS] [MIN_SEIP] [UART_TRIGGER] [UART_COMMAND] [POST_INPUT_MAX_CYCLES] [PROGRESS_INTERVAL_CYCLES]");

    const std::string image = argv[1];
    const std::uint64_t maxCycles =
        argc >= 3 ? std::stoull(argv[2], nullptr, 0) : 10000000ULL;
    const std::string milestone = argc >= 4 ? argv[3] : kDefaultMilestone;
    const std::uint64_t minInterrupts =
        argc >= 5 ? std::stoull(argv[4], nullptr, 0) : 0ULL;
    const std::uint64_t minSeip =
        argc >= 6 ? std::stoull(argv[5], nullptr, 0) : 0ULL;
    const std::string uartTrigger = argc >= 7 ? argv[6] : "";
    const std::string uartCommand = argc >= 8 ? argv[7] : "";
    const std::uint64_t postInputMaxCycles =
        argc >= 9 ? std::stoull(argv[8], nullptr, 0) : 0ULL;
    const std::uint64_t progressIntervalCycles =
        argc >= 10 ? std::stoull(argv[9], nullptr, 0) : 0ULL;
    std::string uartInput;
    if (!uartCommand.empty()) {
      uartInput = uartCommand;
      if (uartInput.back() != '\n') uartInput.push_back('\n');
    }

    VerilatedContext context;
    context.commandArgs(argc, argv);
    VAetherCoreOpenSbiSimTop top{&context};
    Memory memory;
    memory.loadAtBase(image);

    std::uint64_t cycles = 0;
    std::uint64_t commits = 0;
    std::uint64_t exceptions = 0;
    std::uint64_t interrupts = 0;
    std::uint64_t supervisorExternalInterrupts = 0;
    std::uint64_t lastExceptionCause = 0;
    std::uint64_t lastExceptionValue = 0;
    std::uint64_t lastExceptionPc = 0;
    std::string uart;
    bool sawOpenSbiBanner = false;
    bool sawMilestone = false;
    bool inputStarted = !uartInput.empty() && uartTrigger.empty();
    std::size_t inputIndex = 0;
    std::uint64_t seipAtInputStart = 0;
    bool sawRxInterrupt = false;
    bool sawPostInputSeip = false;
    bool postInputDeadlineArmed = false;
    std::uint64_t inputCompleteCycle = 0;
    std::uint64_t postInputDeadline = 0;
    std::uint64_t nextProgressCycle = progressIntervalCycles;
    const auto hostStart = std::chrono::steady_clock::now();
    std::array<std::uint64_t, kRecentCommitCount> recentPcs{};
    std::size_t recentCount = 0;
    std::size_t recentIndex = 0;

    top.reset = 1;
    top.clock = 0;
    top.io_rxValid = 0;
    top.io_rxByte = 0;
    driveMemory(top, memory);
    top.eval();

    if (inputStarted) {
      std::cerr << "\nL32_UART_INPUT_START cycles=0 commits=0 bytes=" << uartInput.size()
                << " trigger=<immediate> command=" << uartCommand << "\n";
    }

    for (; cycles < maxCycles; ++cycles) {
      const bool presentInput = inputStarted && inputIndex < uartInput.size();

      top.clock = 0;
      top.io_rxValid = presentInput;
      top.io_rxByte = presentInput ? static_cast<std::uint8_t>(uartInput[inputIndex]) : 0;
      driveMemory(top, memory);
      top.eval();
      driveMemory(top, memory);
      top.eval();
      const bool rxAccepted = top.io_rxValid && top.io_rxReady;

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

      if (progressIntervalCycles != 0 && cycles >= nextProgressCycle) {
        const auto elapsed = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - hostStart).count();
        const auto cyclesPerSecond = elapsed > 0.0 ? cycles / elapsed : 0.0;
        std::cerr << "\nL32_SIM_PROGRESS cycles=" << cycles
                  << " commits=" << commits
                  << " host-seconds=" << elapsed
                  << " cycles-per-second=" << cyclesPerSecond
                  << " input=" << inputIndex << '/' << uartInput.size()
                  << " milestone=" << (sawMilestone ? 1 : 0) << "\n";
        nextProgressCycle += progressIntervalCycles;
      }

      if (rxAccepted) {
        ++inputIndex;
        if (inputIndex == uartInput.size()) {
          inputCompleteCycle = cycles;
          if (postInputMaxCycles != 0) {
            postInputDeadlineArmed = true;
            postInputDeadline = cycles + postInputMaxCycles;
          }
          std::cerr << "\nL32_UART_INPUT_COMPLETE cycles=" << cycles
                    << " commits=" << commits
                    << " bytes=" << inputIndex
                    << " post-input-budget=" << postInputMaxCycles << "\n";
        }
      }

      if (inputStarted && top.io_uartRxInterrupt && !sawRxInterrupt) {
        sawRxInterrupt = true;
        std::cerr << "\nL32_UART_RX_INTERRUPT cycles=" << cycles
                  << " commits=" << commits
                  << " injected=" << inputIndex << '/' << uartInput.size() << "\n";
      }

      if (top.io_uartValid) {
        const char byte = static_cast<char>(top.io_uartByte);
        uart.push_back(byte);
        std::cout.put(byte);
        if (byte == '\n') std::cout.flush();
        if (!sawOpenSbiBanner && endsWith(uart, "OpenSBI v1.6")) {
          sawOpenSbiBanner = true;
          std::cerr << "\nL32_OPENSBI_BANNER cycles=" << cycles
                    << " commits=" << commits << "\n";
        }
        if (!inputStarted && !uartInput.empty() && !uartTrigger.empty() &&
            endsWith(uart, uartTrigger)) {
          inputStarted = true;
          seipAtInputStart = supervisorExternalInterrupts;
          std::cerr << "\nL32_UART_INPUT_START cycles=" << cycles
                    << " commits=" << commits
                    << " bytes=" << uartInput.size()
                    << " seip-before=" << seipAtInputStart
                    << " trigger=" << uartTrigger
                    << " command=" << uartCommand << "\n";
        }
        if (!sawMilestone && endsWith(uart, milestone)) {
          sawMilestone = true;
          std::cerr << "\nL32_UART_MILESTONE cycles=" << cycles
                    << " commits=" << commits
                    << " interrupts=" << interrupts
                    << " seip=" << supervisorExternalInterrupts
                    << " marker=" << milestone << "\n";
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
        if (top.io_commit_interrupt) {
          ++interrupts;
          const auto interruptCause =
              static_cast<std::uint64_t>(top.io_commit_interruptCause);
          if (interrupts == 1) {
            std::cerr << "\nL32_FIRST_INTERRUPT cycles=" << cycles
                      << " commits=" << commits
                      << " pc=0x" << std::hex
                      << static_cast<std::uint64_t>(top.io_commit_interruptPc)
                      << " cause=0x" << interruptCause
                      << std::dec << "\n";
          }
          if (interruptCause == kSupervisorExternalInterruptCause) {
            ++supervisorExternalInterrupts;
            if (supervisorExternalInterrupts == 1) {
              std::cerr << "\nL32_FIRST_SUPERVISOR_EXTERNAL_INTERRUPT cycles=" << cycles
                        << " commits=" << commits
                        << " pc=0x" << std::hex
                        << static_cast<std::uint64_t>(top.io_commit_interruptPc)
                        << " cause=0x" << interruptCause
                        << std::dec << "\n";
            }
            if (inputStarted && supervisorExternalInterrupts > seipAtInputStart &&
                !sawPostInputSeip) {
              sawPostInputSeip = true;
              std::cerr << "\nL32_UART_INPUT_SEIP cycles=" << cycles
                        << " commits=" << commits
                        << " seip-before=" << seipAtInputStart
                        << " seip-now=" << supervisorExternalInterrupts << "\n";
            }
          }
        }
      }

      const bool inputSatisfied = uartInput.empty() ||
          (inputStarted && inputIndex == uartInput.size() && sawRxInterrupt && sawPostInputSeip);
      if (sawMilestone && interrupts >= minInterrupts &&
          supervisorExternalInterrupts >= minSeip && inputSatisfied) {
        std::cout.flush();
        if (milestone == kDefaultMilestone) {
          std::cerr << "\nL32_OPENSBI_TEST_PAYLOAD_PASS cycles=" << cycles
                    << " commits=" << commits
                    << " exceptions=" << exceptions
                    << " interrupts=" << interrupts
                    << " seip=" << supervisorExternalInterrupts
                    << " banner=" << (sawOpenSbiBanner ? 1 : 0) << "\n";
        }
        std::cerr << "\nL32_RUNTIME_MILESTONE_PASS cycles=" << cycles
                  << " commits=" << commits
                  << " exceptions=" << exceptions
                  << " interrupts=" << interrupts
                  << " seip=" << supervisorExternalInterrupts
                  << " min-interrupts=" << minInterrupts
                  << " min-seip=" << minSeip
                  << " banner=" << (sawOpenSbiBanner ? 1 : 0)
                  << " input-bytes=" << inputIndex << '/' << uartInput.size()
                  << " rx-irq=" << (sawRxInterrupt ? 1 : 0)
                  << " post-input-seip=" << (sawPostInputSeip ? 1 : 0)
                  << " marker=" << milestone << "\n";
        return sawOpenSbiBanner ? 0 : 5;
      }

      if (postInputDeadlineArmed && cycles >= postInputDeadline) {
        std::cout.flush();
        std::cerr << "\nL32_POST_INPUT_TIMEOUT cycles=" << cycles
                  << " commits=" << commits
                  << " input-complete-cycle=" << inputCompleteCycle
                  << " post-input-cycles=" << (cycles - inputCompleteCycle)
                  << " budget=" << postInputMaxCycles
                  << " milestone=" << (sawMilestone ? 1 : 0)
                  << " interrupts=" << interrupts
                  << " seip=" << supervisorExternalInterrupts
                  << " marker=" << milestone << "\n";
        return 12;
      }
    }

    std::cout.flush();
    std::cerr << "\nL32_OPENSBI_TIMEOUT cycles=" << cycles
              << " commits=" << commits
              << " uart-bytes=" << uart.size()
              << " banner=" << (sawOpenSbiBanner ? 1 : 0)
              << " milestone=" << (sawMilestone ? 1 : 0)
              << " min-interrupts=" << minInterrupts
              << " min-seip=" << minSeip
              << " input-started=" << (inputStarted ? 1 : 0)
              << " input-bytes=" << inputIndex << '/' << uartInput.size()
              << " rx-irq=" << (sawRxInterrupt ? 1 : 0)
              << " post-input-seip=" << (sawPostInputSeip ? 1 : 0)
              << " exceptions=" << exceptions
              << " interrupts=" << interrupts
              << " seip=" << supervisorExternalInterrupts
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
