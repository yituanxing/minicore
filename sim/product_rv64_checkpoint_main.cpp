// RAM-only Product G3 cross-process RTL checkpoint qualification.
//
// This is deliberately separate from fork-COW: --savable captures all live
// Verilated flops (including CPU/ROB/CSR/cache/PLIC/UART), while the host
// record captures time, RAM, LR/SC reservation, transport and observation.
// The two files must be bound to the EXACT model and firmware SHA-256 by the
// operator script; no QEMU state is injected into a live out-of-order CPU.
#if defined(AETHERCORE_PRODUCT_SPI_SD) || defined(AETHERCORE_PRODUCT_DEBIAN)
#error "Product G3 checkpoint may not be linked to an SD1/Debian top"
#endif
#ifndef AETHERCORE_PRODUCT_CHECKPOINT_SAVABLE
#error "Product checkpoint requires a separate Verilator --savable model"
#endif
#include "VAetherCoreProductOpenSbiRV64SimTop.h"
#include "l32_opensbi_runtime.h"
#include "verilated.h"
#include "verilated_save.h"

#include <array>
#include <cctype>
#include <chrono>
#include <cstdlib>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <initializer_list>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {
using ProductTop = VAetherCoreProductOpenSbiRV64SimTop;
using aethercore::l32sim::Memory;
using aethercore::l32sim::TransportRuntimeState;
constexpr std::uint64_t kBasis = 14695981039346656037ULL;
constexpr std::uint64_t kPrime = 1099511628211ULL;
constexpr std::string_view kMagic = "AETG3CP2";
constexpr std::uint64_t kVersion = 2;
constexpr std::uint64_t kMaxUartTail = 256;

void mix(std::uint64_t& hash, std::uint64_t word) {
  for (unsigned shift = 0; shift != 64; shift += 8) {
    hash ^= static_cast<std::uint8_t>(word >> shift);
    hash *= kPrime;
  }
}

void writeU64(std::ostream& os, std::uint64_t value) {
  for (unsigned i = 0; i < 8; ++i) os.put(static_cast<char>(value >> (8 * i)));
  if (!os) throw std::runtime_error("checkpoint host write failed");
}

std::uint64_t readU64(std::istream& is) {
  std::uint64_t value = 0;
  for (unsigned i = 0; i < 8; ++i) {
    const int byte = is.get();
    if (byte == EOF) throw std::runtime_error("truncated checkpoint host file");
    value |= static_cast<std::uint64_t>(static_cast<unsigned char>(byte)) << (8 * i);
  }
  return value;
}

bool readBool(std::istream& is) {
  const auto value = readU64(is);
  if (value > 1) throw std::runtime_error("checkpoint boolean is not canonical");
  return value == 1;
}

std::uint32_t readU32(std::istream& is) {
  const auto value = readU64(is);
  if (value > std::numeric_limits<std::uint32_t>::max())
    throw std::runtime_error("checkpoint transport counter overflows uint32");
  return static_cast<std::uint32_t>(value);
}

void writeString(std::ostream& os, const std::string& value) {
  writeU64(os, value.size());
  os.write(value.data(), static_cast<std::streamsize>(value.size()));
  if (!os) throw std::runtime_error("checkpoint string write failed");
}

std::string readString(std::istream& is, std::uint64_t maxSize) {
  const auto size = readU64(is);
  if (size > maxSize) throw std::runtime_error("invalid checkpoint string length");
  std::string value(static_cast<std::size_t>(size), '\0');
  is.read(value.data(), static_cast<std::streamsize>(size));
  if (!is) throw std::runtime_error("truncated checkpoint string");
  return value;
}

bool validSha(const std::string& sha) {
  if (sha.size() != 64) return false;
  for (unsigned char c : sha) {
    if (!std::isxdigit(c) || std::isupper(c)) return false;
  }
  return true;
}

struct Observer {
  std::uint64_t cycles = 0;
  std::uint64_t commits = 0;
  std::uint64_t exceptions = 0;
  std::uint64_t interrupts = 0;
  std::uint64_t stip = 0;
  std::uint64_t seip = 0;
  std::uint64_t uartBytes = 0;
  std::uint64_t uartHash = kBasis;
  std::uint64_t traceHash = kBasis;
  std::string uartTail;
};

bool interruptCause(std::uint64_t cause, std::uint64_t code) {
  return cause == (0x80000000ULL | code) ||
      cause == (0x8000000000000000ULL | code);
}

// The observer is deliberately the same before/after restoring the model.
// Only a 500k-cycle post-checkpoint trace is hashed, avoiding unnecessary
// expensive digest work during the initial real OpenSBI boot prefix.
void observe(const ProductTop& top, Observer& o, bool strictTrace) {
  ++o.cycles;
  if (strictTrace) {
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_commit_valid));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_imemValid));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_imemAddr));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_memValid));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_memAddr));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_memReady));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_ptwValid));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_ptwAddr));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_uartValid));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_uartByte));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_mtime));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_mtimecmp));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_uartInterrupt));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_supervisorExternalInterrupt));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_timerInterrupt));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_exitValid));
    mix(o.traceHash, static_cast<std::uint64_t>(top.io_exitCode));
  }
  if (top.io_commit_valid) {
    ++o.commits;
    if (top.io_commit_exception) ++o.exceptions;
    if (strictTrace) {
      mix(o.traceHash, static_cast<std::uint64_t>(top.io_commit_pc));
      mix(o.traceHash, static_cast<std::uint64_t>(top.io_commit_rawInst));
      mix(o.traceHash, static_cast<std::uint64_t>(top.io_commit_instBytes));
      mix(o.traceHash, static_cast<std::uint64_t>(top.io_commit_exception));
      mix(o.traceHash, static_cast<std::uint64_t>(top.io_commit_exceptionCause));
    }
  }
  // Product interrupt retirement can occur without commit.valid.
  if (top.io_commit_interrupt) {
    ++o.interrupts;
    const auto cause = static_cast<std::uint64_t>(top.io_commit_interruptCause);
    if (interruptCause(cause, 5)) ++o.stip;
    if (interruptCause(cause, 9)) ++o.seip;
    if (strictTrace) {
      mix(o.traceHash, cause);
      mix(o.traceHash, static_cast<std::uint64_t>(top.io_commit_interruptPc));
    }
  }
  if (top.io_uartValid) {
    const auto ch = static_cast<std::uint8_t>(top.io_uartByte);
    ++o.uartBytes;
    o.uartHash ^= ch;
    o.uartHash *= kPrime;
    o.uartTail.push_back(static_cast<char>(ch));
    if (o.uartTail.size() > kMaxUartTail) o.uartTail.erase(0, 1);
  }
}

void cycle(ProductTop& top, VerilatedContext& ctx, Memory& memory,
           TransportRuntimeState& transport, Observer& o, bool strictTrace) {
  aethercore::l32sim::step(top, ctx, memory, transport, false, 0);
  observe(top, o, strictTrace);
}

void writeObserver(std::ostream& os, const Observer& o) {
  for (const auto field : {o.cycles, o.commits, o.exceptions, o.interrupts,
                           o.stip, o.seip, o.uartBytes, o.uartHash, o.traceHash})
    writeU64(os, field);
  writeString(os, o.uartTail);
}

Observer readObserver(std::istream& is) {
  Observer o;
  o.cycles = readU64(is);
  o.commits = readU64(is);
  o.exceptions = readU64(is);
  o.interrupts = readU64(is);
  o.stip = readU64(is);
  o.seip = readU64(is);
  o.uartBytes = readU64(is);
  o.uartHash = readU64(is);
  o.traceHash = readU64(is);
  o.uartTail = readString(is, kMaxUartTail);
  return o;
}

void saveHost(const std::string& filename, const std::string& modelId,
              const std::string& firmwareSha, const VerilatedContext& ctx,
              const Memory& memory, const TransportRuntimeState& transport,
              const Observer& o) {
  std::ofstream file(filename, std::ios::binary | std::ios::trunc);
  if (!file) throw std::runtime_error("cannot create checkpoint host file");
  file.write(kMagic.data(), static_cast<std::streamsize>(kMagic.size()));
  writeU64(file, kVersion);
  writeString(file, modelId);
  writeString(file, firmwareSha);
  writeU64(file, ctx.time());
  writeU64(file, aethercore::l32sim::dataMemoryWaitCycles());
  writeObserver(file, o);
  const auto& wait = transport.dataWait;
  writeU64(file, wait.active);
  writeU64(file, wait.qualificationPrinted);
  writeU64(file, wait.remaining);
  writeU64(file, wait.observedStallCycles);
  const auto ram = memory.snapshot();
  writeU64(file, ram.bytes.size());
  file.write(reinterpret_cast<const char*>(ram.bytes.data()),
             static_cast<std::streamsize>(ram.bytes.size()));
  writeU64(file, ram.reservationValid);
  writeU64(file, ram.reservationAddress);
  writeU64(file, ram.reservationSize);
  file.flush();
  if (!file) throw std::runtime_error("checkpoint host flush failed");
}

void restoreHost(const std::string& filename, const std::string& modelId,
                 const std::string& firmwareSha, VerilatedContext& ctx,
                 Memory& memory, TransportRuntimeState& transport, Observer& o) {
  std::ifstream file(filename, std::ios::binary);
  if (!file) throw std::runtime_error("cannot read checkpoint host file");
  std::array<char, 8> magic{};
  file.read(magic.data(), magic.size());
  if (!file || std::string_view(magic.data(), magic.size()) != kMagic ||
      readU64(file) != kVersion)
    throw std::runtime_error("wrong checkpoint format/version");
  if (readString(file, 64) != modelId)
    throw std::runtime_error("checkpoint model identity differs");
  if (readString(file, 64) != firmwareSha)
    throw std::runtime_error("checkpoint firmware SHA-256 differs");
  const auto savedTime = readU64(file);
  if (readU64(file) != aethercore::l32sim::dataMemoryWaitCycles())
    throw std::runtime_error("checkpoint memory-wait configuration differs");
  if (ctx.time() != 0) throw std::runtime_error("checkpoint context is not fresh");
  ctx.timeInc(savedTime);
  o = readObserver(file);
  transport.dataWait.active = readBool(file);
  transport.dataWait.qualificationPrinted = readBool(file);
  transport.dataWait.remaining = readU32(file);
  transport.dataWait.observedStallCycles = readU32(file);
  Memory::Snapshot ram;
  const auto size = readU64(file);
  if (size != aethercore::l32sim::kRamSize)
    throw std::runtime_error("checkpoint RAM extent is not 256 MiB");
  ram.bytes.resize(static_cast<std::size_t>(size));
  file.read(reinterpret_cast<char*>(ram.bytes.data()),
            static_cast<std::streamsize>(size));
  if (!file) throw std::runtime_error("checkpoint RAM is truncated");
  ram.reservationValid = readBool(file);
  ram.reservationAddress = readU64(file);
  const auto reservationSize = readU64(file);
  if (reservationSize > sizeof(std::uint64_t))
    throw std::runtime_error("checkpoint LR/SC reservation has invalid width");
  ram.reservationSize = static_cast<std::size_t>(reservationSize);
  if (file.get() != EOF) throw std::runtime_error("checkpoint has trailing bytes");
  memory.restore(ram);
  if (o.cycles != savedTime)
    throw std::runtime_error("checkpoint host cycles and Verilated time differ");
}

void saveRtl(const std::string& filename, ProductTop& top) {
  VerilatedSave out;
  out.open(filename.c_str());
  out << top;
  out.close();
}

void restoreRtl(const std::string& filename, ProductTop& top) {
  VerilatedRestore in;
  in.open(filename.c_str());
  in >> top;
  in.close();
}

void outputResult(const Observer& o, const Memory& memory,
                  const TransportRuntimeState& transport, const ProductTop& top) {
  std::cout << "PRODUCT_G3_PERSISTENT_CONTINUATION"
            << " cycles=" << o.cycles << " commits=" << o.commits
            << " exceptions=" << o.exceptions << " interrupts=" << o.interrupts
            << " stip=" << o.stip << " seip=" << o.seip
            << " uart_bytes=" << o.uartBytes
            << " uart_hash=" << std::hex << o.uartHash
            << " trace_hash=" << o.traceHash
            << " ram_hash=" << memory.fingerprint()
            << " mtime=" << static_cast<std::uint64_t>(top.io_mtime)
            << " mtimecmp=" << static_cast<std::uint64_t>(top.io_mtimecmp)
            << " exit_code=" << static_cast<std::uint64_t>(top.io_exitCode)
            << std::dec
            << " exit_valid=" << static_cast<unsigned>(top.io_exitValid)
            << " wait_active=" << transport.dataWait.active
            << " wait_remaining=" << transport.dataWait.remaining << '\n';
}

}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc != 9)
      throw std::runtime_error(
          "usage: PRODUCT_CHECKPOINT save|restore FW_PATH CHECKPOINT_PREFIX "
          "MODEL_SHA256 FIRMWARE_SHA256 BOOT_MAX_CYCLES UART_MARKER REPLAY_CYCLES");
    const std::string operation = argv[1];
    if (operation != "save" && operation != "restore")
      throw std::runtime_error("checkpoint operation must be save or restore");
    const std::string firmware = argv[2], prefix = argv[3];
    const std::string modelSha = argv[4], firmwareSha = argv[5];
    if (!validSha(modelSha) || !validSha(firmwareSha))
      throw std::runtime_error("checkpoint requires lowercase 64-character SHA-256 identities");
    const std::uint64_t bootMax = std::stoull(argv[6], nullptr, 0);
    const std::string marker = argv[7];
    const std::uint64_t replayCycles = std::stoull(argv[8], nullptr, 0);
    if (marker.empty() || marker.size() > 128 || bootMax < 10 ||
        replayCycles == 0 || replayCycles > 5000000)
      throw std::runtime_error("checkpoint must have bounded real UART marker and replay window");
    if (std::getenv("AETHERCORE_PRODUCT_SD_IMAGE") ||
        std::getenv("AETHERCORE_PRODUCT_DTB_IMAGE"))
      throw std::runtime_error("Product G3 checkpoint rejects all SD/Debian media");
    VerilatedContext ctx;
    // Keep plusargs constant across new processes, regardless of host CLI.
    char program[] = "product-checkpoint";
    char* stableArgs[] = {program, nullptr};
    ctx.commandArgs(1, stableArgs);
    ProductTop top{&ctx};
    Memory memory;
    TransportRuntimeState transport;
    Observer o;
    if (operation == "save") {
      memory.loadAtBase(firmware);
      aethercore::l32sim::initialize(top, memory, transport);
      bool foundMarker = false, sawBanner = false;
      for (std::uint64_t i = 0; i < bootMax && !foundMarker; ++i) {
        cycle(top, ctx, memory, transport, o, false);
        if (o.cycles == 4) top.reset = 0;
        sawBanner |= o.uartTail.find("OpenSBI v1.6") != std::string::npos;
        foundMarker = o.uartTail.find(marker) != std::string::npos;
        if (top.io_exitValid && !foundMarker)
          throw std::runtime_error("real Product CPU exited before checkpoint marker");
      }
      if (!foundMarker || !sawBanner || o.commits == 0 || top.reset)
        throw std::runtime_error("G3 did not reach OpenSBI + UART milestone with CPU retirement");
      // This boundary is AFTER low-phase RAM/AMO commit, rising-edge eval,
      // and Verilated timeInc. Never save a partially evaluated bus beat.
      saveRtl(prefix + ".pre.rtl", top);
      saveHost(prefix + ".pre.host", modelSha, firmwareSha, ctx, memory, transport, o);
      std::cout << "PRODUCT_G3_PERSISTENT_CHECKPOINT_SAVED cycles=" << o.cycles
                << " commits=" << o.commits << " mode=full-rtl-and-host-not-fork\n";
    } else {
      restoreHost(prefix + ".pre.host", modelSha, firmwareSha,
                  ctx, memory, transport, o);
      restoreRtl(prefix + ".pre.rtl", top);
      if (top.reset) throw std::runtime_error("restored Product CPU remains in reset");
      std::cout << "PRODUCT_G3_PERSISTENT_CHECKPOINT_RESTORED cycles=" << o.cycles
                << " commits=" << o.commits << " mode=new-process-disk-snapshot\n";
    }
    // Check exactly the same *post-snapshot* 500k clocks in two processes.
    o.traceHash = kBasis;
    for (std::uint64_t i = 0; i < replayCycles; ++i)
      cycle(top, ctx, memory, transport, o, true);
    outputResult(o, memory, transport, top);
    saveRtl(prefix + (operation == "save" ? ".continuous.rtl" : ".restored.rtl"), top);
    saveHost(prefix + (operation == "save" ? ".continuous.host" : ".restored.host"),
             modelSha, firmwareSha, ctx, memory, transport, o);
    return 0;
  } catch (const std::exception& e) {
    std::cerr << "PRODUCT_G3_PERSISTENT_CHECKPOINT_REJECTED: " << e.what() << '\n';
    return 1;
  }
}
