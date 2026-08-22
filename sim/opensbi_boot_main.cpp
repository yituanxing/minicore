#ifdef AETHERCORE_L32_C_TOP
#include "VAetherCoreOpenSbiCSimTop.h"
using OpenSbiTop = VAetherCoreOpenSbiCSimTop;
#else
#include "VAetherCoreOpenSbiSimTop.h"
using OpenSbiTop = VAetherCoreOpenSbiSimTop;
#endif
#include "l32_opensbi_runtime.h"
#include "verilated.h"

#include <array>
#include <chrono>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
constexpr std::size_t kRecentCommitCount = 16;
constexpr const char* kDefaultMilestone = "Test payload running";
constexpr std::uint64_t kSupervisorTimerInterruptCode = 5;
constexpr std::uint64_t kSupervisorExternalInterruptCode = 9;
constexpr std::uint64_t kLinuxPayloadBase = 0x80400000ULL;

using aethercore::l32sim::Memory;
using aethercore::l32sim::initialize;
using aethercore::l32sim::step;

bool endsWith(const std::string& text, const std::string& suffix) {
  return !suffix.empty() && text.size() >= suffix.size() &&
      text.compare(text.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool isInterruptCause(std::uint64_t cause, std::uint64_t code) {
  return cause == (0x80000000ULL | code) ||
      cause == (0x8000000000000000ULL | code);
}
}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc < 2 || argc > 13)
      throw std::runtime_error(
          "usage: L32_OPENSBI_SIM FW_PAYLOAD.bin [MAX_CYCLES] [UART_MILESTONE] [MIN_INTERRUPTS] [MIN_SEIP] [UART_TRIGGER] [UART_COMMAND] [POST_INPUT_MAX_CYCLES] [PROGRESS_INTERVAL_CYCLES] [REQUIRE_LAYERED_COMPRESSED] [MIN_STIP] [MIN_POST_MILESTONE_COMMITS]");

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
    const bool requireLayeredCompressed =
        argc >= 11 ? std::stoull(argv[10], nullptr, 0) != 0 : false;
    const std::uint64_t minStip =
        argc >= 12 ? std::stoull(argv[11], nullptr, 0) : 0ULL;
    const std::uint64_t minPostMilestoneCommits =
        argc >= 13 ? std::stoull(argv[12], nullptr, 0) : 0ULL;
    std::string uartInput;
    if (!uartCommand.empty()) {
      uartInput = uartCommand;
      if (uartInput.back() != '\n') uartInput.push_back('\n');
    }

    VerilatedContext context;
    context.commandArgs(argc, argv);
    OpenSbiTop top{&context};
    Memory memory;
    memory.loadAtBase(image);

    std::uint64_t cycles = 0;
    std::uint64_t commits = 0;
    std::uint64_t compressedCommits = 0;
    std::uint64_t opensbiCompressedCommits = 0;
    std::uint64_t linuxCompressedCommits = 0;
    std::uint64_t exceptions = 0;
    std::uint64_t interrupts = 0;
    std::uint64_t supervisorTimerInterrupts = 0;
    std::uint64_t supervisorExternalInterrupts = 0;
    std::uint64_t lastExceptionCause = 0;
    std::uint64_t lastExceptionValue = 0;
    std::uint64_t lastExceptionPc = 0;
    std::uint64_t milestoneCommitCount = 0;
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

    initialize(top, memory);

    if (inputStarted) {
      std::cerr << "\nL32_UART_INPUT_START cycles=0 commits=0 bytes=" << uartInput.size()
                << " trigger=<immediate> command=" << uartCommand << "\n";
    }

    for (; cycles < maxCycles; ++cycles) {
      const bool presentInput = inputStarted && inputIndex < uartInput.size();
      const bool rxAccepted = step(
          top,
          context,
          memory,
          presentInput,
          presentInput ? static_cast<std::uint8_t>(uartInput[inputIndex]) : 0);

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
          milestoneCommitCount = commits;
          std::cerr << "\nL32_UART_MILESTONE cycles=" << cycles
                    << " commits=" << commits
                    << " interrupts=" << interrupts
                    << " stip=" << supervisorTimerInterrupts
                    << " seip=" << supervisorExternalInterrupts
                    << " marker=" << milestone << "\n";
        }
      }

      if (top.io_commit_valid) {
        ++commits;
        const auto commitPc = static_cast<std::uint64_t>(top.io_commit_pc);
        if (static_cast<unsigned>(top.io_commit_instBytes) == 2U) {
          ++compressedCommits;
          if (commitPc < kLinuxPayloadBase) {
            ++opensbiCompressedCommits;
            if (requireLayeredCompressed && opensbiCompressedCommits == 1) {
              std::cerr << "\nL32_FIRST_OPENSBI_COMPRESSED cycles=" << cycles
                        << " commits=" << commits
                        << " pc=0x" << std::hex << commitPc
                        << " raw=0x" << static_cast<std::uint32_t>(top.io_commit_rawInst)
                        << std::dec << "\n";
            }
          } else {
            ++linuxCompressedCommits;
            if (requireLayeredCompressed && linuxCompressedCommits == 1) {
              std::cerr << "\nL32_FIRST_LINUX_COMPRESSED cycles=" << cycles
                        << " commits=" << commits
                        << " pc=0x" << std::hex << commitPc
                        << " raw=0x" << static_cast<std::uint32_t>(top.io_commit_rawInst)
                        << std::dec << "\n";
            }
          }
        }
        recentPcs[recentIndex] = commitPc;
        recentIndex = (recentIndex + 1) % kRecentCommitCount;
        if (recentCount < kRecentCommitCount) ++recentCount;

        if (top.io_commit_exception) {
          ++exceptions;
          lastExceptionPc = commitPc;
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
        if (isInterruptCause(interruptCause, kSupervisorTimerInterruptCode)) {
          ++supervisorTimerInterrupts;
          if (supervisorTimerInterrupts == 1) {
            std::cerr << "\nL32_FIRST_SUPERVISOR_TIMER_INTERRUPT cycles=" << cycles
                      << " commits=" << commits
                      << " pc=0x" << std::hex
                      << static_cast<std::uint64_t>(top.io_commit_interruptPc)
                      << " cause=0x" << interruptCause
                      << std::dec << "\n";
          }
        }
        if (isInterruptCause(interruptCause, kSupervisorExternalInterruptCode)) {
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

      const bool inputSatisfied = uartInput.empty() ||
          (inputStarted && inputIndex == uartInput.size() && sawRxInterrupt && sawPostInputSeip);
      const bool postMilestoneCommitsSatisfied = minPostMilestoneCommits == 0 ||
          (sawMilestone && commits >= milestoneCommitCount &&
           commits - milestoneCommitCount >= minPostMilestoneCommits);
      if (sawMilestone && interrupts >= minInterrupts &&
          supervisorExternalInterrupts >= minSeip &&
          supervisorTimerInterrupts >= minStip && inputSatisfied &&
          postMilestoneCommitsSatisfied) {
        std::cout.flush();
        if (requireLayeredCompressed &&
            (opensbiCompressedCommits == 0 || linuxCompressedCommits == 0)) {
          std::cerr << "\nL32_LAYERED_COMPRESSED_MISSING cycles=" << cycles
                    << " commits=" << commits
                    << " compressed=" << compressedCommits
                    << " opensbi-compressed=" << opensbiCompressedCommits
                    << " linux-compressed=" << linuxCompressedCommits << "\n";
          return 13;
        }
        if (requireLayeredCompressed) {
          std::cerr << "\nL32_LAYERED_COMPRESSED_PASS cycles=" << cycles
                    << " commits=" << commits
                    << " compressed=" << compressedCommits
                    << " opensbi-compressed=" << opensbiCompressedCommits
                    << " linux-compressed=" << linuxCompressedCommits << "\n";
        }
        if (milestone == kDefaultMilestone) {
          std::cerr << "\nL32_OPENSBI_TEST_PAYLOAD_PASS cycles=" << cycles
                    << " commits=" << commits
                    << " exceptions=" << exceptions
                    << " interrupts=" << interrupts
                    << " stip=" << supervisorTimerInterrupts
                    << " seip=" << supervisorExternalInterrupts
                    << " banner=" << (sawOpenSbiBanner ? 1 : 0) << "\n";
        }
        std::cerr << "\nL32_RUNTIME_MILESTONE_PASS cycles=" << cycles
                  << " commits=" << commits
                  << " compressed=" << compressedCommits
                  << " opensbi-compressed=" << opensbiCompressedCommits
                  << " linux-compressed=" << linuxCompressedCommits
                  << " exceptions=" << exceptions
                  << " interrupts=" << interrupts
                  << " stip=" << supervisorTimerInterrupts
                  << " seip=" << supervisorExternalInterrupts
                  << " min-interrupts=" << minInterrupts
                  << " min-stip=" << minStip
                  << " min-seip=" << minSeip
                  << " min-post-milestone-commits=" << minPostMilestoneCommits
                  << " post-milestone-commits="
                  << (sawMilestone && commits >= milestoneCommitCount
                          ? commits - milestoneCommitCount
                          : 0)
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
                  << " stip=" << supervisorTimerInterrupts
                  << " seip=" << supervisorExternalInterrupts
                  << " marker=" << milestone << "\n";
        return 12;
      }
    }

    std::cout.flush();
    std::cerr << "\nL32_OPENSBI_TIMEOUT cycles=" << cycles
              << " commits=" << commits
              << " compressed=" << compressedCommits
              << " opensbi-compressed=" << opensbiCompressedCommits
              << " linux-compressed=" << linuxCompressedCommits
              << " uart-bytes=" << uart.size()
              << " banner=" << (sawOpenSbiBanner ? 1 : 0)
              << " milestone=" << (sawMilestone ? 1 : 0)
              << " min-interrupts=" << minInterrupts
              << " min-stip=" << minStip
              << " min-seip=" << minSeip
              << " min-post-milestone-commits=" << minPostMilestoneCommits
              << " post-milestone-commits="
              << (sawMilestone && commits >= milestoneCommitCount
                      ? commits - milestoneCommitCount
                      : 0)
              << " input-started=" << (inputStarted ? 1 : 0)
              << " input-bytes=" << inputIndex << '/' << uartInput.size()
              << " rx-irq=" << (sawRxInterrupt ? 1 : 0)
              << " post-input-seip=" << (sawPostInputSeip ? 1 : 0)
              << " exceptions=" << exceptions
              << " interrupts=" << interrupts
              << " stip=" << supervisorTimerInterrupts
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
