// Product G3 GSIM host transport: the same synthesizable Product CPU/MemoryHub
// as the standard Verilator runner, with the same host DDR/LR-SC/AMO owner.
// This is a transport candidate until fixed-cycle GSIM/Verilator A/B matches.
#ifndef AETHERCORE_PRODUCT_GSIM_HOST_BRIDGE
#error "Product GSIM requires generated same-cycle productHostBridge; a stale response is not a valid Product CPU run"
#endif
#define AETHERCORE_GSIM_HOST_MEMORY_ONLY
#include "l32_opensbi_runtime.h"
#include "product_gsim_port_adapter.h"

#include <array>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <exception>
#include <memory>
#include <stdexcept>
#include <string>

namespace {
using aethercore::l32sim::Memory;
using aethercore::l32sim::dataBytesFromMemSize;
using aethercore::product_gsim::Commit;
using aethercore::product_gsim::Model;
using aethercore::product_gsim::Ports;
using aethercore::product_gsim::Request;
using aethercore::product_gsim::Response;

constexpr std::uint64_t kHashBasis = 14695981039346656037ULL;
constexpr std::uint64_t kHashPrime = 1099511628211ULL;
constexpr std::size_t kRecentCommitCount = 16;
constexpr std::uint64_t kResetTicks = 4;  // Same four reset steps as opensbi_boot_main.cpp.

void hashWord(std::uint64_t& digest, std::uint64_t word) {
  for (unsigned shift = 0; shift != 64; shift += 8) {
    digest ^= static_cast<std::uint8_t>(word >> shift);
    digest *= kHashPrime;
  }
}

Response readResponses(const Request& request, const Memory& memory) {
  Response response{};
  if (request.imemValid) {
    const auto width = static_cast<std::size_t>(request.imemBytes);
    response.imemFault = (width != 2 && width != 4) ||
                         !memory.contains(request.imemAddr, width);
    if (!response.imemFault)
      response.imemInst = memory.readInstruction(request.imemAddr, width);
  }
  if (request.memValid) {
    const auto width = dataBytesFromMemSize(request.memSize);
    response.memFault = !memory.contains(request.memAddr, width);
    if (!response.memFault) {
      response.memRdata = request.memAtomic
          ? memory.atomicResponse(request.memAddr, width, request.memAtomicOp)
          : memory.readData(request.memAddr, width);
    }
  }
  if (request.ptwValid) {
    constexpr std::size_t width = 8;
    response.ptwFault = !memory.contains(request.ptwAddr, width);
    if (!response.ptwFault)
      response.ptwRdata = memory.readData(request.ptwAddr, width);
  }
  return response;
}

void commitAcceptedMemory(const Request& request, const Response& response,
                          Memory& memory, bool reset) {
  if (reset || !request.memValid || !response.memReady || response.memFault) return;
  const auto width = dataBytesFromMemSize(request.memSize);
  if (request.memAtomic) {
    memory.commitAtomic(request.memAddr, width, request.memWdata,
                        request.memWmask, request.memAtomicOp);
  } else if (request.memWrite) {
    memory.writeMasked(request.memAddr, request.memWdata, request.memWmask, width);
    memory.clearReservation();
  }
}

struct HostBridgeContext {
  Ports* ports;
  const Memory* memory;
  bool fastUnqualified;
  std::uint64_t calls = 0;
  std::uint64_t validFetchCalls = 0;
  std::uint64_t validDataCalls = 0;
  std::uint64_t validPtwCalls = 0;
};

// The pinned GSIM emitter invokes this between dependent supernode
// evaluations. Driving RAM only between top->step() calls would introduce a
// bogus full-cycle instruction/PTW/data delay versus Verilator's low-phase
// combinational protocol.
void hostBridge(void* opaque) {
  auto& context = *static_cast<HostBridgeContext*>(opaque);
  const Request request = context.fastUnqualified
      ? context.ports->requestFastUnqualified() : context.ports->request();
  context.ports->respond(readResponses(request, *context.memory));
  ++context.calls;
  context.validFetchCalls += request.imemValid;
  context.validDataCalls += request.memValid;
  context.validPtwCalls += request.ptwValid;
}

bool containsTail(const std::string& tail, const char* needle) {
  return tail.find(needle) != std::string::npos;
}

void printRecentPcs(const std::array<std::uint64_t, kRecentCommitCount>& recent,
                    std::size_t count, std::size_t next) {
  for (std::size_t i = 0; i < count; ++i) {
    const auto slot = (next + kRecentCommitCount - count + i) % kRecentCommitCount;
    std::printf("%s0x%llx", i ? "," : "",
                static_cast<unsigned long long>(recent[slot]));
  }
}
}  // namespace

int main(int argc, char** argv) {
  if (argc < 3 || argc > 6) {
    std::fprintf(stderr,
        "usage: product_gsim_prefix_main [--bare|--memory|FIRMWARE] MAX_CYCLES [UART_MILESTONE] [--linux|--fast-g4-unqualified]\n");
    return 2;
  }
  try {
    const std::string image = argv[1];
    const std::uint64_t maxCycles = std::stoull(argv[2], nullptr, 0);
    if (maxCycles <= kResetTicks) throw std::invalid_argument("MAX_CYCLES must exceed reset steps");
    const bool bareProbe = image == "--bare";
    const bool memoryProbe = image == "--memory";
    bool requireLinux = false;
    bool fastUnqualified = false;
    const char* milestone = "Test payload running";
    bool hasMilestone = false;
    for (int i = 3; i < argc; ++i) {
      const std::string arg = argv[i];
      if (arg == "--linux" && !requireLinux) requireLinux = true;
      else if (arg == "--fast-g4-unqualified" && !fastUnqualified) fastUnqualified = true;
      else if (arg.rfind("--", 0) != 0 && !hasMilestone) {
        milestone = argv[i];
        hasMilestone = true;
      } else {
        throw std::invalid_argument("unknown or duplicate Product GSIM option");
      }
    }
    if (fastUnqualified && (bareProbe || memoryProbe || requireLinux))
      throw std::invalid_argument("fast G4 is only for fixed-cycle firmware prefixes, not probes or Linux milestone qualification");

    Memory memory;
    if (bareProbe) {
      constexpr std::uint32_t instructions[] = {
        0x00100093U, 0x00208113U, 0x00310193U, 0x0000006fU};
      for (std::size_t i = 0; i < std::size(instructions); ++i)
        memory.write32Masked(aethercore::l32sim::kRamBase + i * 4,
                             instructions[i], 0xFU);
    } else if (memoryProbe) {
      constexpr std::uint32_t instructions[] = {
        0x00000097U, 0x02a00113U, 0x08008393U, 0x0023a023U,
        0x0003a183U, 0x00118213U, 0x1003a2afU, 0x1843a32fU,
        0x0003a403U, 0x0023a4afU, 0x0003a503U, 0x0000006fU};
      for (std::size_t i = 0; i < std::size(instructions); ++i)
        memory.write32Masked(aethercore::l32sim::kRamBase + i * 4,
                             instructions[i], 0xFU);
    } else {
      memory.loadAtBase(image);
    }

    auto model = std::make_unique<Model>();
    Ports ports(*model);
    HostBridgeContext bridge{&ports, &memory, fastUnqualified};
    model->productHostBridge = &hostBridge;
    model->productHostContext = &bridge;
    ports.clock(true);
    ports.reset(true);
    ports.rx(false, 0);
    ports.respond({});
    std::puts("AETHERCORE_PRODUCT_GSIM_HOST_BRIDGE_ENABLED actual_top=AetherCoreProductOpenSbiRV64SimTop pa_bits=56");

    std::uint64_t commits = 0, compressed = 0, opensbiCompressed = 0;
    std::uint64_t linuxCompressed = 0, exceptions = 0, interrupts = 0;
    std::uint64_t stip = 0, seip = 0, lastExceptionPc = 0;
    std::uint64_t lastExceptionCause = 0, lastExceptionValue = 0;
    std::uint64_t lastPc = 0, commitDigest = kHashBasis;
    std::uint64_t uartBytes = 0, uartFingerprint = kHashBasis;
    std::uint64_t wireDigest = kHashBasis, milestoneCycle = 0;
    std::uint64_t matchingBare = 0, executedCycles = 0, observedTicks = 0;
    bool opensbiBanner = false, linuxBanner = false, sawMilestone = false;
    std::string tail;
    std::array<std::uint64_t, kRecentCommitCount> recentPcs{};
    std::size_t recentCount = 0, recentNext = 0;
    const auto start = std::chrono::steady_clock::now();
    const std::uint32_t bareExpected[] = {0x00100093U, 0x00208113U, 0x00310193U};
    for (std::uint64_t cycle = 0; cycle < maxCycles; ++cycle) {
      observedTicks = cycle + 1;
      const bool reset = cycle < kResetTicks;
      ports.reset(reset);
      // Observe the settled previous low phase, apply its accepted host-side
      // write once, then compute the next hardware tick with the bridge active.
      const Request request = fastUnqualified
          ? ports.requestFastUnqualified() : ports.request();
      const Response response = readResponses(request, memory);
      ports.respond(response);
      commitAcceptedMemory(request, response, memory, reset);
      model->step();
      if (reset) continue;
      ++executedCycles;

      // The fast G4-only mode does not change a single RTL step or host RAM
      // transaction. It only avoids unused read-only port getters and trace
      // digest work; the G3 strict differential remains the default.
      const Commit commit = fastUnqualified ? ports.commitFastUnqualified() : ports.commit();
      if (commit.valid) {
        ++commits;
        lastPc = commit.pc;
        if (!fastUnqualified) {
          hashWord(commitDigest, commit.pc);
          hashWord(commitDigest, commit.inst);
        }
        if (commit.instBytes == 2) {
          ++compressed;
          if (commit.pc < 0x80400000ULL) ++opensbiCompressed;
          else ++linuxCompressed;
        }
        recentPcs[recentNext] = commit.pc;
        recentNext = (recentNext + 1) % kRecentCommitCount;
        if (recentCount != kRecentCommitCount) ++recentCount;
        if (bareProbe && matchingBare < std::size(bareExpected) &&
            commit.pc == aethercore::l32sim::kRamBase + matchingBare * 4 &&
            commit.inst == bareExpected[matchingBare]) ++matchingBare;
        if (commit.exception) {
          ++exceptions;
          lastExceptionPc = commit.pc;
          lastExceptionCause = commit.exceptionCause;
          lastExceptionValue = commit.exceptionValue;
        }
      }
      if (commit.interrupt) {
        ++interrupts;
        if (commit.interruptCause == (0x8000000000000000ULL | 5ULL) ||
            commit.interruptCause == (0x80000000ULL | 5ULL)) ++stip;
        if (commit.interruptCause == (0x8000000000000000ULL | 9ULL) ||
            commit.interruptCause == (0x80000000ULL | 9ULL)) ++seip;
      }
      if (ports.uartValid()) {
        const auto byte = ports.uartByte();
        ++uartBytes;
        uartFingerprint ^= byte;
        uartFingerprint *= kHashPrime;
        tail.push_back(static_cast<char>(byte));
        if (tail.size() > 128) tail.erase(0, tail.size() - 128);
        std::putchar(static_cast<unsigned char>(byte));
        if (byte == '\n') std::fflush(stdout);
        if (containsTail(tail, "OpenSBI v1.6")) opensbiBanner = true;
        if (containsTail(tail, "Linux version 6.6.143")) linuxBanner = true;
        if (!sawMilestone && containsTail(tail, milestone)) {
          sawMilestone = true;
          milestoneCycle = cycle;
        }
      }
      if (!fastUnqualified) {
        // Retain every old same-cycle trace getter and digest for G3 strict
        // differential. G4 fast mode makes no trace-digest claim whatsoever.
        hashWord(wireDigest, ports.mtime());
        hashWord(wireDigest, ports.mtimecmp());
        hashWord(wireDigest,
            (static_cast<std::uint64_t>(ports.timerInterrupt()) << 0) |
            (static_cast<std::uint64_t>(ports.supervisorExternalInterrupt()) << 1) |
            (static_cast<std::uint64_t>(ports.uartInterrupt()) << 2) |
            (static_cast<std::uint64_t>(ports.uartRxInterrupt()) << 3) |
            (static_cast<std::uint64_t>(ports.exitValid()) << 4));
        hashWord(wireDigest, ports.exitCode());
      }

      if (cycle < 20 && commit.valid)
        std::printf("\nAETHERCORE_PRODUCT_GSIM_COMMIT cycle=%llu index=%llu pc=0x%llx inst=0x%08x\n",
                    static_cast<unsigned long long>(cycle),
                    static_cast<unsigned long long>(commits),
                    static_cast<unsigned long long>(commit.pc), commit.inst);
      if (observedTicks % 1000000ULL == 0) {
        const auto seconds = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - start).count();
        std::fprintf(stderr,
            "AETHERCORE_PRODUCT_GSIM_PROGRESS cycles=%llu commits=%llu"
            " exceptions=%llu interrupts=%llu host_seconds=%.3f host_cps=%.1f\n",
            static_cast<unsigned long long>(observedTicks),
            static_cast<unsigned long long>(commits),
            static_cast<unsigned long long>(exceptions),
            static_cast<unsigned long long>(interrupts),
            seconds, seconds ? observedTicks / seconds : 0.0);
      }
      if (requireLinux && opensbiBanner && linuxBanner && sawMilestone && stip && seip)
        break;
    }

    const auto elapsed = std::chrono::duration<double>(
        std::chrono::steady_clock::now() - start).count();
    const auto ramFingerprint = memory.fingerprint();
    char commitDigestField[32]{};
    char wireDigestField[32]{};
    if (fastUnqualified) {
      std::snprintf(commitDigestField, sizeof(commitDigestField), "OMITTED");
      std::snprintf(wireDigestField, sizeof(wireDigestField), "OMITTED");
    } else {
      std::snprintf(commitDigestField, sizeof(commitDigestField), "0x%llx",
                    static_cast<unsigned long long>(commitDigest));
      std::snprintf(wireDigestField, sizeof(wireDigestField), "0x%llx",
                    static_cast<unsigned long long>(wireDigest));
    }
    // Key names match the Verilator runner's fixed-cycle timeout record.
    // A/B must compare these, including RAM, rather than infer PASS from CPS.
    std::printf("\nL32_OPENSBI_TIMEOUT cycles=%llu commits=%llu compressed=%llu"
                " opensbi-compressed=%llu linux-compressed=%llu"
                " uart-bytes=%llu uart-fingerprint=0x%llx"
                " banner=%u milestone=%u exceptions=%llu interrupts=%llu"
                " stip=%llu seip=%llu mtime=0x%llx mtimecmp=0x%llx"
                " last-exception-pc=0x%llx last-exception-cause=0x%llx"
                " last-exception-value=0x%llx ram-fingerprint=0x%llx recent-pcs=",
                static_cast<unsigned long long>(observedTicks),
                static_cast<unsigned long long>(commits),
                static_cast<unsigned long long>(compressed),
                static_cast<unsigned long long>(opensbiCompressed),
                static_cast<unsigned long long>(linuxCompressed),
                static_cast<unsigned long long>(uartBytes),
                static_cast<unsigned long long>(uartFingerprint),
                static_cast<unsigned>(opensbiBanner),
                static_cast<unsigned>(sawMilestone),
                static_cast<unsigned long long>(exceptions),
                static_cast<unsigned long long>(interrupts),
                static_cast<unsigned long long>(stip),
                static_cast<unsigned long long>(seip),
                static_cast<unsigned long long>(ports.mtime()),
                static_cast<unsigned long long>(ports.mtimecmp()),
                static_cast<unsigned long long>(lastExceptionPc),
                static_cast<unsigned long long>(lastExceptionCause),
                static_cast<unsigned long long>(lastExceptionValue),
                static_cast<unsigned long long>(ramFingerprint));
    printRecentPcs(recentPcs, recentCount, recentNext);
    std::putchar('\n');
    std::printf("AETHERCORE_PRODUCT_GSIM_PREFIX guest_cycles=%llu retires=%llu"
                " exceptions=%llu interrupts=%llu stip=%llu seip=%llu"
                " last_pc=0x%llx commit_digest=%s"
                " ram_digest=0x%llx uart_bytes=%llu uart_digest=0x%llx"
                " wire_digest=%s trace_mode=%s mtime=%llu mtimecmp=%llu"
                " exit_valid=%u exit_code=%llu opensbi_banner=%u"
                " linux_banner=%u milestone_cycle=%llu"
                " host_seconds=%.6f host_cps=%.1f"
                " host_bridge_calls=%llu host_imem_calls=%llu"
                " host_data_calls=%llu host_ptw_calls=%llu\n",
                static_cast<unsigned long long>(executedCycles),
                static_cast<unsigned long long>(commits),
                static_cast<unsigned long long>(exceptions),
                static_cast<unsigned long long>(interrupts),
                static_cast<unsigned long long>(stip),
                static_cast<unsigned long long>(seip),
                static_cast<unsigned long long>(lastPc),
                commitDigestField,
                static_cast<unsigned long long>(ramFingerprint),
                static_cast<unsigned long long>(uartBytes),
                static_cast<unsigned long long>(uartFingerprint),
                wireDigestField,
                fastUnqualified ? "fast-g4-unqualified" : "strict-same-cycle",
                static_cast<unsigned long long>(ports.mtime()),
                static_cast<unsigned long long>(ports.mtimecmp()),
                static_cast<unsigned>(ports.exitValid()),
                static_cast<unsigned long long>(ports.exitCode()),
                static_cast<unsigned>(opensbiBanner),
                static_cast<unsigned>(linuxBanner),
                static_cast<unsigned long long>(milestoneCycle),
                elapsed, elapsed ? executedCycles / elapsed : 0.0,
                static_cast<unsigned long long>(bridge.calls),
                static_cast<unsigned long long>(bridge.validFetchCalls),
                static_cast<unsigned long long>(bridge.validDataCalls),
                static_cast<unsigned long long>(bridge.validPtwCalls));

    if (bareProbe) {
      if (matchingBare != 3) {
        std::fprintf(stderr, "AETHERCORE_PRODUCT_GSIM_BARE_FAIL matched=%llu expected=3\n",
                     static_cast<unsigned long long>(matchingBare));
        return 11;
      }
      std::puts("AETHERCORE_PRODUCT_GSIM_BARE_PASS");
    } else if (memoryProbe) {
      const auto word = memory.read32(aethercore::l32sim::kRamBase + 128);
      if (word != 85 || exceptions != 0) {
        std::fprintf(stderr, "AETHERCORE_PRODUCT_GSIM_MEMORY_FAIL final_word=%u exceptions=%llu\n",
                     word, static_cast<unsigned long long>(exceptions));
        return 13;
      }
      std::puts("AETHERCORE_PRODUCT_GSIM_MEMORY_PASS");
    } else if (requireLinux) {
      if (!(opensbiBanner && linuxBanner && sawMilestone && stip && seip)) {
        std::fprintf(stderr, "AETHERCORE_PRODUCT_GSIM_LINUX_MILESTONE_FAIL\n");
        return 14;
      }
      std::puts("AETHERCORE_PRODUCT_GSIM_LINUX_MILESTONE_PASS");
    } else {
      std::puts("AETHERCORE_PRODUCT_GSIM_FIXED_CYCLE_UNQUALIFIED_REQUIRES_VERILATOR_AB");
    }
    return 0;
  } catch (const std::exception& error) {
    std::fprintf(stderr, "AETHERCORE_PRODUCT_GSIM_TRANSPORT_ERROR %s\n", error.what());
    return 12;
  }
}
