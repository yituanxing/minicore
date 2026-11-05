// Real RAM-initramfs Linux cross-process checkpoint qualification. The G4 and
// G5 hosts share the same complete-state protocol but compile against distinct
// Product RTL tops and have incompatible on-disk checkpoint identities.
// The G5 path is ONLY the RAM-initramfs Debian D1/D2 Product top. The external
// SD1 card is a different device model and must never silently be omitted.
#if defined(AETHERCORE_PRODUCT_SPI_SD)
#error "RAM-only Product checkpoint cannot carry an SD card"
#endif
#if defined(AETHERCORE_PRODUCT_CHECKPOINT_G5)
#if !defined(AETHERCORE_PRODUCT_DEBIAN)
#error "G5 checkpoint requires the Debian Product top"
#endif
#include "VAetherCoreProductDebianRV64SimTop.h"
#else
#if defined(AETHERCORE_PRODUCT_DEBIAN)
#error "G4 checkpoint cannot carry a Debian Product top"
#endif
#include "VAetherCoreProductOpenSbiRV64SimTop.h"
#endif
#ifndef AETHERCORE_PRODUCT_CHECKPOINT_SAVABLE
#error "Product checkpoint requires a --savable RTL archive"
#endif
#include "l32_opensbi_runtime.h"
#include "verilated.h"
#include "verilated_save.h"

#include <array>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <initializer_list>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <string_view>

namespace {
#if defined(AETHERCORE_PRODUCT_CHECKPOINT_G5)
using Top = VAetherCoreProductDebianRV64SimTop;
constexpr std::string_view kMagic = "AETG5CP1";
constexpr std::string_view kScope = "PRODUCT_G5";
constexpr std::uint64_t kMaxBootCycles = 1800000000ULL;
#else
using Top = VAetherCoreProductOpenSbiRV64SimTop;
constexpr std::string_view kMagic = "AETG4CP1";
constexpr std::string_view kScope = "PRODUCT_G4";
constexpr std::uint64_t kMaxBootCycles = 100000000ULL;
#endif
using aethercore::l32sim::Memory;
using aethercore::l32sim::TransportRuntimeState;
constexpr std::uint64_t kFnv = 14695981039346656037ULL;
constexpr std::uint64_t kPrime = 1099511628211ULL;
constexpr std::uint64_t kVersion = 1;
constexpr std::size_t kMaxUart = 32ULL << 20;
constexpr std::size_t kRecent = 16;
constexpr std::uint64_t kLinuxBase = 0x80200000ULL;
constexpr std::string_view kLinuxMarker = "Linux version 6.6.143";

void mix(std::uint64_t& hash, std::uint64_t value) {
  for (unsigned i = 0; i < 8; ++i) {
    hash ^= static_cast<std::uint8_t>(value >> (8 * i));
    hash *= kPrime;
  }
}
void put64(std::ostream& os, std::uint64_t value) {
  for (unsigned i = 0; i < 8; ++i) os.put(static_cast<char>(value >> (8 * i)));
  if (!os) throw std::runtime_error("G4 host checkpoint write failed");
}
std::uint64_t get64(std::istream& is) {
  std::uint64_t out = 0;
  for (unsigned i = 0; i < 8; ++i) {
    const int ch = is.get();
    if (ch == EOF) throw std::runtime_error("G4 host checkpoint truncated");
    out |= static_cast<std::uint64_t>(static_cast<unsigned char>(ch)) << (8 * i);
  }
  return out;
}
bool getBool(std::istream& is) {
  const auto n = get64(is);
  if (n > 1) throw std::runtime_error("noncanonical G4 boolean");
  return n != 0;
}
std::uint32_t get32(std::istream& is) {
  const auto n = get64(is);
  if (n > std::numeric_limits<std::uint32_t>::max())
    throw std::runtime_error("G4 transport counter overflow");
  return static_cast<std::uint32_t>(n);
}
void putString(std::ostream& os, const std::string& str) {
  put64(os, str.size());
  os.write(str.data(), static_cast<std::streamsize>(str.size()));
  if (!os) throw std::runtime_error("G4 string checkpoint write failed");
}
std::string getString(std::istream& is, std::size_t limit) {
  const auto n = get64(is);
  if (n > limit) throw std::runtime_error("G4 checkpoint string exceeds bound");
  std::string out(static_cast<std::size_t>(n), '\0');
  is.read(out.data(), static_cast<std::streamsize>(n));
  if (!is) throw std::runtime_error("truncated G4 checkpoint string");
  return out;
}
bool validSha(const std::string& str) {
  if (str.size() != 64) return false;
  for (unsigned char ch : str)
    if (!std::isxdigit(ch) || std::isupper(ch)) return false;
  return true;
}
bool endsWith(const std::string& text, const std::string& end) {
  return !end.empty() && text.size() >= end.size() &&
      text.compare(text.size() - end.size(), end.size(), end) == 0;
}
bool interruptCause(std::uint64_t cause, std::uint64_t code) {
  return cause == (0x80000000ULL | code) ||
      cause == (0x8000000000000000ULL | code);
}

struct Policy {
  std::string marker;
  std::string uartTrigger;
  std::string uartInput;
  std::uint64_t postInputBudget = 0;
};

// These are the actual host-owned progress values of opensbi_boot_main.cpp,
// including UART injection/SEIP and deadline state. RTL alone cannot resume
// an in-flight console command, nor can a mere CPU-register/QEMU snapshot.
struct Observer {
  std::uint64_t cycles = 0;
  std::uint64_t commits = 0;
  std::uint64_t compressed = 0;
  std::uint64_t opensbiCompressed = 0;
  std::uint64_t linuxCompressed = 0;
  std::uint64_t exceptions = 0;
  std::uint64_t interrupts = 0;
  std::uint64_t stip = 0;
  std::uint64_t seip = 0;
  std::uint64_t lastExceptionCause = 0;
  std::uint64_t lastExceptionValue = 0;
  std::uint64_t lastExceptionPc = 0;
  std::uint64_t milestoneCommitCount = 0;
  std::uint64_t uartHash = kFnv;
  std::uint64_t traceHash = kFnv;
  std::string uart;
  bool sawOpenSbiBanner = false;
  bool sawLinuxMarker = false;
  bool inputStarted = false;
  std::uint64_t inputIndex = 0;
  std::uint64_t seipAtInputStart = 0;
  bool sawRxInterrupt = false;
  bool sawPostInputSeip = false;
  bool deadlineArmed = false;
  std::uint64_t inputCompleteCycle = 0;
  std::uint64_t postInputDeadline = 0;
  std::uint64_t nextProgressCycle = 10000000;
  std::uint64_t previousProgressCycle = 0;
  std::uint64_t previousProgressCommits = 0;
  std::uint64_t lastUartCycle = 0;
  std::array<std::uint64_t, kRecent> recentPcs{};
  std::uint64_t recentCount = 0;
  std::uint64_t recentIndex = 0;
};

void writeObserver(std::ostream& os, const Observer& o) {
  for (auto v : {o.cycles, o.commits, o.compressed, o.opensbiCompressed,
                  o.linuxCompressed, o.exceptions, o.interrupts, o.stip, o.seip,
                  o.lastExceptionCause, o.lastExceptionValue, o.lastExceptionPc,
                  o.milestoneCommitCount, o.uartHash, o.traceHash}) put64(os, v);
  putString(os, o.uart);
  for (auto v : {o.sawOpenSbiBanner, o.sawLinuxMarker, o.inputStarted}) put64(os, v);
  put64(os, o.inputIndex);
  put64(os, o.seipAtInputStart);
  for (auto v : {o.sawRxInterrupt, o.sawPostInputSeip, o.deadlineArmed}) put64(os, v);
  for (auto v : {o.inputCompleteCycle, o.postInputDeadline, o.nextProgressCycle,
                  o.previousProgressCycle, o.previousProgressCommits, o.lastUartCycle})
    put64(os, v);
  for (auto pc : o.recentPcs) put64(os, pc);
  put64(os, o.recentCount);
  put64(os, o.recentIndex);
}
Observer readObserver(std::istream& is) {
  Observer o;
  o.cycles = get64(is);
  o.commits = get64(is);
  o.compressed = get64(is);
  o.opensbiCompressed = get64(is);
  o.linuxCompressed = get64(is);
  o.exceptions = get64(is);
  o.interrupts = get64(is);
  o.stip = get64(is);
  o.seip = get64(is);
  o.lastExceptionCause = get64(is);
  o.lastExceptionValue = get64(is);
  o.lastExceptionPc = get64(is);
  o.milestoneCommitCount = get64(is);
  o.uartHash = get64(is);
  o.traceHash = get64(is);
  o.uart = getString(is, kMaxUart);
  o.sawOpenSbiBanner = getBool(is);
  o.sawLinuxMarker = getBool(is);
  o.inputStarted = getBool(is);
  o.inputIndex = get64(is);
  o.seipAtInputStart = get64(is);
  o.sawRxInterrupt = getBool(is);
  o.sawPostInputSeip = getBool(is);
  o.deadlineArmed = getBool(is);
  o.inputCompleteCycle = get64(is);
  o.postInputDeadline = get64(is);
  o.nextProgressCycle = get64(is);
  o.previousProgressCycle = get64(is);
  o.previousProgressCommits = get64(is);
  o.lastUartCycle = get64(is);
  for (auto& pc : o.recentPcs) pc = get64(is);
  o.recentCount = get64(is);
  o.recentIndex = get64(is);
  if (o.recentCount > kRecent || o.recentIndex >= kRecent)
    throw std::runtime_error("G4 recent-PC ring out of bounds");
  return o;
}

void writeHost(const std::string& path, const std::string& modelSha,
               const std::string& firmwareSha, const Policy& policy,
               const VerilatedContext& ctx, const Memory& mem,
               const TransportRuntimeState& transport, const Observer& o) {
  std::ofstream os(path, std::ios::binary | std::ios::trunc);
  if (!os) throw std::runtime_error("cannot create G4 host checkpoint");
  os.write(kMagic.data(), static_cast<std::streamsize>(kMagic.size()));
  put64(os, kVersion);
  putString(os, modelSha);
  putString(os, firmwareSha);
  putString(os, policy.marker);
  putString(os, policy.uartTrigger);
  putString(os, policy.uartInput);
  put64(os, policy.postInputBudget);
  put64(os, ctx.time());
  put64(os, aethercore::l32sim::dataMemoryWaitCycles());
  writeObserver(os, o);
  const auto& wait = transport.dataWait;
  for (auto value : {static_cast<std::uint64_t>(wait.active),
                     static_cast<std::uint64_t>(wait.qualificationPrinted),
                     static_cast<std::uint64_t>(wait.remaining),
                     static_cast<std::uint64_t>(wait.observedStallCycles)}) put64(os, value);
  const auto ram = mem.snapshot();
  put64(os, ram.bytes.size());
  os.write(reinterpret_cast<const char*>(ram.bytes.data()),
           static_cast<std::streamsize>(ram.bytes.size()));
  put64(os, ram.reservationValid);
  put64(os, ram.reservationAddress);
  put64(os, ram.reservationSize);
  os.flush();
  if (!os) throw std::runtime_error("G4 host checkpoint incomplete");
}

void readHost(const std::string& path, const std::string& modelSha,
              const std::string& firmwareSha, const Policy& policy,
              VerilatedContext& ctx, Memory& mem,
              TransportRuntimeState& transport, Observer& o) {
  std::ifstream is(path, std::ios::binary);
  if (!is) throw std::runtime_error("cannot open G4 host checkpoint");
  std::array<char, 8> magic{};
  is.read(magic.data(), magic.size());
  if (!is || std::string_view(magic.data(), magic.size()) != kMagic ||
      get64(is) != kVersion)
    throw std::runtime_error("G4 checkpoint format/version differs");
  if (getString(is, 64) != modelSha || getString(is, 64) != firmwareSha)
    throw std::runtime_error("G4 model or Linux firmware identity differs");
  if (getString(is, 128) != policy.marker ||
      getString(is, 128) != policy.uartTrigger ||
      getString(is, 4096) != policy.uartInput ||
      get64(is) != policy.postInputBudget)
    throw std::runtime_error("G4 UART input/milestone policy differs");
  const auto savedTime = get64(is);
  if (get64(is) != aethercore::l32sim::dataMemoryWaitCycles())
    throw std::runtime_error("G4 memory-wait configuration differs");
  if (ctx.time() != 0) throw std::runtime_error("G4 restore context is not fresh");
  o = readObserver(is);
  transport.dataWait.active = getBool(is);
  transport.dataWait.qualificationPrinted = getBool(is);
  transport.dataWait.remaining = get32(is);
  transport.dataWait.observedStallCycles = get32(is);
  const auto bytes = get64(is);
  if (bytes != aethercore::l32sim::kRamSize)
    throw std::runtime_error("G4 checkpoint DDR extent differs");
  Memory::Snapshot ram;
  ram.bytes.resize(static_cast<std::size_t>(bytes));
  is.read(reinterpret_cast<char*>(ram.bytes.data()),
          static_cast<std::streamsize>(bytes));
  if (!is) throw std::runtime_error("truncated G4 host DDR image");
  ram.reservationValid = getBool(is);
  ram.reservationAddress = get64(is);
  const auto reservationBytes = get64(is);
  if (reservationBytes > 8) throw std::runtime_error("invalid G4 LR/SC reservation size");
  ram.reservationSize = static_cast<std::size_t>(reservationBytes);
  if (is.get() != EOF) throw std::runtime_error("trailing G4 host checkpoint bytes");
  mem.restore(ram);
  ctx.timeInc(savedTime);
  if (o.cycles != savedTime || o.inputIndex > policy.uartInput.size())
    throw std::runtime_error("G4 guest-time/UART progress differs on restore");
  std::uint64_t uartHash = kFnv;
  for (unsigned char ch : o.uart) {
    uartHash ^= ch;
    uartHash *= kPrime;
  }
  if (uartHash != o.uartHash)
    throw std::runtime_error("G4 UART bytes/fingerprint differ");
}

void saveRtl(const std::string& path, Top& top) {
  VerilatedSave out;
  out.open(path.c_str());
  out << top;
  out.close();
}
void readRtl(const std::string& path, Top& top) {
  VerilatedRestore in;
  in.open(path.c_str());
  in >> top;
  in.close();
}

void observe(Top& top, Observer& o, const Policy& policy, bool accepted,
             bool strictTrace, bool emitUart) {
  ++o.cycles;
  const auto cycleIndex = o.cycles - 1;
  if (o.cycles == 4) top.reset = 0;
  if (top.reset) return;
  if (cycleIndex >= o.nextProgressCycle) {
    o.previousProgressCycle = cycleIndex;
    o.previousProgressCommits = o.commits;
    o.nextProgressCycle += 10000000;
  }
  if (strictTrace) {
    for (auto v : {static_cast<std::uint64_t>(top.io_commit_valid),
                   static_cast<std::uint64_t>(top.io_imemValid),
                   static_cast<std::uint64_t>(top.io_imemAddr),
                   static_cast<std::uint64_t>(top.io_memValid),
                   static_cast<std::uint64_t>(top.io_memAddr),
                   static_cast<std::uint64_t>(top.io_memReady),
                   static_cast<std::uint64_t>(top.io_ptwValid),
                   static_cast<std::uint64_t>(top.io_ptwAddr),
                   static_cast<std::uint64_t>(top.io_uartValid),
                   static_cast<std::uint64_t>(top.io_uartByte),
                   static_cast<std::uint64_t>(top.io_mtime),
                   static_cast<std::uint64_t>(top.io_mtimecmp),
                   static_cast<std::uint64_t>(top.io_uartInterrupt),
                   static_cast<std::uint64_t>(top.io_supervisorExternalInterrupt),
                   static_cast<std::uint64_t>(top.io_timerInterrupt),
                   static_cast<std::uint64_t>(top.io_exitValid),
                   static_cast<std::uint64_t>(top.io_exitCode)})
      mix(o.traceHash, v);
  }
  if (accepted) {
    ++o.inputIndex;
    if (o.inputIndex == policy.uartInput.size()) {
      o.inputCompleteCycle = cycleIndex;
      if (policy.postInputBudget) {
        o.deadlineArmed = true;
        o.postInputDeadline = cycleIndex + policy.postInputBudget;
      }
    }
  }
  if (o.inputStarted && top.io_uartRxInterrupt) o.sawRxInterrupt = true;
  if (top.io_uartValid) {
    const auto ch = static_cast<std::uint8_t>(top.io_uartByte);
    o.uart.push_back(static_cast<char>(ch));
    if (o.uart.size() > kMaxUart)
      throw std::runtime_error("G4 UART output exceeds bounded checkpoint capacity");
    o.uartHash ^= ch;
    o.uartHash *= kPrime;
    o.lastUartCycle = cycleIndex;
    if (emitUart) {
      std::cout.put(static_cast<char>(ch));
      if (ch == '\n') std::cout.flush();
    }
    // Suffix detection matches opensbi_boot_main.cpp without repeatedly
    // scanning the entire potentially large Linux UART history.
    o.sawOpenSbiBanner |= endsWith(o.uart, "OpenSBI v1.6");
    if (!o.inputStarted && !policy.uartInput.empty() &&
        endsWith(o.uart, policy.uartTrigger)) {
      o.inputStarted = true;
      o.seipAtInputStart = o.seip;
    }
    if (!o.sawLinuxMarker && endsWith(o.uart, policy.marker)) {
      o.sawLinuxMarker = true;
      o.milestoneCommitCount = o.commits;
    }
  }
  if (top.io_commit_valid) {
    ++o.commits;
    const auto pc = static_cast<std::uint64_t>(top.io_commit_pc);
    if (static_cast<unsigned>(top.io_commit_instBytes) == 2U) {
      ++o.compressed;
      if (pc < kLinuxBase) ++o.opensbiCompressed;
      else ++o.linuxCompressed;
    }
    o.recentPcs[o.recentIndex] = pc;
    o.recentIndex = (o.recentIndex + 1) % kRecent;
    if (o.recentCount < kRecent) ++o.recentCount;
    if (top.io_commit_exception) {
      ++o.exceptions;
      o.lastExceptionPc = pc;
      o.lastExceptionCause = static_cast<std::uint64_t>(top.io_commit_exceptionCause);
      o.lastExceptionValue = static_cast<std::uint64_t>(top.io_commit_exceptionValue);
    }
    if (strictTrace) {
      for (auto v : {pc, static_cast<std::uint64_t>(top.io_commit_rawInst),
                     static_cast<std::uint64_t>(top.io_commit_instBytes),
                     static_cast<std::uint64_t>(top.io_commit_exception),
                     static_cast<std::uint64_t>(top.io_commit_exceptionCause)})
        mix(o.traceHash, v);
    }
  }
  if (top.io_commit_interrupt) {
    ++o.interrupts;
    const auto cause = static_cast<std::uint64_t>(top.io_commit_interruptCause);
    if (interruptCause(cause, 5)) ++o.stip;
    if (interruptCause(cause, 9)) {
      ++o.seip;
      if (o.inputStarted && o.seip > o.seipAtInputStart)
        o.sawPostInputSeip = true;
    }
    if (strictTrace) {
      mix(o.traceHash, cause);
      mix(o.traceHash, static_cast<std::uint64_t>(top.io_commit_interruptPc));
    }
  }
  if (o.deadlineArmed && cycleIndex >= o.postInputDeadline &&
      !o.sawPostInputSeip)
    throw std::runtime_error("G4 UART input completion deadline expired");
}

void oneCycle(Top& top, VerilatedContext& ctx, Memory& mem,
              TransportRuntimeState& transport, Observer& obs,
              const Policy& policy, bool strictTrace, bool emitUart) {
  const bool inject = obs.inputStarted && obs.inputIndex < policy.uartInput.size();
  const auto ch = inject ? static_cast<std::uint8_t>(policy.uartInput[obs.inputIndex]) : 0;
  const bool accepted = aethercore::l32sim::step(top, ctx, mem, transport, inject, ch);
  observe(top, obs, policy, accepted && inject, strictTrace, emitUart);
}

void outputResult(const Observer& o, const VerilatedContext& ctx,
                  const Memory& mem, const TransportRuntimeState& transport,
                  const Top& top) {
  std::cout << kScope << "_PERSISTENT_CONTINUATION"
            << " cycles=" << o.cycles << " time=" << ctx.time()
            << " commits=" << o.commits << " compressed=" << o.compressed
            << " linux_compressed=" << o.linuxCompressed
            << " exceptions=" << o.exceptions << " interrupts=" << o.interrupts
            << " stip=" << o.stip << " seip=" << o.seip
            << " uart_bytes=" << o.uart.size() << " uart_hash=0x" << std::hex << o.uartHash
            << " trace_hash=0x" << o.traceHash
            << " ram_hash=0x" << mem.fingerprint()
            << " mtime=0x" << static_cast<std::uint64_t>(top.io_mtime)
            << " mtimecmp=0x" << static_cast<std::uint64_t>(top.io_mtimecmp)
            << std::dec << " linux_banner=" << o.sawLinuxMarker
            << " opensbi_banner=" << o.sawOpenSbiBanner
            << " uart_input_index=" << o.inputIndex
            << " uart_rx_irq=" << o.sawRxInterrupt
            << " uart_seip=" << o.sawPostInputSeip
            << " wait_active=" << transport.dataWait.active
            << " wait_remaining=" << transport.dataWait.remaining << '\n';
}
}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc != 9 && argc != 12)
      throw std::runtime_error("usage: PRODUCT_CHECKPOINT save|restore FW PREFIX MODEL_SHA FW_SHA BOOT_MAX LINUX_MARKER REPLAY_CYCLES [UART_TRIGGER UART_COMMAND POST_INPUT_BUDGET]");
    const std::string mode = argv[1], firmware = argv[2], prefix = argv[3];
    const std::string modelSha = argv[4], fwSha = argv[5];
    if ((mode != "save" && mode != "restore") || !validSha(modelSha) || !validSha(fwSha))
      throw std::runtime_error("G4 checkpoint mode or model/firmware SHA-256 invalid");
    const std::uint64_t bootMax = std::stoull(argv[6], nullptr, 0);
    const std::uint64_t replayCycles = std::stoull(argv[8], nullptr, 0);
    Policy policy{argv[7], argc == 12 ? argv[9] : "", argc == 12 ? argv[10] : "",
                  argc == 12 ? std::stoull(argv[11], nullptr, 0) : 0};
    if (!policy.uartInput.empty() && policy.uartInput.back() != '\n')
      policy.uartInput.push_back('\n');
    if (policy.marker != kLinuxMarker || bootMax == 0 || bootMax > kMaxBootCycles ||
        replayCycles == 0 || replayCycles > 5000000 ||
        policy.uartTrigger.size() > 128 || policy.uartInput.size() > 4096 ||
        policy.postInputBudget > 5000000)
      throw std::runtime_error("G4 requires Linux 6.6.143 marker, <=100M boot, bounded replay and UART policy");
    if (std::getenv("AETHERCORE_PRODUCT_SD_IMAGE") ||
        std::getenv("AETHERCORE_PRODUCT_DTB_IMAGE"))
      throw std::runtime_error("G4 checkpoint refuses SD1/Debian media");
    VerilatedContext ctx;
    char program[] = "product-g4-checkpoint";
    char* stableArgs[] = {program, nullptr};
    ctx.commandArgs(1, stableArgs);
    Top top{&ctx};
    Memory mem;
    TransportRuntimeState transport;
    Observer o;
    std::uint64_t checkpointAt = 0;
#if defined(AETHERCORE_PRODUCT_CHECKPOINT_G5)
    // On the Debian Product top a Linux-banner-only checkpoint saves little
    // of the costly pre-PID1 path. Allow a deliberate later cycle boundary;
    // the bounded short CI sets 100M, while manual long runs can retain a
    // checkpoint near the actual failing phase without changing guest state.
    if (const char* target = std::getenv("AETHERCORE_PRODUCT_G5_CHECKPOINT_AT_CYCLES"))
      checkpointAt = std::stoull(target, nullptr, 0);
    if (checkpointAt > bootMax)
      throw std::runtime_error("G5 checkpoint target exceeds bounded boot window");
#endif
    if (mode == "save") {
      mem.loadAtBase(firmware);
      aethercore::l32sim::initialize(top, mem, transport);
      o.inputStarted = !policy.uartInput.empty() && policy.uartTrigger.empty();
      while (o.cycles < bootMax && (!o.sawLinuxMarker || o.cycles < checkpointAt)) {
        oneCycle(top, ctx, mem, transport, o, policy, false, true);
        if (o.cycles % 10000000 == 0)
          std::cerr << kScope << "_PERSISTENT_BOOT_PROGRESS cycles=" << o.cycles
                    << " commits=" << o.commits << " linux_banner=" << o.sawLinuxMarker
                    << " uart_bytes=" << o.uart.size() << '\n';
        if (top.io_exitValid && !o.sawLinuxMarker)
          throw std::runtime_error("Product G4 exited before Linux UART banner");
      }
      if (!o.sawLinuxMarker || !o.sawOpenSbiBanner || o.commits == 0 || top.reset)
        throw std::runtime_error("G4 real Product Linux 6.6.143 banner never reached");
      // Complete-cycle barrier: low-phase AMO/RAM commit, high eval,
      // host UART/interrupt observation and Verilated timeInc all finished.
      saveRtl(prefix + ".pre.rtl", top);
      writeHost(prefix + ".pre.host", modelSha, fwSha, policy,
                ctx, mem, transport, o);
      // The Linux version marker is a prefix of its boot-log line. Separate
      // evidence from that unfinished guest UART line before proof parsing.
      std::cout << '\n' << kScope << "_PERSISTENT_CHECKPOINT_SAVED cycles=" << o.cycles
                << " commits=" << o.commits << " opensbi=1 linux_banner=1"
                << " mode=full-rtl-and-host-not-fork\n";
    } else {
      readHost(prefix + ".pre.host", modelSha, fwSha, policy,
               ctx, mem, transport, o);
      readRtl(prefix + ".pre.rtl", top);
      if (top.reset || !o.sawOpenSbiBanner || !o.sawLinuxMarker)
        throw std::runtime_error("G4 checkpoint does not represent running Linux");
      std::cout << kScope << "_PERSISTENT_CHECKPOINT_RESTORED cycles=" << o.cycles
                << " commits=" << o.commits << " opensbi=1 linux_banner=1"
                << " mode=new-process-disk-snapshot\n";
    }
    o.traceHash = kFnv;
    for (std::uint64_t i = 0; i < replayCycles; ++i)
      oneCycle(top, ctx, mem, transport, o, policy, true, false);
    outputResult(o, ctx, mem, transport, top);
    saveRtl(prefix + (mode == "save" ? ".continuous.rtl" : ".restored.rtl"), top);
    writeHost(prefix + (mode == "save" ? ".continuous.host" : ".restored.host"),
              modelSha, fwSha, policy, ctx, mem, transport, o);
    return 0;
  } catch (const std::exception& e) {
    std::cerr << kScope << "_PERSISTENT_CHECKPOINT_REJECTED: " << e.what() << '\n';
    return 1;
  }
}
