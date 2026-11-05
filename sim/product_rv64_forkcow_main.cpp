// Product-only warm checkpoint replay. Unlike a RAM-only copy, fork() clones
// the entire *live* Verilated model, context, CPU/CSR/cache/MMIO/interrupt
// flops, host RAM/LRSC, pending host transport and observation state in one
// Linux process snapshot. Only the parent remains at the checkpoint; each
// disposable child resumes it independently with copy-on-write pages.
//
// IMPORTANT: this amortizes one already-completed cold boot over many warm
// workloads. It cannot accelerate the first boot to a requested milestone,
// and must not be mistaken for a portable/disk-backed Verilator checkpoint.
// SD1 is deliberately excluded: its file-backed SPI card has additional
// external state and a separate lifetime/ownership contract.
#ifdef AETHERCORE_PRODUCT_SPI_SD
#error "Product fork-COW only supports RAM-only tops, not external SPI-SD state"
#endif
#ifdef AETHERCORE_L32_C_TOP
#error "Product fork-COW requires an actual Product RV64 top"
#endif
#ifdef AETHERCORE_PRODUCT_DEBIAN
#include "VAetherCoreProductDebianRV64SimTop.h"
using ProductTop = VAetherCoreProductDebianRV64SimTop;
#else
#include "VAetherCoreProductOpenSbiRV64SimTop.h"
using ProductTop = VAetherCoreProductOpenSbiRV64SimTop;
#endif
#include "l32_opensbi_runtime.h"
#include "verilated.h"

#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>
#include <sys/types.h>
#include <sys/wait.h>
#include <type_traits>
#include <utility>
#include <unistd.h>

namespace {

using aethercore::l32sim::Memory;
using aethercore::l32sim::TransportRuntimeState;
using aethercore::l32sim::initialize;
using aethercore::l32sim::step;

constexpr std::uint64_t kFnvOffset = 14695981039346656037ULL;
constexpr std::uint64_t kFnvPrime = 1099511628211ULL;

void mix(std::uint64_t& hash, std::uint64_t word) {
  for (int i = 0; i < 8; ++i) {
    hash ^= static_cast<std::uint8_t>(word >> (i * 8));
    hash *= kFnvPrime;
  }
}

bool interruptCause(std::uint64_t cause, std::uint64_t code) {
  return cause == (0x80000000ULL | code) ||
      cause == (0x8000000000000000ULL | code);
}

// Bound memory usage even when the firmware emits a continuous UART stream.
class Marker {
 public:
  explicit Marker(std::string marker) : marker_(std::move(marker)) {}
  void push(char byte) {
    if (marker_.empty()) return;
    tail_.push_back(byte);
    if (tail_.size() > marker_.size()) tail_.erase(0, 1);
    if (tail_ == marker_) matched_ = true;
  }
  bool matched() const { return matched_; }

 private:
  std::string marker_;
  std::string tail_;
  bool matched_ = false;
};

struct Counts {
  std::uint64_t cycles = 0;
  std::uint64_t commits = 0;
  std::uint64_t compressed = 0;
  std::uint64_t exceptions = 0;
  std::uint64_t interrupts = 0;
  std::uint64_t stip = 0;
  std::uint64_t seip = 0;
  std::uint64_t uartBytes = 0;
  std::uint64_t traceHash = kFnvOffset;
  std::uint64_t uartHash = kFnvOffset;
};

// Digest every cycle's architecturally visible traces AND memory/interrupt
// seam signals. The replay results additionally include complete host RAM,
// the LR/SC reservation, pending transport and final timer state.
void record(const ProductTop& top, Counts& c) {
  ++c.cycles;
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_commit_valid));
  if (top.io_commit_valid) {
    ++c.commits;
    if (static_cast<unsigned>(top.io_commit_instBytes) == 2U) ++c.compressed;
    if (top.io_commit_exception) ++c.exceptions;
    if (top.io_commit_interrupt) {
      ++c.interrupts;
      const auto cause = static_cast<std::uint64_t>(top.io_commit_interruptCause);
      if (interruptCause(cause, 5)) ++c.stip;
      if (interruptCause(cause, 9)) ++c.seip;
    }
    mix(c.traceHash, static_cast<std::uint64_t>(top.io_commit_pc));
    mix(c.traceHash, static_cast<std::uint64_t>(top.io_commit_rawInst));
    mix(c.traceHash, static_cast<std::uint64_t>(top.io_commit_instBytes));
    mix(c.traceHash, static_cast<std::uint64_t>(top.io_commit_exception));
    mix(c.traceHash, static_cast<std::uint64_t>(top.io_commit_interrupt));
    mix(c.traceHash, static_cast<std::uint64_t>(top.io_commit_interruptCause));
  }
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_imemValid));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_imemAddr));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_memValid));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_memAddr));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_memReady));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_ptwValid));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_ptwAddr));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_rxReady));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_uartRxInterrupt));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_uartInterrupt));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_supervisorExternalInterrupt));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_timerInterrupt));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_exitValid));
  mix(c.traceHash, static_cast<std::uint64_t>(top.io_exitCode));
  if (top.io_uartValid) {
    ++c.uartBytes;
    mix(c.uartHash, static_cast<std::uint8_t>(top.io_uartByte));
  }
}

bool cycle(ProductTop& top, VerilatedContext& ctx, Memory& mem,
           TransportRuntimeState& transport, Counts& counts,
           bool rxValid = false, std::uint8_t rxByte = 0) {
  const bool accepted = step(top, ctx, mem, transport, rxValid, rxByte);
  record(top, counts);
  return accepted;
}

struct ReplayResult {
  std::uint64_t replayCycles = 0;
  std::uint64_t commits = 0;
  std::uint64_t compressed = 0;
  std::uint64_t exceptions = 0;
  std::uint64_t interrupts = 0;
  std::uint64_t stip = 0;
  std::uint64_t seip = 0;
  std::uint64_t uartBytes = 0;
  std::uint64_t traceHash = 0;
  std::uint64_t uartHash = 0;
  std::uint64_t ramHash = 0;
  std::uint64_t endMtime = 0;
  std::uint64_t endMtimecmp = 0;
  std::uint64_t endCtxTime = 0;
  std::uint64_t endDcacheHit = 0;
  std::uint64_t endDcacheMiss = 0;
  std::uint64_t endDcacheBypass = 0;
  std::uint64_t transportHash = 0;
  std::uint64_t acceptedBytes = 0;
  std::uint64_t rxInterruptObserved = 0;
  std::uint64_t responseObserved = 0;
  bool operator==(const ReplayResult&) const = default;
};
static_assert(std::is_trivially_copyable_v<ReplayResult>);

ReplayResult replay(ProductTop& top, VerilatedContext& ctx, Memory& mem,
                    TransportRuntimeState& transport, std::uint64_t replayCycles,
                    const std::string& command, const std::string& expectedReply) {
  Counts stats;
  Marker expected(expectedReply);
  std::size_t accepted = 0;
  bool sawRxInterrupt = false;
  for (std::uint64_t i = 0; i < replayCycles; ++i) {
    const bool inject = accepted < command.size();
    if (cycle(top, ctx, mem, transport, stats, inject,
              inject ? static_cast<std::uint8_t>(command[accepted]) : 0) && inject)
      ++accepted;
    if (top.io_uartRxInterrupt) sawRxInterrupt = true;
    if (top.io_uartValid) expected.push(static_cast<char>(top.io_uartByte));
  }

  // The parent cannot infer successful UART delivery from repeated identical
  // hashes. A requested interactive replay must itself prove RX acceptance,
  // an RX interrupt and a matching response (if supplied).
  if (!command.empty() && accepted != command.size())
    throw std::runtime_error("fork-COW replay UART input not completely accepted");
  if (!command.empty() && !sawRxInterrupt)
    throw std::runtime_error("fork-COW replay UART RX interrupt missing");
  if (!command.empty() && stats.seip == 0)
    throw std::runtime_error("fork-COW replay did not retire S-mode external interrupt");
  if (!expectedReply.empty() && !expected.matched())
    throw std::runtime_error("fork-COW replay UART expected response missing");
  if (stats.commits == 0)
    throw std::runtime_error("fork-COW replay did not retire any Product CPU instruction");

  ReplayResult out;
  out.replayCycles = stats.cycles;
  out.commits = stats.commits;
  out.compressed = stats.compressed;
  out.exceptions = stats.exceptions;
  out.interrupts = stats.interrupts;
  out.stip = stats.stip;
  out.seip = stats.seip;
  out.uartBytes = stats.uartBytes;
  out.traceHash = stats.traceHash;
  out.uartHash = stats.uartHash;
  out.ramHash = mem.fingerprint();
  out.endMtime = static_cast<std::uint64_t>(top.io_mtime);
  out.endMtimecmp = static_cast<std::uint64_t>(top.io_mtimecmp);
  out.endCtxTime = ctx.time();
  out.endDcacheHit = static_cast<std::uint64_t>(top.io_dcacheHitCount);
  out.endDcacheMiss = static_cast<std::uint64_t>(top.io_dcacheMissCount);
  out.endDcacheBypass = static_cast<std::uint64_t>(top.io_dcacheBypassCount);
  const auto& wait = transport.dataWait;
  out.transportHash = kFnvOffset;
  mix(out.transportHash, wait.active);
  mix(out.transportHash, wait.qualificationPrinted);
  mix(out.transportHash, wait.remaining);
  mix(out.transportHash, wait.observedStallCycles);
  out.acceptedBytes = accepted;
  out.rxInterruptObserved = sawRxInterrupt;
  out.responseObserved = expected.matched();
  return out;
}

bool writeAll(int fd, const void* buf, std::size_t size) {
  const auto* data = static_cast<const unsigned char*>(buf);
  while (size != 0) {
    const ssize_t n = ::write(fd, data, size);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return false;
    data += n;
    size -= static_cast<std::size_t>(n);
  }
  return true;
}

bool readAll(int fd, void* buf, std::size_t size) {
  auto* data = static_cast<unsigned char*>(buf);
  while (size != 0) {
    const ssize_t n = ::read(fd, data, size);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return false;
    data += n;
    size -= static_cast<std::size_t>(n);
  }
  return true;
}

ReplayResult forkReplay(ProductTop& top, VerilatedContext& ctx, Memory& mem,
                        TransportRuntimeState& transport,
                        std::uint64_t replayCycles, const std::string& command,
                        const std::string& expectedReply) {
  int pipeFds[2];
  if (::pipe(pipeFds) != 0)
    throw std::runtime_error(std::string("fork-COW pipe: ") + std::strerror(errno));
  std::cout.flush();
  std::cerr.flush();
  // Verilator's explicit clone hook synchronizes any model-owned threading.
  // Build this runner with --threads 1; external fork-unsafe resources are
  // disallowed by the RAM-only, single-process Product host boundary.
  top.prepareClone();
  errno = 0;
  const pid_t pid = ::fork();
  const int forkError = errno;
  top.atClone();
  if (pid < 0) {
    ::close(pipeFds[0]);
    ::close(pipeFds[1]);
    throw std::runtime_error(std::string("fork-COW clone: ") + std::strerror(forkError));
  }
  if (pid == 0) {
    ::close(pipeFds[0]);
    try {
      const ReplayResult report = replay(top, ctx, mem, transport,
                                         replayCycles, command, expectedReply);
      const bool written = writeAll(pipeFds[1], &report, sizeof(report));
      ::close(pipeFds[1]);
      _exit(written ? 0 : 23);
    } catch (const std::exception& e) {
      std::cerr << "PRODUCT_FORKCOW_CHILD_ERROR: " << e.what() << '\n';
      std::cerr.flush();
      _exit(22);
    }
  }
  ::close(pipeFds[1]);
  ReplayResult report;
  const bool received = readAll(pipeFds[0], &report, sizeof(report));
  ::close(pipeFds[0]);
  int status = 0;
  while (::waitpid(pid, &status, 0) < 0) {
    if (errno == EINTR) continue;
    throw std::runtime_error(std::string("fork-COW waitpid: ") + std::strerror(errno));
  }
  if (!received || !WIFEXITED(status) || WEXITSTATUS(status) != 0)
    throw std::runtime_error("fork-COW child failed (exit=" +
      std::to_string(WIFEXITED(status) ? WEXITSTATUS(status) : -1) +
      ", signal=" +
      std::to_string(WIFSIGNALED(status) ? WTERMSIG(status) : 0) + ")");
  return report;
}

}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc < 5 || argc > 9)
      throw std::runtime_error(
        "usage: PRODUCT_FORKCOW FW_PAYLOAD.bin BOOT_MAX_CYCLES UART_TRIGGER "
        "REPLAY_CYCLES [REPEATS>=2] [UART_COMMAND|-] [UART_REPLY|-] [PROGRESS_CYCLES]");
    if (std::getenv("AETHERCORE_PRODUCT_SD_IMAGE") ||
        std::getenv("AETHERCORE_PRODUCT_DTB_IMAGE"))
      throw std::runtime_error("Product fork-COW forbids external SD/DTB images");
    const std::string firmware = argv[1];
    const std::uint64_t bootMax = std::stoull(argv[2], nullptr, 0);
    const std::string trigger = argv[3];
    const std::uint64_t replayCycles = std::stoull(argv[4], nullptr, 0);
    const std::uint64_t repeats = argc >= 6 ? std::stoull(argv[5], nullptr, 0) : 2;
    std::string command = argc >= 7 && std::string(argv[6]) != "-" ? argv[6] : "";
    const std::string reply = argc >= 8 && std::string(argv[7]) != "-" ? argv[7] : "";
    const std::uint64_t progress = argc >= 9 ? std::stoull(argv[8], nullptr, 0) : 0;
    if (trigger.empty() || bootMax == 0 || replayCycles == 0 || repeats < 2 || repeats > 32)
      throw std::runtime_error("invalid Product fork-COW configuration");
    if (!command.empty() && command.back() != '\n') command.push_back('\n');

    VerilatedContext context;
    context.commandArgs(argc, argv);
    ProductTop top{&context};
    Memory memory;
    memory.loadAtBase(firmware);
    TransportRuntimeState transport;
    Counts boot;
    Marker milestone(trigger), banner("OpenSBI v1.6");
    initialize(top, memory, transport);
    const auto bootStart = std::chrono::steady_clock::now();
    while (boot.cycles < bootMax && !milestone.matched()) {
      cycle(top, context, memory, transport, boot);
      if (boot.cycles == 4) top.reset = 0;
      if (top.io_uartValid) {
        const char ch = static_cast<char>(top.io_uartByte);
        milestone.push(ch);
        banner.push(ch);
        std::cout.put(ch);
        if (ch == '\n') std::cout.flush();
      }
      if (progress && boot.cycles % progress == 0) {
        const auto elapsed = std::chrono::duration<double>(
          std::chrono::steady_clock::now() - bootStart).count();
        std::cerr << "\nPRODUCT_FORKCOW_BOOT_PROGRESS cycles=" << boot.cycles
                  << " cps=" << (elapsed > 0 ? boot.cycles / elapsed : 0)
                  << " commits=" << boot.commits << '\n';
      }
      if (top.io_exitValid && !milestone.matched())
        throw std::runtime_error("Product exited before checkpoint UART milestone");
    }
    if (!milestone.matched() || !banner.matched() || boot.commits == 0)
      throw std::runtime_error("Product did not reach a real OpenSBI+retirement checkpoint");
    std::cout.flush();
    std::cerr << "\nPRODUCT_FORKCOW_READY cycles=" << boot.cycles
              << " commits=" << boot.commits
              << " stip=" << boot.stip << " seip=" << boot.seip
              << " repeat=" << repeats << " replay-cycles=" << replayCycles
              << " mode=single-process-ram-only-fork-cow\n";

    ReplayResult reference;
    for (std::uint64_t index = 0; index < repeats; ++index) {
      const auto start = std::chrono::steady_clock::now();
      const ReplayResult result = forkReplay(top, context, memory, transport,
                                            replayCycles, command, reply);
      if (index != 0 && !(reference == result))
        throw std::runtime_error("fork-COW replays diverged: CPU/trace/RAM/interrupt/transport state mismatch");
      if (index == 0) reference = result;
      const auto seconds = std::chrono::duration<double>(
        std::chrono::steady_clock::now() - start).count();
      std::cerr << "PRODUCT_FORKCOW_CHILD_PASS index=" << index
                << " seconds=" << seconds
                << " cycles=" << result.replayCycles
                << " commits=" << result.commits
                << " interrupts=" << result.interrupts
                << " stip=" << result.stip << " seip=" << result.seip
                << " trace-hash=0x" << std::hex << result.traceHash
                << " ram-hash=0x" << result.ramHash << std::dec << '\n';
    }
    std::cerr << "PRODUCT_FORKCOW_PASS boot-cycles=" << boot.cycles
              << " replays=" << repeats << " replay-cycles=" << replayCycles
              << " trace-hash=0x" << std::hex << reference.traceHash
              << " ram-hash=0x" << reference.ramHash << std::dec << '\n';
    return 0;
  } catch (const std::exception& e) {
    std::cerr << "PRODUCT_FORKCOW_ERROR: " << e.what() << '\n';
    return 1;
  }
}
