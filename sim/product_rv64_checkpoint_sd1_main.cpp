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

#include <array>
#include <cctype>
#include <cstdint>
#include <cstdlib>
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
constexpr std::uint64_t kFnv = 14695981039346656037ULL;
constexpr std::uint64_t kPrime = 1099511628211ULL;
constexpr std::uint64_t kDtbAddress = 0x87f00000ULL;
constexpr std::size_t kDtbMax = 1ULL << 20;
constexpr std::size_t kMaxUart = 32ULL << 20;
constexpr std::uint64_t kMaxCardSnapshot = 512ULL << 20;
constexpr std::size_t kRecent = 16;
constexpr std::uint64_t kLinuxBase = 0x80200000ULL;

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
    if (argc!=14 && argc!=15 && argc!=17 && argc!=18)
      throw std::runtime_error("usage: SD1_CHECKPOINT save|snapshot|restore|advance FW DTB SD PREFIX MODEL_SHA FW_SHA DTB_SHA SD_SHA LINUX_SHA ROOTFS_SHA START_CYCLES SEGMENT_CYCLES [UART_TRIGGER UART_COMMAND POST_INPUT_BUDGET] [ADVANCE_DEST_PREFIX]");
    const std::string mode=argv[1],fw=argv[2],dtb=argv[3],sd=argv[4],prefix=argv[5];
    const Identity id{argv[6],argv[7],argv[8],argv[9],argv[10],argv[11]};
    id.validate();
    if (mode!="save" && mode!="snapshot" && mode!="restore" && mode!="advance")
      throw std::runtime_error("invalid SD1 checkpoint mode");
    const bool advancing=mode=="advance";
    if (advancing!=(argc==15 || argc==18))
      throw std::runtime_error("advance needs an independent destination prefix; other modes forbid it");
    const auto bootCycles=std::stoull(argv[12],nullptr,0);
    const auto replayCycles=std::stoull(argv[13],nullptr,0);
    const bool hasPolicy=argc==17 || argc==18;
    Policy policy{hasPolicy?argv[14]:"",hasPolicy?argv[15]:"",
                  hasPolicy?std::stoull(argv[16],nullptr,0):0};
    const std::string advanceDest=advancing?argv[argc-1]:"";
    if (!policy.uartInput.empty() && policy.uartInput.back()!='\n')
      policy.uartInput.push_back('\n');
    if (bootCycles<1000000 || bootCycles>1800000000 ||
        ((mode=="save" || mode=="snapshot") && bootCycles>20000000) ||
        replayCycles==0 || replayCycles>(advancing?20000000ULL:5000000ULL) ||
        (advancing && bootCycles>1800000000ULL-replayCycles) ||
        policy.uartTrigger.size()>128 || policy.uartInput.size()>4096 ||
        policy.postInputBudget>5000000)
      throw std::runtime_error("SD1 cold boot <=20M, staged resume <=20M/step, test replay <=5M; never implicit 1.8B cold boot");
    if (!std::filesystem::is_regular_file(fw) ||
        !std::filesystem::is_regular_file(dtb) ||
        !std::filesystem::is_regular_file(sd) ||
        std::filesystem::file_size(dtb)>kDtbMax ||
        std::filesystem::file_size(fw)>kDtbAddress-aethercore::l32sim::kRamBase)
      throw std::runtime_error("SD1 firmware/DTB/immutable media layout invalid");
    // Not just caller-supplied SHA strings: standalone restore processes
    // independently bind their own executable plus actual firmware, FDT and
    // immutable ext4 image bytes. The shell also binds Linux/rootfs manifests.
    for (const auto& [path, expected] : {
        std::pair<std::string,std::string>{"/proc/self/exe",id.model},
        {fw,id.firmware},{dtb,id.dtb},{sd,id.media}})
      if (shaFile(path)!=expected)
        throw std::runtime_error("SD1 real executable/firmware/DTB/SD bytes changed");
    VerilatedContext ctx;
    char program[]="product-sd1-checkpoint";
    char* stableArgs[]={program,nullptr};
    ctx.commandArgs(1,stableArgs);
    Top top{&ctx};
    Memory mem;
    TransportRuntimeState transport;
    SdSpiCard card{sd};
    Observer obs;
    if (mode=="save" || mode=="snapshot") {
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
      // Completed host clock boundary: savable model + exact DDR/LRSC/CSRs/
      // UART/IRQ/transport/SPI card protocol/SD ext4 COW sector state.
      saveRtl(prefix+".pre.rtl",top);
      writeHost(prefix+".pre.host",id,policy,ctx,mem,transport,card,obs);
      std::cout<<"\nPRODUCT_SD1_PERSISTENT_CHECKPOINT_SAVED cycles="<<obs.cycles
               <<" commits="<<obs.commits<<" opensbi_banner="<<obs.sawOpenSbiBanner
               <<" login_observed="<<obs.sawLogin<<' '<<card.progress()
               <<" full_rtl_ddr_sd_card=1 no_ext4_mount_claim=1 no_pid1_claim=1\n";
      if (mode=="snapshot") return 0;
    } else {
      readHost(prefix+".pre.host",id,policy,ctx,mem,transport,card,obs);
      readRtl(prefix+".pre.rtl",top);
      if (top.reset || obs.cycles!=bootCycles || obs.commits==0)
        throw std::runtime_error("SD1 restored snapshot is not the bounded Product checkpoint");
      std::cout<<"PRODUCT_SD1_PERSISTENT_CHECKPOINT_RESTORED cycles="<<obs.cycles
               <<" commits="<<obs.commits<<" opensbi_banner="<<obs.sawOpenSbiBanner
               <<" login_observed="<<obs.sawLogin<<' '<<card.progress()
               <<" full_rtl_ddr_sd_card=1 no_ext4_mount_claim=1 no_pid1_claim=1\n";
    }
    obs.traceHash=kFnv;
    for (std::uint64_t i=0;i<replayCycles;++i) {
      oneCycle(top,ctx,mem,transport,card,obs,policy,true,false);
      if (advancing && top.io_exitValid)
        throw std::runtime_error("SD1 guest exited during staged warm advancement");
    }
    if (advancing) {
      // Write a NEW immutable prestate; the original source can be reused for
      // deterministic replays and remains untouched across CI runs.
      saveRtl(advanceDest+".pre.rtl",top);
      writeHost(advanceDest+".pre.host",id,policy,ctx,mem,transport,card,obs);
      std::cout<<"PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED old_cycles="<<bootCycles
               <<" new_cycles="<<obs.cycles<<" delta="<<replayCycles
               <<" commits="<<obs.commits<<" trace_hash=0x"<<std::hex<<obs.traceHash
               <<std::dec<<' '<<card.progress()
               <<" no_ext4_mount_claim=1 no_pid1_claim=1\n";
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
