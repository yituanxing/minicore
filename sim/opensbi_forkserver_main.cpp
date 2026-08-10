#include "VAetherCoreOpenSbiSimTop.h"
#include "l32_opensbi_runtime.h"
#include "verilated.h"

#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
#include <sys/wait.h>
#include <unistd.h>

namespace {
constexpr std::uint64_t kSeipCause = 0x80000009ULL;

using aethercore::l32sim::Memory;
using aethercore::l32sim::initialize;
using aethercore::l32sim::step;

bool endsWith(const std::string& text, const std::string& suffix) {
  return !suffix.empty() && text.size() >= suffix.size() &&
         text.compare(text.size() - suffix.size(), suffix.size(), suffix) == 0;
}

struct Workload { std::string id, milestone, command; };
struct Counts { std::uint64_t commits = 0, exceptions = 0, interrupts = 0, seip = 0; };

std::vector<Workload> loadWorkloads(const std::string& path) {
  std::ifstream in(path);
  if (!in) throw std::runtime_error("cannot open workload file: " + path);
  std::vector<Workload> out;
  std::string line;
  std::size_t lineNo = 0;
  while (std::getline(in, line)) {
    ++lineNo;
    if (line.empty() || line[0] == '#') continue;
    const auto a = line.find('\t');
    const auto b = a == std::string::npos ? std::string::npos : line.find('\t', a + 1);
    if (a == std::string::npos || b == std::string::npos)
      throw std::runtime_error("invalid workload line " + std::to_string(lineNo));
    Workload w{line.substr(0, a), line.substr(a + 1, b - a - 1), line.substr(b + 1)};
    if (w.id.empty() || w.milestone.empty() || w.command.empty())
      throw std::runtime_error("empty workload field on line " + std::to_string(lineNo));
    out.push_back(std::move(w));
  }
  if (out.empty()) throw std::runtime_error("workload file is empty");
  return out;
}

void record(VAetherCoreOpenSbiSimTop& top, Counts& c) {
  if (!top.io_commit_valid) return;
  ++c.commits;
  if (top.io_commit_exception) ++c.exceptions;
  if (top.io_commit_interrupt) {
    ++c.interrupts;
    if (static_cast<std::uint64_t>(top.io_commit_interruptCause) == kSeipCause) ++c.seip;
  }
}

bool cycle(VAetherCoreOpenSbiSimTop& top, VerilatedContext& ctx, Memory& mem,
           std::uint64_t& cycles, Counts& c, bool rxValid, std::uint8_t rxByte) {
  const bool accepted = step(top, ctx, mem, rxValid, rxByte);
  ++cycles;
  record(top, c);
  return accepted;
}

int runCase(VAetherCoreOpenSbiSimTop& top, VerilatedContext& ctx, Memory& mem,
            const Workload& w, std::uint64_t startCycles, Counts start,
            std::uint64_t maxCycles, std::uint64_t progressEvery) {
  std::uint64_t cycles = startCycles;
  Counts c = start;
  const auto startCommits = c.commits;
  const auto startSeip = c.seip;
  std::string input = w.command;
  if (input.back() != '\n') input.push_back('\n');
  std::size_t inputPos = 0;
  bool rxIrq = false, postSeip = false, milestone = false;
  std::string uart;
  const auto hostStart = std::chrono::steady_clock::now();
  std::uint64_t nextProgress = progressEvery;

  std::cerr << "\nL32_FORKSERVER_CASE_START id=" << w.id << " cycles=" << cycles
            << " bytes=" << input.size() << " marker=" << w.milestone << "\n";
  for (std::uint64_t delta = 0; delta < maxCycles; ++delta) {
    const bool sending = inputPos < input.size();
    if (cycle(top, ctx, mem, cycles, c, sending,
              sending ? static_cast<std::uint8_t>(input[inputPos]) : 0) && sending) {
      ++inputPos;
      if (inputPos == input.size())
        std::cerr << "\nL32_FORKSERVER_INPUT_COMPLETE id=" << w.id
                  << " cycles=" << cycles << "\n";
    }
    if (top.io_uartRxInterrupt && !rxIrq) {
      rxIrq = true;
      std::cerr << "\nL32_FORKSERVER_RX_INTERRUPT id=" << w.id << " cycles=" << cycles << "\n";
    }
    if (c.seip > startSeip && !postSeip) {
      postSeip = true;
      std::cerr << "\nL32_FORKSERVER_INPUT_SEIP id=" << w.id << " cycles=" << cycles << "\n";
    }
    if (top.io_uartValid) {
      const char ch = static_cast<char>(top.io_uartByte);
      uart.push_back(ch);
      std::cout.put(ch);
      if (ch == '\n') std::cout.flush();
      if (!milestone && endsWith(uart, w.milestone)) {
        milestone = true;
        std::cerr << "\nL32_FORKSERVER_MILESTONE id=" << w.id
                  << " cycles=" << cycles << " marker=" << w.milestone << "\n";
      }
    }
    if (progressEvery && delta + 1 >= nextProgress) {
      const auto sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - hostStart).count();
      std::cerr << "\nL32_FORKSERVER_CASE_PROGRESS id=" << w.id
                << " delta-cycles=" << delta + 1 << " cycles-per-second="
                << (sec > 0 ? (delta + 1) / sec : 0.0) << "\n";
      nextProgress += progressEvery;
    }
    if (milestone && inputPos == input.size() && rxIrq && postSeip) {
      std::cout.flush();
      std::cerr << "\nL32_FORKSERVER_CASE_PASS id=" << w.id
                << " delta-cycles=" << cycles - startCycles
                << " delta-commits=" << c.commits - startCommits
                << " seip-delta=" << c.seip - startSeip << "\n";
      return 0;
    }
  }
  std::cout.flush();
  std::cerr << "\nL32_FORKSERVER_CASE_TIMEOUT id=" << w.id
            << " delta-cycles=" << cycles - startCycles << " input=" << inputPos << '/'
            << input.size() << " rx-irq=" << (rxIrq ? 1 : 0)
            << " post-input-seip=" << (postSeip ? 1 : 0)
            << " milestone=" << (milestone ? 1 : 0) << " exceptions=" << c.exceptions
            << " interrupts=" << c.interrupts << " seip=" << c.seip << "\n";
  return 12;
}
}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc < 5 || argc > 7)
      throw std::runtime_error(
          "usage: FORKSERVER FW_PAYLOAD.bin BOOT_MAX_CYCLES UART_TRIGGER WORKLOADS.tsv [CASE_MAX_CYCLES] [PROGRESS_INTERVAL_CYCLES]");
    const std::string image = argv[1];
    const auto bootMax = std::stoull(argv[2], nullptr, 0);
    const std::string trigger = argv[3];
    const auto workloads = loadWorkloads(argv[4]);
    const auto caseMax = argc >= 6 ? std::stoull(argv[5], nullptr, 0) : 50000000ULL;
    const auto progressEvery = argc >= 7 ? std::stoull(argv[6], nullptr, 0) : 25000000ULL;

    VerilatedContext ctx;
    ctx.commandArgs(argc, argv);
    VAetherCoreOpenSbiSimTop top{&ctx};
    Memory mem;
    mem.loadAtBase(image);
    Counts counts;
    std::uint64_t cycles = 0, nextProgress = progressEvery;
    std::string uart;
    bool banner = false, ready = false;
    const auto hostStart = std::chrono::steady_clock::now();

    initialize(top, mem);

    while (cycles < bootMax && !ready) {
      cycle(top, ctx, mem, cycles, counts, false, 0);
      if (cycles == 4) top.reset = 0;
      if (top.reset) continue;
      if (top.io_uartValid) {
        const char ch = static_cast<char>(top.io_uartByte);
        uart.push_back(ch);
        std::cout.put(ch);
        if (ch == '\n') std::cout.flush();
        if (!banner && endsWith(uart, "OpenSBI v1.6")) banner = true;
        if (endsWith(uart, trigger)) ready = true;
      }
      if (progressEvery && cycles >= nextProgress) {
        const auto sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - hostStart).count();
        std::cerr << "\nL32_FORKSERVER_BOOT_PROGRESS cycles=" << cycles
                  << " cycles-per-second=" << (sec > 0 ? cycles / sec : 0.0) << "\n";
        nextProgress += progressEvery;
      }
    }
    if (!ready) {
      std::cerr << "\nL32_FORKSERVER_BOOT_TIMEOUT cycles=" << cycles << " trigger=" << trigger << "\n";
      return 2;
    }
    if (!banner) {
      std::cerr << "\nL32_FORKSERVER_BOOT_INVALID reason=missing-opensbi-banner\n";
      return 5;
    }
    std::cout.flush();
    std::cerr << "\nL32_FORKSERVER_READY cycles=" << cycles << " commits=" << counts.commits
              << " seip=" << counts.seip << " cases=" << workloads.size() << "\n";
    std::cerr.flush();

    std::size_t passed = 0, failed = 0;
    for (const auto& w : workloads) {
      std::cout.flush();
      std::cerr.flush();
      top.prepareClone();
      errno = 0;
      const pid_t pid = ::fork();
      const int forkErr = errno;
      top.atClone();
      if (pid < 0)
        throw std::runtime_error("fork failed for " + w.id + ": " + std::strerror(forkErr));
      if (pid == 0) {
        const int rc = runCase(top, ctx, mem, w, cycles, counts, caseMax, progressEvery);
        std::cout.flush();
        std::cerr.flush();
        _exit(rc);
      }
      int status = 0;
      while (::waitpid(pid, &status, 0) < 0) {
        if (errno == EINTR) continue;
        throw std::runtime_error("waitpid failed for " + w.id + ": " + std::strerror(errno));
      }
      if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
        ++passed;
      } else {
        ++failed;
        std::cerr << "\nL32_FORKSERVER_CASE_FAIL id=" << w.id
                  << " rc=" << (WIFEXITED(status) ? WEXITSTATUS(status) : -1)
                  << " signal=" << (WIFSIGNALED(status) ? WTERMSIG(status) : 0) << "\n";
      }
    }
    std::cerr << "\nL32_FORKSERVER_RESULT cases=" << workloads.size() << " passed=" << passed
              << " failed=" << failed << " boot-cycles=" << cycles << "\n";
    if (failed == 0) {
      std::cerr << "L32_FORKSERVER_PASS cases=" << workloads.size() << " boot-cycles=" << cycles << "\n";
      return 0;
    }
    return 20;
  } catch (const std::exception& e) {
    std::cerr << "L32_FORKSERVER_ERROR: " << e.what() << "\n";
    return 1;
  }
}
