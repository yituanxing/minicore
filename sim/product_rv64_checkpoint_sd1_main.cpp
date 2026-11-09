// True Product RV64 Debian SD1 top: cross-process RTL + DDR + physical SPI
// host-card checkpoint. This intentionally does NOT claim Linux ext4/PID1.
#if !defined(AETHERCORE_PRODUCT_SPI_SD) || !defined(AETHERCORE_PRODUCT_DEBIAN) || \
    !defined(AETHERCORE_PRODUCT_CHECKPOINT_SAVABLE)
#error "SD1 persistent checkpoint requires the distinct --savable Debian SPI-SD RTL top"
#endif
#include "VAetherCoreProductDebianSdRV64SimTop.h"
#include "l32_opensbi_runtime.h"
#include "l32_sd_spi_card.h"
#include "verilated.h"
#include "verilated_save.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <initializer_list>
#include <iostream>
#include <limits>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {
using Top = VAetherCoreProductDebianSdRV64SimTop;
using aethercore::l32sim::Memory;
using aethercore::l32sim::SdSpiCard;
using aethercore::l32sim::TransportRuntimeState;
constexpr std::string_view kMagic = "AETSDCP1";
constexpr std::uint64_t kVersion = 1;
// Exactly the frozen signed-Debian polling runner, not an arbitrary older top.
constexpr std::string_view kFrozenPollingModel =
    "5a39da4993e9fa8c9a4280fdd3fedb48c6039cb6fa1eb487f8d0b41b2d31c8cc";
// The only permitted source model for the isolated 7892M Host-only migration.
constexpr std::string_view kFrozenMigratedV1Model =
    "b9a651a3ba3aa50c7e0b15405d24a3d3ab56dd25fa2df7fb20f5654cdda80461";
// Qualified Host-v2 executable from real 7892M->7912M 20M byte-equivalence.
// The following v3 migration is deliberately scoped to the C 8072M source.
constexpr std::string_view kFrozenMigratedV2Model =
    "c47f6d16aab24bf83df9f4baab2b3cbbe19ced7bc3f42c1ffa96c5f35530aa70";
// Exact qualified 8.5B Host-v3 executable; source of the isolated v4 relay.
constexpr std::string_view kFrozenMigratedV3Model =
    "04e671f2dfae8aa6d7c5b17a93c688cb7b30005de9a2a329f983b909f4fa1be6";
constexpr std::uint64_t kV2MigrationSourceCycles = 7892000000ULL;
constexpr std::uint64_t kV2MigratedMaxCycles = 8100000000ULL;
constexpr std::uint64_t kV3MigrationSourceCycles = 8072000000ULL;
constexpr std::uint64_t kV3MigratedMaxCycles = 8500000000ULL;
constexpr std::uint64_t kV4MigrationSourceCycles = 8272000000ULL;
constexpr std::uint64_t kV4MigratedMaxCycles = 10000000000ULL;
constexpr std::uint64_t kFnv = 14695981039346656037ULL;
constexpr std::uint64_t kPrime = 1099511628211ULL;
constexpr std::uint64_t kDtbAddress = 0x87f00000ULL;
constexpr std::size_t kDtbMax = 1ULL << 20;
constexpr std::size_t kMaxUart = 32ULL << 20;
constexpr std::uint64_t kMaxCardSnapshot = 512ULL << 20;
constexpr std::size_t kRecent = 16;
constexpr std::uint64_t kLinuxBase = 0x80200000ULL;
constexpr std::string_view kC1Ready = "AETHERCORE_C1_READY rootfs_unmounted=1\n";
constexpr std::string_view kC1MountBegin = "AETHERCORE_C1_ROOTFS_MOUNT_BEGIN";
constexpr std::string_view kC1MountOk = "AETHERCORE_C1_ROOTFS_MOUNT_OK";
constexpr std::string_view kC1NativeExec = "AETHERCORE_C1_NATIVE_INIT_EXEC";
constexpr std::string_view kC1Fail = "AETHERCORE_C1_FAIL";
constexpr std::string_view kC1Go = "AETHERCORE_C1_GO\n";

void put64(std::ostream& os, std::uint64_t value) {
  for (unsigned i = 0; i < 8; ++i) os.put(static_cast<char>(value >> (8 * i)));
  if (!os) throw std::runtime_error("SD1 host snapshot write failed");
}
std::uint64_t get64(std::istream& is) {
  std::uint64_t n = 0;
  for (unsigned i = 0; i < 8; ++i) {
    const int ch = is.get();
    if (ch == EOF) throw std::runtime_error("truncated SD1 host snapshot");
    n |= static_cast<std::uint64_t>(static_cast<unsigned char>(ch)) << (8 * i);
  }
  return n;
}
bool getBool(std::istream& is) {
  const auto n = get64(is);
  if (n > 1) throw std::runtime_error("noncanonical SD1 host boolean");
  return n != 0;
}
std::uint32_t get32(std::istream& is) {
  const auto n = get64(is);
  if (n > std::numeric_limits<std::uint32_t>::max())
    throw std::runtime_error("SD1 transport integer overflow");
  return static_cast<std::uint32_t>(n);
}
void putString(std::ostream& os, std::string_view s) {
  put64(os, s.size());
  os.write(s.data(), static_cast<std::streamsize>(s.size()));
  if (!os) throw std::runtime_error("SD1 snapshot string write failed");
}
std::string getString(std::istream& is, std::size_t limit) {
  const auto n = get64(is);
  if (n > limit) throw std::runtime_error("SD1 snapshot string over limit");
  std::string s(static_cast<std::size_t>(n), '\0');
  is.read(s.data(), static_cast<std::streamsize>(n));
  if (!is) throw std::runtime_error("truncated SD1 snapshot string");
  return s;
}
bool validSha(std::string_view s) {
  if (s.size() != 64) return false;
  for (const unsigned char c : s)
    if (!std::isxdigit(c) || std::isupper(c)) return false;
  return true;
}
std::string shaFile(const std::string& path) {
  constexpr char alphabet[]="0123456789abcdef";
  const auto bytes=aethercore::l32sim::snapshot_detail::sha256File(path);
  std::string result;
  result.reserve(64);
  for (const auto ch : bytes) {
    result.push_back(alphabet[ch>>4]);
    result.push_back(alphabet[ch&15]);
  }
  return result;
}
bool endsWith(const std::string& s, std::string_view suffix) {
  return !suffix.empty() && s.size() >= suffix.size() &&
         s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}
void mix(std::uint64_t& hash, std::uint64_t value) {
  for (unsigned i = 0; i < 8; ++i) {
    hash ^= static_cast<std::uint8_t>(value >> (8 * i));
    hash *= kPrime;
  }
}
bool irqCause(std::uint64_t cause, std::uint64_t code) {
  return cause == (0x80000000ULL | code) ||
         cause == (0x8000000000000000ULL | code);
}

struct Identity {
  std::string model, firmware, dtb, media, linuxImage, rootfs;
  void validate() const {
    for (const auto& sha : {model, firmware, dtb, media, linuxImage, rootfs})
      if (!validSha(sha)) throw std::runtime_error("invalid SD1 image/model SHA256 identity");
  }
};
struct Policy {
  std::string uartTrigger;
  std::string uartInput;
  std::uint64_t postInputBudget = 0;
};

// Matches all mutable host-side observability/console state that the normal
// opensbi_boot_main.cpp evolves. Full RTL savable independently covers CSR,
// PLIC, interrupts, pending SPI controller transactions and UART internals.
struct Observer {
  std::uint64_t cycles=0, commits=0, compressed=0, opensbiCompressed=0,
      linuxCompressed=0, exceptions=0, interrupts=0, stip=0, seip=0,
      lastExceptionCause=0, lastExceptionValue=0, lastExceptionPc=0,
      milestoneCommitCount=0, uartHash=kFnv, traceHash=kFnv;
  std::string uart;
  bool sawOpenSbiBanner=false, sawLogin=false, inputStarted=false;
  std::uint64_t inputIndex=0, seipAtInputStart=0;
  bool sawRxInterrupt=false, sawPostInputSeip=false, deadlineArmed=false;
  std::uint64_t inputCompleteCycle=0, postInputDeadline=0,
      nextProgressCycle=10000000, previousProgressCycle=0,
      previousProgressCommits=0, lastUartCycle=0;
  std::array<std::uint64_t, kRecent> recentPcs{};
  std::uint64_t recentCount=0, recentIndex=0;
};
void requireC1Ready(const Observer& o) {
  if (o.uart.find(kC1Ready) == std::string::npos ||
      o.uart.find(kC1MountBegin) != std::string::npos ||
      o.uart.find(kC1MountOk) != std::string::npos ||
      o.uart.find(kC1NativeExec) != std::string::npos ||
      o.uart.find(kC1Fail) != std::string::npos ||
      o.uart.find("Kernel panic - not syncing") != std::string::npos ||
      o.sawLogin || o.inputStarted || o.inputIndex)
    throw std::runtime_error("C1 boundary requires READY, untouched rootfs and no pre-release UART input");
}
bool c1HostQuiescent(const Top& top, const TransportRuntimeState& transport,
                     const SdSpiCard& card) {
  // Full RTL internal state is saved independently. These externally visible
  // SPI controller pins must also match the host-card's inactive state, and
  // host DDR transport must not be mid-wait at the media switch.
  return card.c1RootfsHandoffQuiescent() && top.io_spiCsN &&
         !top.io_spiSclk && !transport.dataWait.active;
}
bool c1CaptureReady(const Observer& o, const Top& top,
                    const TransportRuntimeState& transport,
                    const SdSpiCard& card) {
  if (o.uart.find(kC1Fail) != std::string::npos ||
      o.uart.find("Kernel panic - not syncing") != std::string::npos)
    throw std::runtime_error("C1 cannot preserve a failed pre-rootfs Linux boot");
  if (o.uart.find(kC1Ready) == std::string::npos) return false;
  requireC1Ready(o);
  // This is deliberately false for a READY UART line observed mid-SPI. That
  // snapshot remains an intermediate, unreleasable prefix until a later
  // c1-advance stops at a genuine media-quiescent clock boundary.
  return c1HostQuiescent(top,transport,card);
}
void writeObserver(std::ostream& os, const Observer& o) {
  for (auto n : {o.cycles,o.commits,o.compressed,o.opensbiCompressed,
                 o.linuxCompressed,o.exceptions,o.interrupts,o.stip,o.seip,
                 o.lastExceptionCause,o.lastExceptionValue,o.lastExceptionPc,
                 o.milestoneCommitCount,o.uartHash,o.traceHash}) put64(os,n);
  putString(os,o.uart);
  for (auto n : {o.sawOpenSbiBanner,o.sawLogin,o.inputStarted}) put64(os,n);
  put64(os,o.inputIndex); put64(os,o.seipAtInputStart);
  for (auto n : {o.sawRxInterrupt,o.sawPostInputSeip,o.deadlineArmed}) put64(os,n);
  for (auto n : {o.inputCompleteCycle,o.postInputDeadline,o.nextProgressCycle,
                 o.previousProgressCycle,o.previousProgressCommits,o.lastUartCycle}) put64(os,n);
  for (auto pc : o.recentPcs) put64(os,pc);
  put64(os,o.recentCount); put64(os,o.recentIndex);
}
Observer readObserver(std::istream& is) {
  Observer o;
  o.cycles=get64(is); o.commits=get64(is); o.compressed=get64(is);
  o.opensbiCompressed=get64(is); o.linuxCompressed=get64(is);
  o.exceptions=get64(is); o.interrupts=get64(is); o.stip=get64(is); o.seip=get64(is);
  o.lastExceptionCause=get64(is); o.lastExceptionValue=get64(is);
  o.lastExceptionPc=get64(is); o.milestoneCommitCount=get64(is);
  o.uartHash=get64(is); o.traceHash=get64(is);
  o.uart=getString(is,kMaxUart);
  o.sawOpenSbiBanner=getBool(is); o.sawLogin=getBool(is); o.inputStarted=getBool(is);
  o.inputIndex=get64(is); o.seipAtInputStart=get64(is);
  o.sawRxInterrupt=getBool(is); o.sawPostInputSeip=getBool(is); o.deadlineArmed=getBool(is);
  o.inputCompleteCycle=get64(is); o.postInputDeadline=get64(is);
  o.nextProgressCycle=get64(is); o.previousProgressCycle=get64(is);
  o.previousProgressCommits=get64(is); o.lastUartCycle=get64(is);
  for (auto& pc : o.recentPcs) pc=get64(is);
  o.recentCount=get64(is); o.recentIndex=get64(is);
  if (o.recentCount>kRecent || o.recentIndex>=kRecent)
    throw std::runtime_error("invalid SD1 recent-PC ring");
  return o;
}

void writeHost(const std::string& path, const Identity& id, const Policy& policy,
               const VerilatedContext& ctx, const Memory& memory,
               const TransportRuntimeState& transport, const SdSpiCard& card,
               const Observer& o) {
  std::ofstream os(path,std::ios::binary|std::ios::trunc);
  if (!os) throw std::runtime_error("cannot create SD1 host checkpoint");
  os.write(kMagic.data(),static_cast<std::streamsize>(kMagic.size()));
  put64(os,kVersion);
  for (const auto& s : {id.model,id.firmware,id.dtb,id.media,id.linuxImage,id.rootfs})
    putString(os,s);
  putString(os,policy.uartTrigger);
  putString(os,policy.uartInput);
  put64(os,policy.postInputBudget);
  put64(os,ctx.time());
  put64(os,aethercore::l32sim::dataMemoryWaitCycles());
  writeObserver(os,o);
  const auto& w=transport.dataWait;
  for (auto n : {static_cast<std::uint64_t>(w.active),
                 static_cast<std::uint64_t>(w.qualificationPrinted),
                 static_cast<std::uint64_t>(w.remaining),
                 static_cast<std::uint64_t>(w.observedStallCycles)}) put64(os,n);
  const auto ram=memory.snapshot();
  if (ram.bytes.size()!=aethercore::l32sim::kRamSize)
    throw std::runtime_error("SD1 host DDR size mismatch");
  put64(os,ram.bytes.size());
  os.write(reinterpret_cast<const char*>(ram.bytes.data()),
           static_cast<std::streamsize>(ram.bytes.size()));
  put64(os,ram.reservationValid); put64(os,ram.reservationAddress);
  put64(os,ram.reservationSize);
  // Atomic SD host state: includes edge phase, command/CRC pipeline, output
  // FIFO and every sector in the immutable-backed copy-on-write overlay.
  const auto cardBlob=card.snapshot();
  if (cardBlob.size()>kMaxCardSnapshot)
    throw std::runtime_error("SD1 COW overlay exceeds snapshot safety budget");
  put64(os,cardBlob.size());
  os.write(reinterpret_cast<const char*>(cardBlob.data()),
           static_cast<std::streamsize>(cardBlob.size()));
  os.flush();
  if (!os) throw std::runtime_error("incomplete SD1 host checkpoint");
}
void readHost(const std::string& path, const Identity& id, const Policy& policy,
              VerilatedContext& ctx, Memory& memory,
              TransportRuntimeState& transport, SdSpiCard& card, Observer& o) {
  std::ifstream is(path,std::ios::binary);
  if (!is) throw std::runtime_error("SD1 pre-state host file missing");
  std::array<char,8> magic{};
  is.read(magic.data(),magic.size());
  if (!is || std::string_view(magic.data(),magic.size())!=kMagic || get64(is)!=kVersion)
    throw std::runtime_error("SD1 host schema/top/version mismatch");
  for (const auto& expected : {id.model,id.firmware,id.dtb,id.media,id.linuxImage,id.rootfs})
    if (getString(is,64)!=expected)
      throw std::runtime_error("SD1 model/firmware/DTB/SD/Linux/rootfs SHA mismatch");
  if (getString(is,128)!=policy.uartTrigger ||
      getString(is,4096)!=policy.uartInput ||
      get64(is)!=policy.postInputBudget)
    throw std::runtime_error("SD1 UART/IRQ injection policy changed");
  const auto savedTime=get64(is);
  if (get64(is)!=aethercore::l32sim::dataMemoryWaitCycles())
    throw std::runtime_error("SD1 DDR dataWait configuration changed");
  if (ctx.time()!=0) throw std::runtime_error("SD1 restore needs a fresh RTL context");
  o=readObserver(is);
  transport.dataWait.active=getBool(is);
  transport.dataWait.qualificationPrinted=getBool(is);
  transport.dataWait.remaining=get32(is);
  transport.dataWait.observedStallCycles=get32(is);
  const auto bytes=get64(is);
  if (bytes!=aethercore::l32sim::kRamSize)
    throw std::runtime_error("SD1 DDR extent differs (require full 256MiB)");
  Memory::Snapshot ram;
  ram.bytes.resize(static_cast<std::size_t>(bytes));
  is.read(reinterpret_cast<char*>(ram.bytes.data()),static_cast<std::streamsize>(bytes));
  if (!is) throw std::runtime_error("truncated SD1 DDR snapshot");
  ram.reservationValid=getBool(is);
  ram.reservationAddress=get64(is);
  const auto reservationSize=get64(is);
  if (reservationSize>8) throw std::runtime_error("invalid SD1 LR/SC reservation size");
  ram.reservationSize=static_cast<std::size_t>(reservationSize);
  const auto cardLen=get64(is);
  if (cardLen<52 || cardLen>kMaxCardSnapshot ||
      cardLen>std::filesystem::file_size(path))
    throw std::runtime_error("SD1 card snapshot length invalid");
  std::vector<std::uint8_t> cardBlob(static_cast<std::size_t>(cardLen));
  is.read(reinterpret_cast<char*>(cardBlob.data()),static_cast<std::streamsize>(cardLen));
  if (!is || is.get()!=EOF) throw std::runtime_error("truncated/trailing SD1 card state");
  if (o.cycles!=savedTime || o.inputIndex>policy.uartInput.size())
    throw std::runtime_error("SD1 guest time/UART input cursor differs");
  std::uint64_t uartHash=kFnv;
  for (const unsigned char ch : o.uart) {uartHash^=ch;uartHash*=kPrime;}
  if (uartHash!=o.uartHash)
    throw std::runtime_error("SD1 UART bytes/host fingerprint differs");
  // All validation before advancing guest time; SdSpiCard::restore itself
  // atomically validates on a staged card and pins the complete media SHA.
  card.restore(std::span<const std::uint8_t>(cardBlob));
  memory.restore(ram);
  ctx.timeInc(savedTime);
}
void saveRtl(const std::string& path, Top& top) {
  VerilatedSave out;
  out.open(path.c_str());
  out<<top;
  out.close();
}
void readRtl(const std::string& path, Top& top) {
  VerilatedRestore in;
  in.open(path.c_str());
  in>>top;
  in.close();
}

std::string pollingReadyField(const std::filesystem::path& path,
                              std::string_view key) {
  std::ifstream in(path);
  if (!in) throw std::runtime_error("SD1 migration requires sealed polling-ready manifest");
  const std::string prefix=std::string(key)+"=";
  std::string value,line;
  bool found=false;
  while (std::getline(in,line)) {
    if (line.rfind(prefix,0)!=0) continue;
    if (found) throw std::runtime_error("duplicate SD1 migration manifest field");
    value=line.substr(prefix.size());
    found=true;
  }
  if (!in.eof() || !found || value.empty())
    throw std::runtime_error("missing or malformed SD1 migration manifest field");
  return value;
}

// A migration changes *only* the 64 ASCII bytes of model SHA in AETSDCP1:
// magic[0..7], version[8..15], length[16..23], model SHA[24..87].
// Streaming comparison includes every observer byte, 256 MiB DDR, LR/SC,
// pending transport, SPI SD protocol and COW overlay without a second RAM copy.
void requireExactMigrationBytes(const std::string& sourceHost,
                                const std::string& candidateHost,
                                const std::string& sourceRtl,
                                const std::string& candidateRtl) {
  const auto rtlBytes=std::filesystem::file_size(sourceRtl);
  if (rtlBytes!=std::filesystem::file_size(candidateRtl))
    throw std::runtime_error("migration changed serialized Product RTL length");
  std::ifstream oldRtl(sourceRtl,std::ios::binary),newRtl(candidateRtl,std::ios::binary);
  if (!oldRtl || !newRtl)
    throw std::runtime_error("migration RTL comparison failed to open");
  std::array<char,64*1024> rtlLeft{},rtlRight{};
  for (std::uint64_t offset=0;offset<rtlBytes;) {
    const auto count=static_cast<std::streamsize>(
        std::min<std::uint64_t>(rtlLeft.size(),rtlBytes-offset));
    oldRtl.read(rtlLeft.data(),count);
    newRtl.read(rtlRight.data(),count);
    if (!oldRtl || !newRtl || std::memcmp(rtlLeft.data(),rtlRight.data(),
                                          static_cast<std::size_t>(count))!=0)
      throw std::runtime_error("migration changed serialized Product RTL state");
    offset+=static_cast<std::uint64_t>(count);
  }
  const auto oldBytes=std::filesystem::file_size(sourceHost);
  if (oldBytes!=std::filesystem::file_size(candidateHost) || oldBytes<88)
    throw std::runtime_error("migration changed SD1 Host state length");
  std::ifstream oldIn(sourceHost,std::ios::binary),newIn(candidateHost,std::ios::binary);
  if (!oldIn || !newIn) throw std::runtime_error("migration Host comparison failed to open");
  constexpr std::size_t kChunk=64*1024;
  std::array<char,kChunk> left{},right{};
  std::uint64_t offset=0;
  while (offset<oldBytes) {
    const auto count=static_cast<std::streamsize>(
        std::min<std::uint64_t>(kChunk,oldBytes-offset));
    oldIn.read(left.data(),count);
    newIn.read(right.data(),count);
    if (!oldIn || !newIn) throw std::runtime_error("migration Host comparison truncated");
    for (std::streamsize i=0;i<count;++i) {
      const auto pos=offset+static_cast<std::uint64_t>(i);
      if (pos>=24 && pos<88) continue;
      if (left[static_cast<std::size_t>(i)]!=right[static_cast<std::size_t>(i)])
        throw std::runtime_error("migration changed Host state beyond model SHA");
    }
    offset+=static_cast<std::uint64_t>(count);
  }
}

void observe(Top& top, Observer& o, const Policy& policy,
             bool accepted, bool strictTrace, bool emitUart) {
  ++o.cycles;
  const auto index=o.cycles-1;
  if (o.cycles==4) top.reset=0;
  if (top.reset) return;
  if (index>=o.nextProgressCycle) {
    o.previousProgressCycle=index;
    o.previousProgressCommits=o.commits;
    o.nextProgressCycle+=10000000;
  }
  if (strictTrace) {
    for (auto n : {static_cast<std::uint64_t>(top.io_commit_valid),
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
                   static_cast<std::uint64_t>(top.io_spiSclk),
                   static_cast<std::uint64_t>(top.io_spiMosi),
                   static_cast<std::uint64_t>(top.io_spiMiso),
                   static_cast<std::uint64_t>(top.io_spiCsN),
                   static_cast<std::uint64_t>(top.io_exitValid),
                   static_cast<std::uint64_t>(top.io_exitCode)}) mix(o.traceHash,n);
  }
  if (accepted) {
    ++o.inputIndex;
    if (o.inputIndex==policy.uartInput.size()) {
      o.inputCompleteCycle=index;
      if (policy.postInputBudget) {
        o.deadlineArmed=true;
        o.postInputDeadline=index+policy.postInputBudget;
      }
    }
  }
  if (o.inputStarted && top.io_uartRxInterrupt) o.sawRxInterrupt=true;
  if (top.io_uartValid) {
    const auto ch=static_cast<std::uint8_t>(top.io_uartByte);
    o.uart.push_back(static_cast<char>(ch));
    if (o.uart.size()>kMaxUart)
      throw std::runtime_error("SD1 UART history exceeds checkpoint budget");
    o.uartHash^=ch; o.uartHash*=kPrime;
    o.lastUartCycle=index;
    if (emitUart) {
      std::cout.put(static_cast<char>(ch));
      if (ch=='\n') std::cout.flush();
    }
    o.sawOpenSbiBanner|=endsWith(o.uart,"OpenSBI v1.6");
    if (!o.inputStarted && !policy.uartInput.empty() &&
        endsWith(o.uart,policy.uartTrigger)) {
      o.inputStarted=true;
      o.seipAtInputStart=o.seip;
    }
    if (!o.sawLogin && endsWith(o.uart,"login:")) {
      o.sawLogin=true;
      o.milestoneCommitCount=o.commits;
    }
  }
  if (top.io_commit_valid) {
    ++o.commits;
    const auto pc=static_cast<std::uint64_t>(top.io_commit_pc);
    if (static_cast<unsigned>(top.io_commit_instBytes)==2U) {
      ++o.compressed;
      if (pc<kLinuxBase) ++o.opensbiCompressed;
      else ++o.linuxCompressed;
    }
    o.recentPcs[o.recentIndex]=pc;
    o.recentIndex=(o.recentIndex+1)%kRecent;
    if (o.recentCount<kRecent) ++o.recentCount;
    if (top.io_commit_exception) {
      ++o.exceptions;
      o.lastExceptionPc=pc;
      o.lastExceptionCause=static_cast<std::uint64_t>(top.io_commit_exceptionCause);
      o.lastExceptionValue=static_cast<std::uint64_t>(top.io_commit_exceptionValue);
    }
    if (strictTrace) {
      for (auto n : {pc,static_cast<std::uint64_t>(top.io_commit_rawInst),
                     static_cast<std::uint64_t>(top.io_commit_instBytes),
                     static_cast<std::uint64_t>(top.io_commit_exception),
                     static_cast<std::uint64_t>(top.io_commit_exceptionCause)}) mix(o.traceHash,n);
    }
  }
  if (top.io_commit_interrupt) {
    ++o.interrupts;
    const auto cause=static_cast<std::uint64_t>(top.io_commit_interruptCause);
    if (irqCause(cause,5)) ++o.stip;
    if (irqCause(cause,9)) {
      ++o.seip;
      if (o.inputStarted && o.seip>o.seipAtInputStart) o.sawPostInputSeip=true;
    }
    if (strictTrace) {
      mix(o.traceHash,cause);
      mix(o.traceHash,static_cast<std::uint64_t>(top.io_commit_interruptPc));
    }
  }
  if (o.deadlineArmed && index>=o.postInputDeadline && !o.sawPostInputSeip)
    throw std::runtime_error("SD1 UART post-input interrupt deadline expired");
}
void oneCycle(Top& top, VerilatedContext& ctx, Memory& mem,
              TransportRuntimeState& transport, SdSpiCard& card,
              Observer& obs, const Policy& policy, bool trace, bool emitUart) {
  const bool inject=obs.inputStarted && obs.inputIndex<policy.uartInput.size();
  const auto ch=inject ? static_cast<std::uint8_t>(policy.uartInput[obs.inputIndex]) : 0;
  // Pin ordering exactly matches the official SD1 Product host. Never call
  // card.observe() again for the cycle restored from disk.
  top.io_spiMiso=card.miso()?1:0;
  const bool accepted=aethercore::l32sim::step(top,ctx,mem,transport,inject,ch);
  card.observe(static_cast<bool>(top.io_spiSclk),
               static_cast<bool>(top.io_spiMosi),
               static_cast<bool>(top.io_spiCsN));
  observe(top,obs,policy,accepted&&inject,trace,emitUart);
}
void result(const Observer& o, const VerilatedContext& ctx, const Memory& mem,
            const TransportRuntimeState& transport, const SdSpiCard& card,
            const Top& top) {
  std::cout<<"PRODUCT_SD1_PERSISTENT_CONTINUATION"
      <<" cycles="<<o.cycles<<" time="<<ctx.time()
      <<" commits="<<o.commits<<" compressed="<<o.compressed
      <<" linux_compressed="<<o.linuxCompressed
      <<" exceptions="<<o.exceptions<<" interrupts="<<o.interrupts
      <<" stip="<<o.stip<<" seip="<<o.seip
      <<" uart_bytes="<<o.uart.size()
      <<" uart_hash=0x"<<std::hex<<o.uartHash
      <<" trace_hash=0x"<<o.traceHash
      <<" ram_hash=0x"<<mem.fingerprint()
      <<" mtime=0x"<<static_cast<std::uint64_t>(top.io_mtime)
      <<" mtimecmp=0x"<<static_cast<std::uint64_t>(top.io_mtimecmp)
      <<std::dec<<" opensbi_banner="<<o.sawOpenSbiBanner
      <<" login_observed="<<o.sawLogin
      <<" uart_input_index="<<o.inputIndex
      <<" uart_rx_irq="<<o.sawRxInterrupt
      <<" uart_seip="<<o.sawPostInputSeip
      <<" wait_active="<<transport.dataWait.active
      <<" wait_remaining="<<transport.dataWait.remaining
      <<' '<<card.progress()<<'\n';
}
}  // namespace

int main(int argc,char** argv) {
  try {
    if (argc<14)
      throw std::runtime_error("usage: SD1_CHECKPOINT save|snapshot|restore|advance|c1-save|c1-advance|c1-release|migrate-v1|migrate-v2-8b|migrate-v3-8p5b|migrate-v4-10b FW DTB OLD_SD PREFIX MODEL_SHA FW_SHA DTB_SHA OLD_SD_SHA LINUX_SHA OLD_ROOTFS_SHA START_CYCLES SEGMENT_CYCLES [strict UART policy+destination | C1 NEW_SD NEW_SD_SHA NEW_ROOTFS_SHA DEST_PREFIX | migration OLD_EXEC DEST_PREFIX]");
    const std::string mode=argv[1],fw=argv[2],dtb=argv[3],sd=argv[4],prefix=argv[5];
    const Identity id{argv[6],argv[7],argv[8],argv[9],argv[10],argv[11]};
    id.validate();
    const bool c1Save=mode=="c1-save", c1Advance=mode=="c1-advance",
               c1Release=mode=="c1-release";
    const bool c1Mode=c1Save || c1Advance || c1Release;
    const bool migratingV2=mode=="migrate-v2-8b";
    const bool migratingV3=mode=="migrate-v3-8p5b";
    const bool migratingV4=mode=="migrate-v4-10b";
    const bool migrating=mode=="migrate-v1" || migratingV2 || migratingV3 || migratingV4;
    if (mode!="save" && mode!="snapshot" && mode!="restore" && mode!="advance" &&
        !c1Mode && !migrating)
      throw std::runtime_error("invalid SD1 checkpoint mode");
    const bool advancing=mode=="advance" || c1Advance;
    if (migrating) {
      if (argc!=16)
        throw std::runtime_error("model migration needs exactly OLD_EXEC and distinct DEST_PREFIX");
    } else if (c1Mode) {
      if (argc!=(c1Save?14:(c1Advance?15:18)))
        throw std::runtime_error("C1 needs exact c1-save / c1-advance DEST / c1-release NEW_SD NEW_SHA NEW_ROOTFS_SHA DEST argument count");
    } else if ((argc!=14 && argc!=15 && argc!=17 && argc!=18) ||
               (advancing!=(argc==15 || argc==18))) {
      throw std::runtime_error("strict SD1 advance needs independent destination; other modes forbid it");
    }
    const auto bootCycles=std::stoull(argv[12],nullptr,0);
    const auto replayCycles=std::stoull(argv[13],nullptr,0);
    const bool hasPolicy=!c1Mode && !migrating && (argc==17 || argc==18);
    Policy policy{hasPolicy?argv[14]:"",hasPolicy?argv[15]:"",
                  hasPolicy?std::stoull(argv[16],nullptr,0):0};
    const std::string advanceDest=advancing?argv[argc-1]:(c1Release?argv[17]:"");
    const std::string newMedia=c1Release?argv[14]:"";
    const std::string newMediaSha=c1Release?argv[15]:"";
    const std::string newRootfsSha=c1Release?argv[16]:"";
    if (c1Release && (!validSha(newMediaSha) || !validSha(newRootfsSha)))
      throw std::runtime_error("C1 replacement requires exact media and rootfs SHA256 pins");
    if (c1Mode) {
      const std::string& outputPrefix=(c1Save?prefix:advanceDest);
      if ((c1Advance || c1Release) &&
          std::filesystem::absolute(prefix).lexically_normal()==
            std::filesystem::absolute(outputPrefix).lexically_normal())
        throw std::runtime_error("C1 source and new checkpoint prefix must differ");
      if (std::filesystem::exists(outputPrefix+".pre.rtl") ||
          std::filesystem::exists(outputPrefix+".pre.host"))
        throw std::runtime_error("C1 refuses to overwrite an existing checkpoint");
    }
    const char* c1Confirm=std::getenv("AETHERCORE_SD1_C1_CONFIRM");
    if (c1Mode && !(c1Confirm &&
        std::string_view(c1Confirm)=="GENERIC_LINUX_ROOTFS_BOUNDARY"))
      throw std::runtime_error("C1 requires explicit generic Linux rootfs boundary confirmation");
    if (!policy.uartInput.empty() && policy.uartInput.back()!='\n')
      policy.uartInput.push_back('\n');
    // The short proof's historic 1.8B ceiling stays the default. Long SD1
    // continuation is a separate, explicitly opted-in execution profile:
    // no implicit cold replay or unbounded cycle count is permitted.
    const char* relayConfirm=std::getenv("AETHERCORE_SD1_RELAY_CONFIRM");
    const bool longRelay=relayConfirm &&
        std::string_view(relayConfirm)=="SD1_FULL_DEBIAN_SIGNED_RELAY";
    const bool migratedRelay=relayConfirm &&
        std::string_view(relayConfirm)=="SD1_FULL_DEBIAN_SIGNED_RELAY_V2_MIGRATED";
    const bool migratedRelayV2=relayConfirm &&
        std::string_view(relayConfirm)=="SD1_FULL_DEBIAN_SIGNED_RELAY_V3_MIGRATED_8P1B";
    const bool migratedRelayV3=relayConfirm &&
        std::string_view(relayConfirm)=="SD1_FULL_DEBIAN_SIGNED_RELAY_V4_MIGRATED_8P5B";
    const bool migratedRelayV4=relayConfirm &&
        std::string_view(relayConfirm)=="SD1_FULL_DEBIAN_SIGNED_RELAY_V5_MIGRATED_10B";
    const std::uint64_t defaultMaxCycle=migratedRelayV4?kV4MigratedMaxCycles:
        migratedRelayV3?kV3MigratedMaxCycles:
        migratedRelayV2?kV2MigratedMaxCycles:
        migratedRelay?8000000000ULL:
        (longRelay?4000000000ULL:1800000000ULL);
    const std::uint64_t maxCycle=migratingV4?kV4MigratedMaxCycles:
        migratingV3?kV3MigratedMaxCycles:
        migratingV2?kV2MigratedMaxCycles:
        (c1Mode || migrating)?4000000000ULL:defaultMaxCycle;
    if (bootCycles<1000000 || bootCycles>maxCycle ||
        ((mode=="save" || mode=="snapshot" || c1Save) && bootCycles>20000000) ||
        (migrating ? replayCycles!=0 :
          (replayCycles==0 || replayCycles>(advancing?20000000ULL:5000000ULL))) ||
        ((advancing || c1Release) && bootCycles>maxCycle-replayCycles) ||
        policy.uartTrigger.size()>128 || policy.uartInput.size()>4096 ||
        policy.postInputBudget>5000000)
      throw std::runtime_error("SD1 cold boot <=20M, staged resume <=20M/step, test replay <=5M; 1.8B default, signed relay <=4B, migrated v1 <=8B, explicit v2 <=8.1B, explicit v3 <=8.5B, explicit v4 <=10B, migration zero cycles");
    if (!std::filesystem::is_regular_file(fw) ||
        !std::filesystem::is_regular_file(dtb) ||
        !std::filesystem::is_regular_file(sd) ||
        std::filesystem::file_size(dtb)>kDtbMax ||
        std::filesystem::file_size(fw)>kDtbAddress-aethercore::l32sim::kRamBase)
      throw std::runtime_error("SD1 firmware/DTB/immutable media layout invalid");
    // Not just caller-supplied SHA strings: standalone restore processes
    // independently bind their own executable plus actual firmware, FDT and
    // immutable ext4 image bytes. The shell also binds Linux/rootfs manifests.
    const std::string currentModelSha=shaFile("/proc/self/exe");
    if (!migrating && currentModelSha!=id.model)
      throw std::runtime_error("SD1 real executable SHA differs from supplied identity");
    for (const auto& [path, expected] : {
        std::pair<std::string,std::string>{fw,id.firmware},
        {dtb,id.dtb},{sd,id.media}})
      if (shaFile(path)!=expected)
        throw std::runtime_error("SD1 real executable/firmware/DTB/SD bytes changed");
    if (migrating) {
      const char* confirm=std::getenv("AETHERCORE_SD1_MODEL_MIGRATE_CONFIRM");
       const std::string_view sourceModel=migratingV4?
           kFrozenMigratedV3Model:migratingV3?
          kFrozenMigratedV2Model:(migratingV2?kFrozenMigratedV1Model:kFrozenPollingModel);
       const std::string_view confirmation=migratingV4?
           "FROZEN_SIGNED_SD1_MODEL_MIGRATE_V4_10B_8272M":migratingV3?
          "FROZEN_SIGNED_SD1_MODEL_MIGRATE_V3_8P5B_8072M":
          (migratingV2?"FROZEN_SIGNED_SD1_MODEL_MIGRATE_V2_8B_7892M":
                       "FROZEN_SIGNED_SD1_MODEL_MIGRATE_V1");
      if (!(confirm && std::string_view(confirm)==confirmation))
         throw std::runtime_error(migratingV4?
             "migrate-v4-10b requires exact E 8272M confirmation":migratingV3?
            "migrate-v3-8p5b requires exact C 8072M confirmation":migratingV2?
            "migrate-v2-8b requires exact 7892M confirmation":
            "migrate-v1 requires explicit frozen-model confirmation");
      if (id.model!=sourceModel || currentModelSha==id.model ||
          !std::filesystem::is_regular_file(argv[14]) ||
          std::filesystem::is_symlink(argv[14]) || shaFile(argv[14])!=id.model)
         throw std::runtime_error(migratingV4?
             "migrate-v4-10b requires exact qualified Host-v3 source executable":migratingV3?
            "migrate-v3-8p5b requires qualified Host-v2 source executable":migratingV2?
            "migrate-v2-8b requires exact qualified migrated-v1 source executable":
            "migrate-v1 requires the real frozen polling executable");
      const std::filesystem::path sourcePrefix(prefix),destPrefix(argv[15]);
      if (sourcePrefix.filename()!="persistent" || destPrefix.filename()!="persistent" ||
          std::filesystem::absolute(sourcePrefix).lexically_normal()==
            std::filesystem::absolute(destPrefix).lexically_normal() ||
          std::filesystem::exists(destPrefix.parent_path()) ||
          std::filesystem::exists(std::string(argv[15])+".pre.host") ||
          std::filesystem::exists(std::string(argv[15])+".pre.rtl"))
        throw std::runtime_error("migrate-v1 requires distinct, absent destination directory");
      const auto ready=sourcePrefix.parent_path()/
           ((migratingV2 || migratingV3 || migratingV4)?".migration-ready":".polling-ready");
      if (std::filesystem::is_symlink(ready) ||
          pollingReadyField(ready,"schema")!="AETSDCP1" ||
          pollingReadyField(ready,"qualification")!=
             ((migratingV2 || migratingV3 || migratingV4)?"PRODUCT_SD1_MIGRATED_WARM_RTL_NOT_DEBIAN_PASS":
                         "PRODUCT_SD1_SPI_POLLING_WARM_NOT_DEBIAN_PASS") ||
          pollingReadyField(ready,"boot_cycles")!=std::to_string(bootCycles))
         throw std::runtime_error(migratingV4?
             "migrate-v4-10b requires authenticated E 8272M source":migratingV3?
            "migrate-v3-8p5b requires authenticated C 8072M source":migratingV2?
            "migrate-v2-8b requires authenticated migrated 7892M source":
            "migrate-v1 requires authentic bounded polling source");
      if (migratingV2 &&
          (bootCycles!=kV2MigrationSourceCycles ||
           sourcePrefix.parent_path().filename()!="7892m" ||
           pollingReadyField(ready,"original_model_sha256")!=kFrozenPollingModel))
        throw std::runtime_error("migrate-v2-8b 7892M origin/lineage mismatch");
      if (migratingV3 &&
          (bootCycles!=kV3MigrationSourceCycles ||
           sourcePrefix.parent_path().filename()!="8072m" ||
           pollingReadyField(ready,"source_cycles")!="8052000000" ||
           pollingReadyField(ready,"previous_model_sha256")!=kFrozenMigratedV1Model ||
           pollingReadyField(ready,"original_model_sha256")!=kFrozenPollingModel ||
           pollingReadyField(ready,"debian_login_claim")!="0"))
        throw std::runtime_error("migrate-v3-8p5b 8072M origin/lineage mismatch");
      if (migratingV4 &&
          (bootCycles!=kV4MigrationSourceCycles ||
           sourcePrefix.parent_path().filename()!="8272m" ||
           pollingReadyField(ready,"source_cycles")!="8252000000" ||
           pollingReadyField(ready,"previous_model_sha256")!=kFrozenMigratedV2Model ||
           pollingReadyField(ready,"original_model_sha256")!=kFrozenPollingModel ||
           pollingReadyField(ready,"debian_login_claim")!="0"))
        throw std::runtime_error("migrate-v4-10b 8272M origin/lineage mismatch");
      for (const auto& [key,sha] : {
          std::pair<std::string_view,std::string_view>{"model_sha256",id.model},
          {"firmware_sha256",id.firmware},{"dtb_sha256",id.dtb},
          {"sd_image_sha256",id.media},{"linux_image_sha256",id.linuxImage},
          {"rootfs_result_sha256",id.rootfs}})
        if (pollingReadyField(ready,key)!=sha)
          throw std::runtime_error("migrate-v1 polling source identity drift");
      for (const auto& [key,ext] : {
          std::pair<std::string_view,std::string_view>{"snapshot_host_sha256",".pre.host"},
          {"snapshot_rtl_sha256",".pre.rtl"}}) {
        const auto snapshot=prefix+std::string(ext);
        if (!std::filesystem::is_regular_file(snapshot) ||
            std::filesystem::is_symlink(snapshot) ||
            pollingReadyField(ready,key)!=shaFile(snapshot))
          throw std::runtime_error("migrate-v1 source Host/RTL SHA does not match manifest");
      }
      const auto progress=sourcePrefix.parent_path()/"card-progress.json";
      if (!std::filesystem::is_regular_file(progress) ||
          std::filesystem::is_symlink(progress) ||
          pollingReadyField(ready,"card_progress_sha256")!=shaFile(progress.string()))
        throw std::runtime_error("migrate-v1 source SD progress SHA does not match manifest");
    }
    if (c1Release && (!std::filesystem::is_regular_file(newMedia) ||
                      shaFile(newMedia)!=newMediaSha))
      throw std::runtime_error("C1 replacement SD image bytes differ from the explicitly pinned SHA");
    VerilatedContext ctx;
    char program[]="product-sd1-checkpoint";
    char* stableArgs[]={program,nullptr};
    ctx.commandArgs(1,stableArgs);
    Top top{&ctx};
    Memory mem;
    TransportRuntimeState transport;
    SdSpiCard card{sd};
    // Explicit C1 profile only: arm BEFORE initialize/first guest step.
    // The strict historical save/snapshot/advance never arm read tracking and
    // continue to emit/require the original schema-v1 SD card state.
    if (c1Save) card.armC1RootfsBoundary();
    Observer obs;
    if (migrating) {
      // Zero guest clocks. The ordinary restore/advance path continues to
      // require self-executable SHA equality and cannot accept foreign state.
      readHost(prefix+".pre.host",id,policy,ctx,mem,transport,card,obs);
      readRtl(prefix+".pre.rtl",top);
      if (top.reset || obs.cycles!=bootCycles || obs.commits==0)
        throw std::runtime_error("migrate-v1 rejected inactive/mismatched Product state");
      Identity nextId=id;
      nextId.model=currentModelSha;
      const std::filesystem::path targetDir=
          std::filesystem::path(argv[15]).parent_path();
      // Create a private sibling directory, verify the entire frozen state,
      // then atomically publish the *directory*. No partial valid destination
      // becomes visible even if a process is killed between Host/RTL writes.
      const std::string pattern=targetDir.string()+
           (migratingV4?".migrate-v4-10b.XXXXXX":
            migratingV3?".migrate-v3-8p5b.XXXXXX":
           migratingV2?".migrate-v2-8b.XXXXXX":".migrate-v1.XXXXXX");
      std::vector<char> pathTemplate(pattern.begin(),pattern.end());
      pathTemplate.push_back('\0');
      if (!::mkdtemp(pathTemplate.data()))
        throw std::runtime_error("migrate-v1 cannot create private staging directory");
      const std::filesystem::path stagingDir(pathTemplate.data());
      const auto stagingPrefix=(stagingDir/"persistent").string();
      try {
        saveRtl(stagingPrefix+".pre.rtl",top);
        writeHost(stagingPrefix+".pre.host",nextId,policy,ctx,mem,transport,card,obs);
        requireExactMigrationBytes(prefix+".pre.host",stagingPrefix+".pre.host",
                                   prefix+".pre.rtl",stagingPrefix+".pre.rtl");
        if (std::filesystem::exists(targetDir))
          throw std::runtime_error("migrate-v1 destination appeared during verification");
        std::filesystem::rename(stagingDir,targetDir);
      } catch (...) {
        std::error_code ignored;
        std::filesystem::remove_all(stagingDir,ignored);
        throw;
      }
       std::cout<<(migratingV4?"PRODUCT_SD1_MODEL_MIGRATE_V4_10B_ZERO_STEP_PASS":
                  migratingV3?"PRODUCT_SD1_MODEL_MIGRATE_V3_8P5B_ZERO_STEP_PASS":
                 migratingV2?"PRODUCT_SD1_MODEL_MIGRATE_V2_8B_ZERO_STEP_PASS":
                             "PRODUCT_SD1_MODEL_MIGRATE_V1_ZERO_STEP_PASS")
               <<" cycles="<<obs.cycles<<" source_model_sha256="<<id.model
               <<" destination_model_sha256="<<nextId.model
               <<" full_host_except_model_sha_identical=1"
               <<" full_rtl_identical=1 no_guest_cycles=1 not_debian_pass=1\n";
      return 0;
    }
    if (mode=="save" || mode=="snapshot" || c1Save) {
      mem.loadAtBase(fw);
      mem.loadBoundedRegion(dtb,kDtbAddress,kDtbMax);
      top.io_spiMiso=1;
      aethercore::l32sim::initialize(top,mem,transport);
      obs.inputStarted=!policy.uartInput.empty() && policy.uartTrigger.empty();
      while (obs.cycles<bootCycles) {
        oneCycle(top,ctx,mem,transport,card,obs,policy,false,true);
        if (obs.cycles%10000000==0)
          std::cerr<<"PRODUCT_SD1_PERSISTENT_BOOT_PROGRESS cycles="<<obs.cycles
                   <<" commits="<<obs.commits<<' '<<card.progress()<<'\n';
        if (top.io_exitValid)
          throw std::runtime_error("SD1 guest exited before bounded checkpoint cycle");
      }
      if (obs.commits==0 || top.reset)
        throw std::runtime_error("SD1 did not retire real Product instructions");
      if (c1Save && !card.c1RootfsBoundaryArmed())
        throw std::runtime_error("C1 cold prefix lost the SD read ledger");
      const bool c1Ready=c1Save && c1CaptureReady(obs,top,transport,card);
      // Completed host clock boundary: savable model + exact DDR/LRSC/CSRs/
      // UART/IRQ/transport/SPI card protocol/SD ext4 COW sector state.
      saveRtl(prefix+".pre.rtl",top);
      writeHost(prefix+".pre.host",id,policy,ctx,mem,transport,card,obs);
      std::cout<<"\nPRODUCT_SD1_PERSISTENT_CHECKPOINT_SAVED cycles="<<obs.cycles
               <<" commits="<<obs.commits<<" opensbi_banner="<<obs.sawOpenSbiBanner
               <<" login_observed="<<obs.sawLogin<<' '<<card.progress()
               <<" full_rtl_ddr_sd_card=1 no_ext4_mount_claim=1 no_pid1_claim=1\n";
      if (c1Save) {
        std::cout<<"PRODUCT_SD1_C1_PREFIX_SAVED cycles="<<obs.cycles
                 <<" consumed_sectors="<<card.c1ConsumedSectorCount()
                 <<" ready="<<c1Ready
                 <<" spi_quiescent="<<c1HostQuiescent(top,transport,card)
                 <<" no_rootfs_swap_claim=1\n";
        return 0;
      }
      if (mode=="snapshot") return 0;
    } else {
      readHost(prefix+".pre.host",id,policy,ctx,mem,transport,card,obs);
      readRtl(prefix+".pre.rtl",top);
      if (top.reset || obs.cycles!=bootCycles || obs.commits==0)
        throw std::runtime_error("SD1 restored snapshot is not the bounded Product checkpoint");
      if ((c1Advance || c1Release) && !card.c1RootfsBoundaryArmed())
        throw std::runtime_error("C1 cannot resume a strict schema-v1 SD prefix");
      std::cout<<"PRODUCT_SD1_PERSISTENT_CHECKPOINT_RESTORED cycles="<<obs.cycles
               <<" commits="<<obs.commits<<" opensbi_banner="<<obs.sawOpenSbiBanner
               <<" login_observed="<<obs.sawLogin<<' '<<card.progress()
               <<" full_rtl_ddr_sd_card=1 no_ext4_mount_claim=1 no_pid1_claim=1\n";
    }
    Identity continuationId=id;
    Policy continuationPolicy=policy;
    if (c1Release) {
      // Read BOTH complete old RTL and old media first. The ordinary host
      // source/model/firmware/DTB SHA matching above remains immutable, and
      // SdSpiCard::restore transactionally verifies its v2 read ledger.
      // Only the intact, rootfs-unmounted C1 UART boundary may swap media.
      requireC1Ready(obs);
      if (!c1HostQuiescent(top,transport,card))
        throw std::runtime_error("C1 release requires RTL SPI pins, card and host DDR idle");
      const auto validatedPriorReads=card.c1ConsumedSectorCount();
      card.rebindC1Rootfs(newMedia);  // checks SPI quiescence, COW and every consumed sector
      continuationId.media=newMediaSha;
      continuationId.rootfs=newRootfsSha;
      continuationPolicy.uartTrigger="";
      continuationPolicy.uartInput=std::string(kC1Go);
      continuationPolicy.postInputBudget=0;
      obs.inputStarted=true;
      obs.inputIndex=0;
      obs.seipAtInputStart=obs.seip;
      std::cout<<"PRODUCT_SD1_C1_OLD_PREFIX_VERIFIED_AND_MEDIA_REBOUND"
               <<" cycles="<<obs.cycles
               <<" new_sd_sha256="<<newMediaSha
               <<" new_rootfs_sha256="<<newRootfsSha
               <<" consumed_sectors="<<validatedPriorReads
               <<" ready=1 release_token=AETHERCORE_C1_GO\n";
    }
    obs.traceHash=kFnv;
    for (std::uint64_t i=0;i<replayCycles;++i) {
      oneCycle(top,ctx,mem,transport,card,obs,continuationPolicy,true,false);
      if ((advancing || c1Release) && top.io_exitValid)
        throw std::runtime_error("SD1 guest exited during staged warm advancement");
    }
    if (c1Release) {
      // No magic success claim: this only establishes the verified media
      // handoff and actual UART injection. A separate real Linux/guest marker
      // gate must observe ext4 mount and distro native init in later stages.
      if (obs.uart.find(kC1Fail)!=std::string::npos ||
          obs.uart.find("Kernel panic - not syncing")!=std::string::npos)
        throw std::runtime_error("C1 guest failed immediately after rootfs release");
      if (obs.inputIndex!=continuationPolicy.uartInput.size())
        throw std::runtime_error("C1 GO UART release was not fully accepted by the guest");
      saveRtl(advanceDest+".pre.rtl",top);
      writeHost(advanceDest+".pre.host",continuationId,continuationPolicy,
                ctx,mem,transport,card,obs);
      std::cout<<"PRODUCT_SD1_C1_MEDIA_REBOUND_AND_GO_SENT"
               <<" cycles="<<obs.cycles<<" commits="<<obs.commits
               <<" new_sd_sha256="<<newMediaSha
               <<" new_rootfs_sha256="<<newRootfsSha
               <<" no_native_init_claim=1\n";
      return 0;
    }
    if (advancing) {
      const bool c1Ready=c1Advance && c1CaptureReady(obs,top,transport,card);
      // Write a NEW immutable prestate; the original source can be reused for
      // deterministic replays and remains untouched across CI runs.
      saveRtl(advanceDest+".pre.rtl",top);
      writeHost(advanceDest+".pre.host",id,policy,ctx,mem,transport,card,obs);
      std::cout<<"PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles="<<bootCycles
               <<" new_cycles="<<obs.cycles<<" delta="<<replayCycles
               <<" commits="<<obs.commits<<" trace_hash=0x"<<std::hex<<obs.traceHash
               <<std::dec<<' '<<card.progress()
               <<" no_ext4_mount_claim=1 no_pid1_claim=1\n";
      if (c1Advance)
        std::cout<<"PRODUCT_SD1_C1_PREFIX_ADVANCED consumed_sectors="
                 <<card.c1ConsumedSectorCount()
                 <<" ready="<<c1Ready
                 <<" spi_quiescent="<<c1HostQuiescent(top,transport,card)
                 <<" no_rootfs_swap_claim=1\n";
      return 0;
    }
    result(obs,ctx,mem,transport,card,top);
    saveRtl(prefix+(mode=="save"?".continuous.rtl":".restored.rtl"),top);
    writeHost(prefix+(mode=="save"?".continuous.host":".restored.host"),
              id,policy,ctx,mem,transport,card,obs);
    return 0;
  } catch (const std::exception& e) {
    std::cerr<<"PRODUCT_SD1_PERSISTENT_CHECKPOINT_REJECTED: "<<e.what()<<'\n';
    return 1;
  }
}
