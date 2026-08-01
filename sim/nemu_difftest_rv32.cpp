#include "nemu_difftest_rv32.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <dlfcn.h>
#include <fstream>
#include <iomanip>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <utility>
#include <vector>

namespace {
constexpr bool kToDut = false;
constexpr bool kToRef = true;
constexpr std::size_t kTraceDepth = 32;
constexpr std::size_t kRv32StateBytes = 33 * sizeof(std::uint32_t);

// Exact state copied by OpenXiangShan/NEMU revision
// 8601834e4889e6bf3b6113eb5f824ba7689126f5 with the frozen RV32 reference
// configuration. The ABI probe proves that regcpy touches exactly these 132
// bytes and leaves the following guard bytes unchanged.
struct NemuRv32State {
  std::uint32_t gpr[32]{};
  std::uint32_t pc = 0;
};

static_assert(sizeof(NemuRv32State) == kRv32StateBytes);
static_assert(offsetof(NemuRv32State, pc) == 32 * sizeof(std::uint32_t));

std::vector<std::uint8_t> readImage(const std::string& path) {
  std::ifstream input(path, std::ios::binary | std::ios::ate);
  if (!input) throw std::runtime_error("RV32 DiffTest cannot open image: " + path);
  const auto size = input.tellg();
  if (size < 0) throw std::runtime_error("RV32 DiffTest cannot determine image size: " + path);
  std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
  input.seekg(0);
  if (!bytes.empty()) input.read(reinterpret_cast<char*>(bytes.data()), size);
  if (!input && !bytes.empty()) throw std::runtime_error("RV32 DiffTest cannot read image: " + path);
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

std::uint32_t narrow32(std::uint64_t value, const char* field) {
  if (value > std::numeric_limits<std::uint32_t>::max()) {
    throw std::runtime_error(std::string("RV32 DiffTest received a non-RV32 ") + field +
                             " value");
  }
  return static_cast<std::uint32_t>(value);
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
}  // namespace

class NemuDifftest::Impl {
 public:
  using Init = void (*)();
  using Memcpy = void (*)(std::uint32_t, void*, std::size_t, bool);
  using Regcpy = void (*)(void*, bool);
  using Exec = void (*)(std::uint64_t);

  Impl(const std::string& sharedObject, const std::string& imagePath,
       std::uint64_t resetPc, std::uint64_t ramSize)
      : resetPc_(narrow32(resetPc, "reset PC")),
        ramSize_(ramSize),
        image_(readImage(imagePath)) {
    if (ramSize == 0 || ramSize > std::numeric_limits<std::uint32_t>::max()) {
      throw std::runtime_error("RV32 DiffTest RAM size must fit in the 32-bit address space");
    }
    if (image_.size() > ramSize_) {
      throw std::runtime_error("RV32 DiffTest image is larger than configured reference RAM");
    }

    const std::uint64_t ramEnd = static_cast<std::uint64_t>(resetPc_) + ramSize_;
    if (ramEnd > (std::uint64_t{1} << 32)) {
      throw std::runtime_error("RV32 DiffTest RAM range exceeds the 32-bit address space");
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

      init_();
      if (!image_.empty()) memcpy_(resetPc_, image_.data(), image_.size(), kToRef);

      NemuRv32State initial{};
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
           hex32(narrow32(commit.pc, "commit PC")));
    }

    const std::uint32_t commitPc = narrow32(commit.pc, "commit PC");
    NemuRv32State before{};
    regcpy_(&before, kToDut);
    comparePc(before.pc, commitPc, "before reference execution");
    compareRegisters(before, "before reference execution");

    const std::uint32_t imageInst = instructionAt(commitPc);
    if (imageInst != commit.inst) {
      fail("DUT instruction " + hex32(commit.inst) + " differs from image instruction " +
           hex32(imageInst) + " at pc=" + hex32(commitPc));
    }

    exec_(1);

    NemuRv32State after{};
    regcpy_(&after, kToDut);

    if (commit.rdWrite && commit.rd != 0) {
      if (commit.rd >= dutRegs_.size()) fail("DUT retirement names an invalid register");
      dutRegs_[commit.rd] = narrow32(commit.rdData, "register result");
    }
    dutRegs_[0] = 0;
    compareRegisters(after, "after reference execution");

    if (commit.memValid && commit.memWrite) compareStore(commit);

    std::ostringstream line;
    line << "#" << checked_ << " pc=" << hex32(commitPc)
         << " inst=" << hex32(commit.inst);
    if (commit.rdWrite) {
      line << " x" << static_cast<unsigned>(commit.rd) << "="
           << hex32(narrow32(commit.rdData, "register result"));
    }
    if (commit.memValid && commit.memWrite) {
      line << " store[" << hex32(narrow32(commit.memAddr, "store address"))
           << "] mask=0x" << std::hex << static_cast<unsigned>(commit.memWmask)
           << std::dec;
    }
    line << " next=" << hex32(after.pc);
    trace_.push_back(line.str());
    if (trace_.size() > kTraceDepth) trace_.pop_front();
    ++checked_;
  }

  std::uint64_t checkedCommits() const { return checked_; }

 private:
  std::uint32_t instructionAt(std::uint32_t pc) const {
    if (pc < resetPc_) fail("DUT committed below the loaded image at pc=" + hex32(pc));
    const std::uint64_t offset64 = static_cast<std::uint64_t>(pc) - resetPc_;
    if (offset64 + 4 > image_.size()) {
      fail("DUT committed outside the loaded image at pc=" + hex32(pc));
    }
    const std::size_t offset = static_cast<std::size_t>(offset64);
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

  void compareRegisters(const NemuRv32State& reference, const char* phase) const {
    for (unsigned index = 0; index < dutRegs_.size(); ++index) {
      if (reference.gpr[index] != dutRegs_[index]) {
        fail(std::string(phase) + ": x" + std::to_string(index) +
             " NEMU=" + hex32(reference.gpr[index]) +
             " DUT=" + hex32(dutRegs_[index]));
      }
    }
  }

  bool inRam(std::uint32_t address, std::size_t bytes) const {
    if (address < resetPc_) return false;
    const std::uint64_t offset = static_cast<std::uint64_t>(address) - resetPc_;
    return offset <= ramSize_ && bytes <= ramSize_ - offset;
  }

  void compareStore(const DifftestCommit& commit) const {
    const std::uint32_t address = narrow32(commit.memAddr, "store address");
    constexpr std::size_t kBusBytes = 4;

    // Platform MMIO (UART/exit) is verified by the DUT runner itself. The
    // frozen GCC workload exits before the MMIO store reaches architectural
    // retirement, but keeping this boundary explicit prevents an invalid RAM
    // copy if a later workload retires an MMIO write.
    if (!inRam(address, kBusBytes)) return;

    if ((commit.memWmask & 0xf0U) != 0) {
      fail("RV32 store used mask bits outside the four-byte data bus");
    }

    std::array<std::uint8_t, kBusBytes> referenceBytes{};
    memcpy_(address, referenceBytes.data(), referenceBytes.size(), kToDut);
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

  std::uint32_t resetPc_ = 0;
  std::uint64_t ramSize_ = 0;
  std::vector<std::uint8_t> image_;
  std::array<std::uint32_t, 32> dutRegs_{};
  std::deque<std::string> trace_;
  std::uint64_t checked_ = 0;
};

NemuDifftest::NemuDifftest(const std::string& sharedObject,
                           const std::string& imagePath,
                           std::uint64_t resetPc,
                           std::uint64_t ramSize)
    : impl_(std::make_unique<Impl>(sharedObject, imagePath, resetPc, ramSize)) {}

NemuDifftest::~NemuDifftest() = default;
NemuDifftest::NemuDifftest(NemuDifftest&&) noexcept = default;
NemuDifftest& NemuDifftest::operator=(NemuDifftest&&) noexcept = default;

void NemuDifftest::check(const DifftestCommit& commit) { impl_->check(commit); }
std::uint64_t NemuDifftest::checkedCommits() const { return impl_->checkedCommits(); }
