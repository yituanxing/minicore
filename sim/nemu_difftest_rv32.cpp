#include "nemu_difftest.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <dlfcn.h>
#include <fstream>
#include <iomanip>
#include <limits>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
constexpr bool kToDut = false;
constexpr bool kToRef = true;
constexpr std::size_t kTraceDepth = 32;
constexpr std::uint32_t kMmioBase = 0x10000000U;
constexpr std::size_t kMmioSize = 4096;
char kMmioName[] = "aethercore-rv32-mmio";

constexpr std::uint32_t kSystemOpcode = 0x73U;
constexpr std::uint32_t kMstatus = 0x300U;
constexpr std::uint32_t kMisa = 0x301U;
constexpr std::uint32_t kMtvec = 0x305U;
constexpr std::uint32_t kMscratch = 0x340U;
constexpr std::uint32_t kMepc = 0x341U;
constexpr std::uint32_t kMcause = 0x342U;
constexpr std::uint32_t kMtval = 0x343U;
constexpr std::uint32_t kRv32ImMisa = 0x40001100U;
constexpr std::uint32_t kMstatusMie = 1U << 3;
constexpr std::uint32_t kMstatusMpie = 1U << 7;
constexpr std::uint32_t kMstatusMppMachine = 3U << 11;

// Frozen ABI for OpenXiangShan/NEMU revision
// 8601834e4889e6bf3b6113eb5f824ba7689126f5 with the repository's derived
// riscv32-minicore-ref_defconfig. The validated regcpy region is exactly
// 32 32-bit GPRs followed by the 32-bit PC.
struct NemuState32 {
  std::uint32_t gpr[32]{};
  std::uint32_t pc = 0;
};

static_assert(sizeof(NemuState32) == 132);
static_assert(offsetof(NemuState32, pc) == 32 * sizeof(std::uint32_t));

struct ZicsrState32 {
  std::uint32_t mstatus = 0;
  std::uint32_t mtvec = 0;
  std::uint32_t mscratch = 0;
  std::uint32_t mepc = 0;
  std::uint32_t mcause = 0;
  std::uint32_t mtval = 0;
};

std::vector<std::uint8_t> readImage(const std::string& path) {
  std::ifstream input(path, std::ios::binary | std::ios::ate);
  if (!input) throw std::runtime_error("RV32 DiffTest cannot open image: " + path);
  const auto size = input.tellg();
  if (size < 0) throw std::runtime_error("RV32 DiffTest cannot determine image size: " + path);
  std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
  input.seekg(0);
  if (!bytes.empty()) input.read(reinterpret_cast<char*>(bytes.data()), size);
  if (!input && !bytes.empty()) {
    throw std::runtime_error("RV32 DiffTest cannot read image: " + path);
  }
  return bytes;
}

template <typename Function>
Function loadSymbol(void* handle, const char* name) {
  dlerror();
  void* symbol = dlsym(handle, name);
  if (const char* error = dlerror()) {
    throw std::runtime_error(std::string("RV32 DiffTest missing NEMU symbol ") + name +
                             ": " + error);
  }
  return reinterpret_cast<Function>(symbol);
}

std::optional<std::uint64_t> mismatchInjectionCommit() {
  const char* raw = std::getenv("AETHERCORE_RV32_DIFFTEST_INJECT_MISMATCH_AT");
  if (raw == nullptr || *raw == '\0') return std::nullopt;
  std::size_t consumed = 0;
  const std::string text{raw};
  const auto value = std::stoull(text, &consumed, 0);
  if (consumed != text.size()) {
    throw std::runtime_error("invalid AETHERCORE_RV32_DIFFTEST_INJECT_MISMATCH_AT: " + text);
  }
  return value;
}

std::string hex32(std::uint32_t value) {
  std::ostringstream out;
  out << "0x" << std::hex << std::setw(8) << std::setfill('0') << value;
  return out.str();
}

std::string byteHex(std::uint8_t value) {
  std::ostringstream out;
  out << std::hex << std::setw(2) << std::setfill('0') << static_cast<unsigned>(value);
  return out.str();
}

bool isZicsrInstruction(std::uint32_t instruction) {
  return (instruction & 0x7fU) == kSystemOpcode && ((instruction >> 12) & 0x7U) != 0;
}
}  // namespace

class NemuDifftest::Impl {
 public:
  using Init = void (*)();
  using Memcpy = void (*)(std::uint32_t, void*, std::size_t, bool);
  using Regcpy = void (*)(void*, bool);
  using Exec = void (*)(std::uint64_t);
  using NewSpace = std::uint8_t* (*)(int);
  using IoCallback = void (*)(std::uint32_t, int, bool);
  using AddMmioMap = void (*)(char*, std::uint32_t, std::uint8_t*, int, IoCallback);

  Impl(const std::string& sharedObject, const std::string& imagePath,
       std::uint64_t resetPc, std::uint64_t ramSize)
      : resetPc_(checkedAddress(resetPc, "reset PC")),
        image_(readImage(imagePath)),
        injectMismatchAt_(mismatchInjectionCommit()) {
    if (image_.size() > ramSize) {
      throw std::runtime_error("RV32 DiffTest image is larger than configured reference RAM");
    }
    if (ramSize != 64ULL * 1024ULL * 1024ULL) {
      throw std::runtime_error("RV32 DiffTest expects the frozen 64 MiB NEMU reference RAM");
    }

    handle_ = dlopen(sharedObject.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle_) {
      throw std::runtime_error(std::string("RV32 DiffTest cannot load NEMU shared object: ") +
                               dlerror());
    }

    try {
      init_ = loadSymbol<Init>(handle_, "difftest_init");
      memcpy_ = loadSymbol<Memcpy>(handle_, "difftest_memcpy");
      regcpy_ = loadSymbol<Regcpy>(handle_, "difftest_regcpy");
      exec_ = loadSymbol<Exec>(handle_, "difftest_exec");
      newSpace_ = loadSymbol<NewSpace>(handle_, "new_space");
      addMmioMap_ = loadSymbol<AddMmioMap>(handle_, "add_mmio_map");

      init_();

      // The frozen RV32 reference intentionally contains no platform device
      // model. Register one passive page so UART/exit MMIO instructions execute
      // in NEMU and their store bytes remain observable instead of being skipped.
      mmioSpace_ = newSpace_(static_cast<int>(kMmioSize));
      if (mmioSpace_ == nullptr) {
        throw std::runtime_error("RV32 DiffTest failed to allocate reference MMIO space");
      }
      std::memset(mmioSpace_, 0, kMmioSize);
      addMmioMap_(kMmioName, kMmioBase, mmioSpace_, static_cast<int>(kMmioSize), nullptr);

      if (!image_.empty()) memcpy_(resetPc_, image_.data(), image_.size(), kToRef);

      NemuState32 initial{};
      initial.pc = resetPc_;
      regcpy_(&initial, kToRef);
      dutRegs_.fill(0);
    } catch (...) {
      dlclose(handle_);
      handle_ = nullptr;
      throw;
    }
  }

  ~Impl() {
    if (handle_) dlclose(handle_);
  }

  void check(const DifftestCommit& commit) {
    if (commit.exception) {
      fail("normal RV32 DiffTest received an exception commit at pc=" +
           hex32(checkedAddress(commit.pc, "commit PC")));
    }

    const auto commitPc = checkedAddress(commit.pc, "commit PC");
    NemuState32 before{};
    regcpy_(&before, kToDut);
    comparePc(before.pc, commitPc, "before reference execution");
    compareRegisters(before, "before reference execution");

    const std::uint32_t imageInst = instructionAt(commitPc);
    if (imageInst != commit.inst) {
      fail("DUT instruction " + hex32(commit.inst) + " differs from image instruction " +
           hex32(imageInst) + " at pc=" + hex32(commitPc));
    }

    NemuState32 after{};
    const bool shadowStep = isZicsrInstruction(commit.inst);
    if (shadowStep) {
      after = executeZicsr(before, commit.inst);
      regcpy_(&after, kToRef);
      ++zicsrShadowSteps_;
    } else {
      exec_(1);
      regcpy_(&after, kToDut);
    }

    if (commit.rdWrite && commit.rd != 0) {
      dutRegs_[commit.rd] = static_cast<std::uint32_t>(commit.rdData);
    }
    dutRegs_[0] = 0;

    // CI uses this only for deliberate negative probes. The normal path has no
    // injection environment variable and remains a pure architectural check.
    if (injectMismatchAt_ && checked_ == *injectMismatchAt_) dutRegs_[31] ^= 1U;

    compareRegisters(after, "after reference execution");
    if (commit.memValid && commit.memWrite) compareStore(commit);

    std::ostringstream line;
    line << "#" << checked_ << " pc=" << hex32(commitPc)
         << " inst=" << hex32(commit.inst);
    if (commit.rdWrite) {
      line << " x" << static_cast<unsigned>(commit.rd) << "="
           << hex32(static_cast<std::uint32_t>(commit.rdData));
    }
    if (commit.memValid && commit.memWrite) {
      line << " store[" << hex32(checkedAddress(commit.memAddr, "store address"))
           << "] mask=0x" << std::hex << static_cast<unsigned>(commit.memWmask) << std::dec;
    }
    if (shadowStep) line << " reference=zicsr-shadow";
    line << " next=" << hex32(after.pc);
    trace_.push_back(line.str());
    if (trace_.size() > kTraceDepth) trace_.pop_front();
    ++checked_;
  }

  std::uint64_t checkedCommits() const { return checked_; }
  std::uint64_t zicsrShadowSteps() const { return zicsrShadowSteps_; }

 private:
  static std::uint32_t checkedAddress(std::uint64_t value, const char* label) {
    if (value > std::numeric_limits<std::uint32_t>::max()) {
      throw std::runtime_error(std::string("RV32 DiffTest ") + label +
                               " exceeds 32 bits");
    }
    return static_cast<std::uint32_t>(value);
  }

  std::uint32_t readCsr(std::uint32_t address) const {
    switch (address) {
      case kMstatus: return zicsr_.mstatus;
      case kMisa: return kRv32ImMisa;
      case kMtvec: return zicsr_.mtvec;
      case kMscratch: return zicsr_.mscratch;
      case kMepc: return zicsr_.mepc;
      case kMcause: return zicsr_.mcause;
      case kMtval: return zicsr_.mtval;
      default: fail("Zicsr shadow read of unimplemented CSR " + hex32(address));
    }
  }

  bool csrWritable(std::uint32_t address) const {
    switch (address) {
      case kMstatus:
      case kMtvec:
      case kMscratch:
      case kMepc:
      case kMcause:
      case kMtval:
        return true;
      case kMisa:
        return false;
      default:
        fail("Zicsr shadow legality query for unimplemented CSR " + hex32(address));
    }
  }

  void writeCsr(std::uint32_t address, std::uint32_t value) {
    switch (address) {
      case kMstatus:
        zicsr_.mstatus = (value & (kMstatusMie | kMstatusMpie)) | kMstatusMppMachine;
        return;
      case kMtvec:
        zicsr_.mtvec = value & ~std::uint32_t{3};
        return;
      case kMscratch:
        zicsr_.mscratch = value;
        return;
      case kMepc:
        zicsr_.mepc = value & ~std::uint32_t{3};
        return;
      case kMcause:
        zicsr_.mcause = value;
        return;
      case kMtval:
        zicsr_.mtval = value;
        return;
      case kMisa:
        fail("Zicsr shadow attempted to write read-only misa");
      default:
        fail("Zicsr shadow write of unimplemented CSR " + hex32(address));
    }
  }

  NemuState32 executeZicsr(const NemuState32& before, std::uint32_t instruction) {
    const std::uint32_t funct3 = (instruction >> 12) & 0x7U;
    const std::uint32_t operation = funct3 & 0x3U;
    const bool immediate = (funct3 & 0x4U) != 0;
    const std::uint32_t rd = (instruction >> 7) & 0x1fU;
    const std::uint32_t sourceField = (instruction >> 15) & 0x1fU;
    const std::uint32_t address = instruction >> 20;

    if (operation == 0) {
      fail("Zicsr shadow received reserved SYSTEM funct3=" + std::to_string(funct3));
    }

    const std::uint32_t oldValue = readCsr(address);
    const std::uint32_t source = immediate ? sourceField : before.gpr[sourceField];
    const bool writeIntent = operation == 1 || sourceField != 0;

    if (writeIntent && !csrWritable(address)) {
      fail("Zicsr shadow received a normal commit that writes read-only CSR " +
           hex32(address));
    }

    NemuState32 after = before;
    after.pc = before.pc + 4;
    if (rd != 0) after.gpr[rd] = oldValue;
    after.gpr[0] = 0;

    if (writeIntent) {
      std::uint32_t newValue = source;
      if (operation == 2) newValue = oldValue | source;
      if (operation == 3) newValue = oldValue & ~source;
      writeCsr(address, newValue);
    }

    return after;
  }

  std::uint32_t instructionAt(std::uint32_t pc) const {
    if (pc < resetPc_ || static_cast<std::uint64_t>(pc - resetPc_) + 4 > image_.size()) {
      fail("DUT committed outside the loaded image at pc=" + hex32(pc));
    }
    const std::size_t offset = static_cast<std::size_t>(pc - resetPc_);
    return std::uint32_t(image_[offset]) |
           (std::uint32_t(image_[offset + 1]) << 8) |
           (std::uint32_t(image_[offset + 2]) << 16) |
           (std::uint32_t(image_[offset + 3]) << 24);
  }

  void comparePc(std::uint32_t reference, std::uint32_t dut, const char* phase) const {
    if (reference != dut) {
      fail(std::string(phase) + ": NEMU pc=" + hex32(reference) +
           " DUT pc=" + hex32(dut));
    }
  }

  void compareRegisters(const NemuState32& reference, const char* phase) const {
    for (unsigned index = 0; index < dutRegs_.size(); ++index) {
      if (reference.gpr[index] != dutRegs_[index]) {
        fail(std::string(phase) + ": x" + std::to_string(index) +
             " NEMU=" + hex32(reference.gpr[index]) +
             " DUT=" + hex32(dutRegs_[index]));
      }
    }
  }

  void compareStore(const DifftestCommit& commit) const {
    const auto address = checkedAddress(commit.memAddr, "store address");
    std::array<std::uint8_t, 4> referenceBytes{};

    const std::uint64_t mmioEnd = static_cast<std::uint64_t>(kMmioBase) + kMmioSize;
    if (address >= kMmioBase && static_cast<std::uint64_t>(address) + referenceBytes.size() <= mmioEnd) {
      std::memcpy(referenceBytes.data(), mmioSpace_ + (address - kMmioBase),
                  referenceBytes.size());
    } else {
      memcpy_(address, referenceBytes.data(), referenceBytes.size(), kToDut);
    }

    for (unsigned byte = 0; byte < referenceBytes.size(); ++byte) {
      if (((commit.memWmask >> byte) & 1U) == 0) continue;
      const auto dutByte = static_cast<std::uint8_t>(commit.memWdata >> (byte * 8));
      if (referenceBytes[byte] != dutByte) {
        fail("store mismatch at " + hex32(address + byte) +
             " NEMU=0x" + byteHex(referenceBytes[byte]) +
             " DUT=0x" + byteHex(dutByte));
      }
    }
  }

  [[noreturn]] void fail(const std::string& reason) const {
    std::ostringstream out;
    out << "RV32 DiffTest mismatch after " << checked_ << " matched commits: " << reason;
    if (!trace_.empty()) {
      out << "\nRecent matched commits:";
      for (const auto& line : trace_) out << "\n  " << line;
    }
    throw std::runtime_error(out.str());
  }

  void* handle_ = nullptr;
  Init init_ = nullptr;
  Memcpy memcpy_ = nullptr;
  Regcpy regcpy_ = nullptr;
  Exec exec_ = nullptr;
  NewSpace newSpace_ = nullptr;
  AddMmioMap addMmioMap_ = nullptr;
  std::uint8_t* mmioSpace_ = nullptr;

  std::uint32_t resetPc_ = 0;
  std::vector<std::uint8_t> image_;
  std::array<std::uint32_t, 32> dutRegs_{};
  ZicsrState32 zicsr_{};
  std::deque<std::string> trace_;
  std::optional<std::uint64_t> injectMismatchAt_;
  std::uint64_t checked_ = 0;
  std::uint64_t zicsrShadowSteps_ = 0;
};

NemuDifftest::NemuDifftest(const std::string& sharedObject, const std::string& imagePath,
                           std::uint64_t resetPc, std::uint64_t ramSize)
    : impl_(std::make_unique<Impl>(sharedObject, imagePath, resetPc, ramSize)) {}

NemuDifftest::~NemuDifftest() = default;
NemuDifftest::NemuDifftest(NemuDifftest&&) noexcept = default;
NemuDifftest& NemuDifftest::operator=(NemuDifftest&&) noexcept = default;

void NemuDifftest::check(const DifftestCommit& commit) { impl_->check(commit); }
std::uint64_t NemuDifftest::checkedCommits() const { return impl_->checkedCommits(); }
std::uint64_t NemuDifftest::zicsrShadowSteps() const { return impl_->zicsrShadowSteps(); }
