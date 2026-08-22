#include "VAetherCoreV2OpenSbiRV64SimTop.h"
#include "l32_opensbi_runtime.h"
#include "verilated.h"

#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sys/wait.h>
#include <unistd.h>

namespace {
using Top = VAetherCoreV2OpenSbiRV64SimTop;
using aethercore::l32sim::Memory;
using aethercore::l32sim::initialize;
using aethercore::l32sim::step;

constexpr std::uint64_t kSupervisorTimerCode = 5;
constexpr std::uint64_t kSupervisorExternalCode = 9;

bool endsWith(const std::string& text, const std::string& suffix) {
  return !suffix.empty() && text.size() >= suffix.size() &&
         text.compare(text.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool interruptCauseIs(std::uint64_t cause, std::uint64_t code) {
  return cause == (0x80000000ULL | code) ||
         cause == (0x8000000000000000ULL | code);
}

struct Stats {
  std::uint64_t commits = 0;
  std::uint64_t exceptions = 0;
  std::uint64_t interrupts = 0;
  std::uint64_t stip = 0;
  std::uint64_t seip = 0;
};

// CommitTrace.interrupt is an architectural-boundary event, not a retirement
// qualifier. In v2 a clean-boundary asynchronous interrupt may therefore have
// commit.valid == 0 and commit.interrupt == 1. Keep the two observations
// independent or a checkpoint child would silently miss the exact SEIP event
// it is intended to debug.
void observe(const Top& top, Stats& stats) {
  if (top.io_commit_valid) {
    ++stats.commits;
    if (top.io_commit_exception) ++stats.exceptions;
  }

  if (top.io_commit_interrupt) {
    ++stats.interrupts;
    const auto cause = static_cast<std::uint64_t>(top.io_commit_interruptCause);
    if (interruptCauseIs(cause, kSupervisorTimerCode)) ++stats.stip;
    if (interruptCauseIs(cause, kSupervisorExternalCode)) ++stats.seip;
  }
}

void runCycle(Top& top, VerilatedContext& context, Memory& memory,
              std::uint64_t& cycles, Stats& stats) {
  step(top, context, memory, false, 0);
  ++cycles;
  observe(top, stats);
}

int runChild(Top& top, VerilatedContext& context, Memory& memory,
             std::uint64_t startCycles, Stats startStats,
             const std::string& marker, std::uint64_t maxDeltaCycles,
             std::uint64_t progressEvery, bool requirePostCheckpointSeip,
             std::uint64_t childIndex) {
  std::uint64_t cycles = startCycles;
  Stats stats = startStats;
  const auto startCommits = stats.commits;
  const auto startInterrupts = stats.interrupts;
  const auto startStip = stats.stip;
  const auto startSeip = stats.seip;
  std::uint64_t nextProgress = progressEvery;
  std::string uart;
  bool sawMarker = false;
  const auto hostStart = std::chrono::steady_clock::now();

  std::cerr << "\nV2_CHECKPOINT_CHILD_START index=" << childIndex
            << " cycles=" << cycles
            << " commits=" << stats.commits
            << " stip=" << stats.stip
            << " seip=" << stats.seip
            << " marker=" << marker << "\n";

  for (std::uint64_t delta = 0; delta < maxDeltaCycles; ++delta) {
    runCycle(top, context, memory, cycles, stats);

    if (top.io_uartValid) {
      const char byte = static_cast<char>(top.io_uartByte);
      uart.push_back(byte);
      std::cout.put(byte);
      if (byte == '\n') std::cout.flush();
      if (!sawMarker && endsWith(uart, marker)) {
        sawMarker = true;
        std::cerr << "\nV2_CHECKPOINT_CHILD_MARKER index=" << childIndex
                  << " delta-cycles=" << (cycles - startCycles)
                  << " delta-commits=" << (stats.commits - startCommits)
                  << " seip-delta=" << (stats.seip - startSeip)
                  << " marker=" << marker << "\n";
      }
    }

    if (progressEvery != 0 && delta + 1 >= nextProgress) {
      const auto elapsed = std::chrono::duration<double>(
          std::chrono::steady_clock::now() - hostStart).count();
      std::cerr << "\nV2_CHECKPOINT_CHILD_PROGRESS index=" << childIndex
                << " delta-cycles=" << (delta + 1)
                << " delta-commits=" << (stats.commits - startCommits)
                << " stip-delta=" << (stats.stip - startStip)
                << " seip-delta=" << (stats.seip - startSeip)
                << " cycles-per-second="
                << (elapsed > 0.0 ? (delta + 1) / elapsed : 0.0) << "\n";
      nextProgress += progressEvery;
    }

    const bool seipSatisfied =
        !requirePostCheckpointSeip || stats.seip > startSeip;
    if (sawMarker && seipSatisfied) {
      std::cout.flush();
      std::cerr << "\nV2_CHECKPOINT_CHILD_PASS index=" << childIndex
                << " delta-cycles=" << (cycles - startCycles)
                << " delta-commits=" << (stats.commits - startCommits)
                << " interrupts-delta=" << (stats.interrupts - startInterrupts)
                << " stip-delta=" << (stats.stip - startStip)
                << " seip-delta=" << (stats.seip - startSeip)
                << "\n";
      return 0;
    }

    if (top.io_exitValid) {
      std::cerr << "\nV2_CHECKPOINT_CHILD_EXIT index=" << childIndex
                << " code=" << static_cast<std::uint64_t>(top.io_exitCode)
                << " delta-cycles=" << (cycles - startCycles) << "\n";
      return 14;
    }
  }

  std::cout.flush();
  std::cerr << "\nV2_CHECKPOINT_CHILD_TIMEOUT index=" << childIndex
            << " delta-cycles=" << (cycles - startCycles)
            << " delta-commits=" << (stats.commits - startCommits)
            << " interrupts-delta=" << (stats.interrupts - startInterrupts)
            << " stip-delta=" << (stats.stip - startStip)
            << " seip-delta=" << (stats.seip - startSeip)
            << " marker=" << (sawMarker ? 1 : 0)
            << " require-post-checkpoint-seip="
            << (requirePostCheckpointSeip ? 1 : 0) << "\n";
  return 12;
}
}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc < 6 || argc > 9) {
      throw std::runtime_error(
          "usage: V2_CHECKPOINT_FORKSERVER FW_PAYLOAD.bin BOOT_MAX_CYCLES "
          "CHECKPOINT_UART_TRIGGER CHILD_SUCCESS_MARKER CHILD_COUNT "
          "[CHILD_MAX_CYCLES] [PROGRESS_INTERVAL_CYCLES] "
          "[REQUIRE_POST_CHECKPOINT_SEIP]");
    }

    const std::string image = argv[1];
    const auto bootMaxCycles = std::stoull(argv[2], nullptr, 0);
    const std::string checkpointTrigger = argv[3];
    const std::string childMarker = argv[4];
    const auto childCount = std::stoull(argv[5], nullptr, 0);
    const auto childMaxCycles =
        argc >= 7 ? std::stoull(argv[6], nullptr, 0) : 50000000ULL;
    const auto progressEvery =
        argc >= 8 ? std::stoull(argv[7], nullptr, 0) : 10000000ULL;
    const bool requirePostCheckpointSeip =
        argc >= 9 ? std::stoull(argv[8], nullptr, 0) != 0 : true;

    if (checkpointTrigger.empty())
      throw std::runtime_error("checkpoint trigger must not be empty");
    if (childMarker.empty())
      throw std::runtime_error("child success marker must not be empty");
    if (childCount == 0)
      throw std::runtime_error("child count must be greater than zero");

    VerilatedContext context;
    context.commandArgs(argc, argv);
    Top top{&context};
    Memory memory;
    memory.loadAtBase(image);
    Stats stats;
    std::uint64_t cycles = 0;
    std::uint64_t nextProgress = progressEvery;
    std::string uart;
    bool sawOpenSbiBanner = false;
    bool checkpointReady = false;
    const auto hostStart = std::chrono::steady_clock::now();

    initialize(top, memory);

    while (cycles < bootMaxCycles && !checkpointReady) {
      runCycle(top, context, memory, cycles, stats);
      if (cycles == 4) top.reset = 0;
      if (top.reset) continue;

      if (top.io_uartValid) {
        const char byte = static_cast<char>(top.io_uartByte);
        uart.push_back(byte);
        std::cout.put(byte);
        if (byte == '\n') std::cout.flush();
        if (!sawOpenSbiBanner && endsWith(uart, "OpenSBI v1.6"))
          sawOpenSbiBanner = true;
        if (endsWith(uart, checkpointTrigger))
          checkpointReady = true;
      }

      if (progressEvery != 0 && cycles >= nextProgress) {
        const auto elapsed = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - hostStart).count();
        std::cerr << "\nV2_CHECKPOINT_BOOT_PROGRESS cycles=" << cycles
                  << " commits=" << stats.commits
                  << " stip=" << stats.stip
                  << " seip=" << stats.seip
                  << " cycles-per-second="
                  << (elapsed > 0.0 ? cycles / elapsed : 0.0) << "\n";
        nextProgress += progressEvery;
      }
    }

    if (!checkpointReady) {
      std::cerr << "\nV2_CHECKPOINT_BOOT_TIMEOUT cycles=" << cycles
                << " commits=" << stats.commits
                << " stip=" << stats.stip
                << " seip=" << stats.seip
                << " trigger=" << checkpointTrigger << "\n";
      return 2;
    }
    if (!sawOpenSbiBanner) {
      std::cerr << "\nV2_CHECKPOINT_BOOT_INVALID reason=missing-opensbi-banner\n";
      return 5;
    }

    std::cout.flush();
    std::cerr << "\nV2_CHECKPOINT_READY cycles=" << cycles
              << " commits=" << stats.commits
              << " interrupts=" << stats.interrupts
              << " stip=" << stats.stip
              << " seip=" << stats.seip
              << " trigger=" << checkpointTrigger
              << " children=" << childCount << "\n";
    std::cerr.flush();

    std::uint64_t passed = 0;
    std::uint64_t failed = 0;
    for (std::uint64_t index = 0; index < childCount; ++index) {
      std::cout.flush();
      std::cerr.flush();

      // Verilator's clone hooks make the generated model fork-safe. The host
      // Memory vector, timer/device state embedded in the model, VerilatedContext
      // and all counters are inherited by fork() through OS copy-on-write. The
      // parent never advances after this boundary, so every child starts from
      // the exact same Linux/OpenSBI machine state.
      top.prepareClone();
      errno = 0;
      const pid_t pid = ::fork();
      const int forkError = errno;
      top.atClone();

      if (pid < 0) {
        throw std::runtime_error(
            "fork failed: " + std::string(std::strerror(forkError)));
      }
      if (pid == 0) {
        const int rc = runChild(
            top, context, memory, cycles, stats, childMarker,
            childMaxCycles, progressEvery, requirePostCheckpointSeip, index);
        std::cout.flush();
        std::cerr.flush();
        _exit(rc);
      }

      int status = 0;
      while (::waitpid(pid, &status, 0) < 0) {
        if (errno == EINTR) continue;
        throw std::runtime_error(
            "waitpid failed: " + std::string(std::strerror(errno)));
      }

      if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
        ++passed;
      } else {
        ++failed;
        std::cerr << "\nV2_CHECKPOINT_CHILD_FAIL index=" << index
                  << " rc=" << (WIFEXITED(status) ? WEXITSTATUS(status) : -1)
                  << " signal=" << (WIFSIGNALED(status) ? WTERMSIG(status) : 0)
                  << "\n";
      }
    }

    std::cerr << "\nV2_CHECKPOINT_RESULT children=" << childCount
              << " passed=" << passed
              << " failed=" << failed
              << " checkpoint-cycles=" << cycles << "\n";
    if (failed == 0) {
      std::cerr << "V2_CHECKPOINT_PASS children=" << childCount
                << " checkpoint-cycles=" << cycles << "\n";
      return 0;
    }
    return 20;
  } catch (const std::exception& error) {
    std::cerr << "V2_CHECKPOINT_ERROR: " << error.what() << "\n";
    return 1;
  }
}
