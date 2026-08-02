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
constexpr std::size_t kMmioSize = 4096;
constexpr std::uint32_t kPlatformMmioBase = 0x10000000U;
constexpr std::uint32_t kMtimecmpBase = 0x02004000U;
constexpr std::uint32_t kMtimePageBase = 0x0200b000U;
char kPlatformMmioName[] = "aethercore-rv32-mmio";
char kMtimecmpMmioName[] = "aethercore-rv32-mtimecmp";
char kMtimeMmioName[] = "aethercore-rv32-mtime";

constexpr std::uint32_t kSystemOpcode = 0x73U;
constexpr std::uint32_t kLoadOpcode = 0x03U;
constexpr std::uint32_t kStoreOpcode = 0x23U;
constexpr std::uint32_t kEcall = 0x00000073U;
constexpr std::uint32_t kEbreak = 0x00100073U;
constexpr std::uint32_t kMret = 0x30200073U;

constexpr std::uint32_t kInstructionAccessFault = 1U;
constexpr std::uint32_t kIllegalInstruction = 2U;
constexpr std::uint32_t kBreakpoint = 3U;
constexpr std::uint32_t kLoadAccessFault = 5U;
constexpr std::uint32_t kStoreAccessFault = 7U;
constexpr std::uint32_t kEnvironmentCallFromM = 11U;
constexpr std::uint32_t kMachineTimerInterruptCause = 0x80000007U;

constexpr std::uint32_t kMstatus = 0x300U;
constexpr std::uint32_t kMisa = 0x301U;
constexpr std::uint32_t kMie = 0x304U;
constexpr std::uint32_t kMtvec = 0x305U;
constexpr std::uint32_t kMscratch = 0x340U;
constexpr std::uint32_t kMepc = 0x341U;
constexpr std::uint32_t kMcause = 0x342U;
constexpr std::uint32_t kMtval = 0x343U;
constexpr std::uint32_t kMip = 0x344U;
constexpr std::uint32_t kRv32ImMisa = 0x40001100U;
constexpr std::uint32_t kMstatusMie = 1U << 3;
constexpr std::uint32_t kMstatusMpie = 1U << 7;
constexpr std::uint32_t kMstatusMppMachine = 3U << 11;
constexpr std::uint32_t kMstatusTrapMask =
    kMstatusMie | kMstatusMpie | kMstatusMppMachine;
constexpr std::uint32_t kMtie = 1U << 7;
constexpr std::uint32_t kMtip = 1U << 7;

struct NemuState32 {
  std::uint32_t gpr[32]{};
  std::uint32_t pc = 0;
};

static_assert(sizeof(NemuState32) == 132);
static_assert(offsetof(NemuState32, pc) == 32 * sizeof(std::uint32_t));

struct MachineState32 {
  std::uint32_t mstatus = 0;
  std::uint32_t mie = 0;
  std::uint32_t mip = 0;
  std::uint32_t mtvec = 0;
  std::uint32_t mscratch = 0;
  std::uint32_t mepc = 0;
  std::uint32_t mcause = 0;
  std::uint32_t mtval = 0;
};

std::vector<std::uint8_t> readImage(const std::string& path) {
  std::ifstream input(path, std::ios::binary | std::ios::ate);
  if (!input) throw std::runtime_error("RV32 timer DiffTest cannot open image: " + path);
  const auto size = input.tellg();
  if (size < 0) throw std::runtime_error("RV32 timer DiffTest cannot determine image size: " + path);
  std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
  input.seekg(0);
  if (!bytes.empty()) input.read(reinterpret_cast<char*>(bytes.data()), size);
  if (!input && !bytes.empty()) {
    throw std::runtime_error("RV32 timer DiffTest cannot read image: " + path);
  }
  return bytes;
}

template <typename Function>
Function loadSymbol(void* handle, const char* name) {
  dlerror();
  void* symbol = dlsym(handle, name);
  if (const char* error = dlerror()) {
    throw std::runtime_error(std::string("RV32 timer DiffTest missing NEMU symbol ") + name +
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

std::int32_t signExtend12(std::uint32_t value) {
  return static_cast<std::int32_t>(value << 20) >> 20;
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
      throw std::runtime_error("RV32 timer DiffTest image is larger than configured reference RAM");
    }
    if (ramSize != 64ULL * 1024ULL * 1024ULL) {
      throw std::runtime_error("RV32 timer DiffTest expects the frozen 64 MiB NEMU reference RAM");
    }

    handle_ = dlopen(sharedObject.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle_) {
      throw std::runtime_error(std::string("RV32 timer DiffTest cannot load NEMU shared object: ") +
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
      platformMmioSpace_ = allocateMmio(kPlatformMmioName, kPlatformMmioBase);
      mtimecmpMmioSpace_ = allocateMmio(kMtimecmpMmioName, kMtimecmpBase);
      mtimeMmioSpace_ = allocateMmio(kMtimeMmioName, kMtimePageBase);

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
    const auto commitPc = checkedAddress(commit.pc, "commit PC");
    NemuState32 before{};
    regcpy_(&before, kToDut);
    comparePc(before.pc, commitPc, "before reference execution");
    compareRegisters(before, "before reference execution");

    NemuState32 after{};
    bool zicsrStep = false;
    bool trapStep = false;
    bool mretStep = false;
    bool timerInterruptStep = false;

    if (commit.exception) {
      const auto cause = checkedAddress(commit.exceptionCause, "exception cause");
      if (cause == kMachineTimerInterruptCause) {
        validateTimerInterrupt(commit);
        after = executeTimerInterrupt(before, commit);
        regcpy_(&after, kToRef);
        ++timerInterruptShadowSteps_;
        timerInterruptStep = true;
      } else {
        validateTrap(before, commit);
        after = executeTrap(before, commit);
        regcpy_(&after, kToRef);
        ++trapShadowSteps_;
        trapStep = true;
      }
    } else {
      const std::uint32_t imageInst = instructionAt(commitPc);
      if (imageInst != commit.inst) {
        fail("DUT instruction " + hex32(commit.inst) + " differs from image instruction " +
             hex32(imageInst) + " at pc=" + hex32(commitPc));
      }

      mretStep = commit.inst == kMret;
      zicsrStep = isZicsrInstruction(commit.inst);
      if (mretStep) {
        after = executeMret(before, commit);
        regcpy_(&after, kToRef);
        ++mretShadowSteps_;
      } else if (zicsrStep) {
        after = executeZicsr(before, commit.inst);
        regcpy_(&after, kToRef);
        ++zicsrShadowSteps_;
      } else {
        exec_(1);
        regcpy_(&after, kToDut);
      }
    }

    if (commit.rdWrite && commit.rd != 0) {
      dutRegs_[commit.rd] = static_cast<std::uint32_t>(commit.rdData);
    }
    dutRegs_[0] = 0;

    if (injectMismatchAt_ && checked_ == *injectMismatchAt_) dutRegs_[31] ^= 1U;

    compareRegisters(after, "after reference execution");
    if (commit.memValid && commit.memWrite) {
      compareStore(commit);
      observePlatformStore(commit);
    }

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
    if (zicsrStep) line << " reference=zicsr-shadow";
    if (mretStep) line << " reference=mret-shadow";
    if (trapStep) {
      line << " reference=trap-shadow cause="
           << hex32(checkedAddress(commit.exceptionCause, "exception cause"))
           << " value=" << hex32(checkedAddress(commit.exceptionValue, "exception value"));
    }
    if (timerInterruptStep) {
      line << " reference=timer-interrupt-shadow cause="
           << hex32(kMachineTimerInterruptCause);
    }
    line << " next=" << hex32(after.pc);
    trace_.push_back(line.str());
    if (trace_.size() > kTraceDepth) trace_.pop_front();
    ++checked_;
  }

  std::uint64_t checkedCommits() const { return checked_; }
  std::uint64_t zicsrShadowSteps() const { return zicsrShadowSteps_; }
  std::uint64_t trapShadowSteps() const { return trapShadowSteps_; }
  std::uint64_t mretShadowSteps() const { return mretShadowSteps_; }
  std::uint64_t timerInterruptShadowSteps() const { return timerInterruptShadowSteps_; }

 private:
  static std::uint32_t checkedAddress(std::uint64_t value, const char* label) {
    if (value > std::numeric_limits<std::uint32_t>::max()) {
      throw std::runtime_error(std::string("RV32 timer DiffTest ") + label +
                               " exceeds 32 bits");
    }
    return static_cast<std::uint32_t>(value);
  }

  std::uint8_t* allocateMmio(char* name, std::uint32_t base) {
    auto* space = newSpace_(static_cast<int>(kMmioSize));
    if (space == nullptr) {
      throw std::runtime_error("RV32 timer DiffTest failed to allocate MMIO space");
    }
    std::memset(space, 0, kMmioSize);
    addMmioMap_(name, base, space, static_cast<int>(kMmioSize), nullptr);
    return space;
  }

  std::uint32_t readCsr(std::uint32_t address) const {
    switch (address) {
      case kMstatus: return machine_.mstatus;
      case kMisa: return kRv32ImMisa;
      case kMie: return machine_.mie;
      case kMtvec: return machine_.mtvec;
      case kMscratch: return machine_.mscratch;
      case kMepc: return machine_.mepc;
      case kMcause: return machine_.mcause;
      case kMtval: return machine_.mtval;
      case kMip: return machine_.mip;
      default: fail("Zicsr shadow read of unimplemented CSR " + hex32(address));
    }
  }

  bool csrWritable(std::uint32_t address) const {
    switch (address) {
      case kMstatus:
      case kMie:
      case kMtvec:
      case kMscratch:
      case kMepc:
      case kMcause:
      case kMtval:
        return true;
      case kMisa:
      case kMip:
        return false;
      default:
        fail("Zicsr shadow legality query for unimplemented CSR " + hex32(address));
    }
  }

  void writeCsr(std::uint32_t address, std::uint32_t value) {
    switch (address) {
      case kMstatus:
        machine_.mstatus = (value & (kMstatusMie | kMstatusMpie)) | kMstatusMppMachine;
        return;
      case kMie:
        machine_.mie = value & kMtie;
        return;
      case kMtvec:
        machine_.mtvec = value & ~std::uint32_t{3};
        return;
      case kMscratch:
        machine_.mscratch = value;
        return;
      case kMepc:
        machine_.mepc = value & ~std::uint32_t{3};
        return;
      case kMcause:
        machine_.mcause = value;
        return;
      case kMtval:
        machine_.mtval = value;
        return;
      case kMisa:
        fail("Zicsr shadow attempted to write read-only misa");
      case kMip:
        fail("Zicsr shadow attempted to write read-only mip.MTIP");
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
      fail("Zicsr shadow received a normal event that writes read-only CSR " +
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

  NemuState32 executeMret(const NemuState32& before, const DifftestCommit& commit) {
    if (commit.inst != kMret || commit.rdWrite || commit.memValid) {
      fail("MRET shadow received an invalid architectural event");
    }

    machine_.mstatus =
        (machine_.mstatus & ~kMstatusTrapMask) |
        ((machine_.mstatus & kMstatusMpie) ? kMstatusMie : 0U) |
        kMstatusMpie |
        kMstatusMppMachine;

    NemuState32 after = before;
    after.pc = machine_.mepc;
    after.gpr[0] = 0;
    return after;
  }

  void validateTimerInterrupt(const DifftestCommit& commit) const {
    if (commit.inst != 0 || commit.rdWrite || commit.memValid ||
        checkedAddress(commit.exceptionValue, "interrupt value") != 0) {
      fail("machine timer interrupt exposed an instruction or architectural side effect");
    }
    if ((machine_.mstatus & kMstatusMie) == 0 || (machine_.mie & kMtie) == 0) {
      fail("machine timer interrupt arrived while MIE or MTIE was disabled");
    }
  }

  NemuState32 executeTimerInterrupt(const NemuState32& before,
                                    const DifftestCommit& commit) {
    const auto pc = checkedAddress(commit.pc, "interrupt resume PC");
    machine_.mip |= kMtip;
    machine_.mstatus =
        (machine_.mstatus & ~kMstatusTrapMask) |
        ((machine_.mstatus & kMstatusMie) ? kMstatusMpie : 0U) |
        kMstatusMppMachine;
    machine_.mepc = pc & ~std::uint32_t{3};
    machine_.mcause = kMachineTimerInterruptCause;
    machine_.mtval = 0;

    NemuState32 after = before;
    after.pc = machine_.mtvec;
    after.gpr[0] = 0;
    return after;
  }

  std::uint32_t explicitMemoryAddress(const NemuState32& before,
                                      std::uint32_t instruction) const {
    const std::uint32_t opcode = instruction & 0x7fU;
    const std::uint32_t rs1 = (instruction >> 15) & 0x1fU;
    std::uint32_t encodedImmediate = 0;
    if (opcode == kLoadOpcode) {
      encodedImmediate = instruction >> 20;
    } else if (opcode == kStoreOpcode) {
      encodedImmediate = ((instruction >> 25) << 5) | ((instruction >> 7) & 0x1fU);
    } else {
      fail("trap shadow expected an explicit load/store instruction at pc=" +
           hex32(before.pc));
    }
    return before.gpr[rs1] + static_cast<std::uint32_t>(signExtend12(encodedImmediate));
  }

  void validateTrap(const NemuState32& before, const DifftestCommit& commit) const {
    const auto cause = checkedAddress(commit.exceptionCause, "exception cause");
    const auto value = checkedAddress(commit.exceptionValue, "exception value");
    const auto pc = checkedAddress(commit.pc, "exception PC");

    if (commit.rdWrite || commit.memValid) {
      fail("trap event exposed a register or memory side effect");
    }

    switch (cause) {
      case kInstructionAccessFault:
        if (value != pc) {
          fail("instruction access fault mtval=" + hex32(value) +
               " expected faulting pc=" + hex32(pc));
        }
        return;

      case kIllegalInstruction: {
        const auto instruction = instructionAt(pc);
        if (instruction != commit.inst || value != commit.inst) {
          fail("illegal-instruction trap metadata disagrees with the image");
        }
        return;
      }

      case kBreakpoint:
        if (commit.inst != kEbreak || instructionAt(pc) != kEbreak || value != pc) {
          fail("breakpoint trap metadata is inconsistent");
        }
        return;

      case kLoadAccessFault:
        if ((commit.inst & 0x7fU) != kLoadOpcode || instructionAt(pc) != commit.inst ||
            value != explicitMemoryAddress(before, commit.inst)) {
          fail("load access-fault trap metadata is inconsistent");
        }
        return;

      case kStoreAccessFault:
        if ((commit.inst & 0x7fU) != kStoreOpcode || instructionAt(pc) != commit.inst ||
            value != explicitMemoryAddress(before, commit.inst)) {
          fail("store access-fault trap metadata is inconsistent");
        }
        return;

      case kEnvironmentCallFromM:
        if (commit.inst != kEcall || instructionAt(pc) != kEcall || value != 0) {
          fail("M-mode ECALL trap metadata is inconsistent");
        }
        return;

      default:
        fail("trap shadow received unsupported synchronous cause " + hex32(cause));
    }
  }

  NemuState32 executeTrap(const NemuState32& before, const DifftestCommit& commit) {
    const auto cause = checkedAddress(commit.exceptionCause, "exception cause");
    const auto value = checkedAddress(commit.exceptionValue, "exception value");
    const auto pc = checkedAddress(commit.pc, "exception PC");

    machine_.mstatus =
        (machine_.mstatus & ~kMstatusTrapMask) |
        ((machine_.mstatus & kMstatusMie) ? kMstatusMpie : 0U) |
        kMstatusMppMachine;
    machine_.mepc = pc & ~std::uint32_t{3};
    machine_.mcause = cause;
    machine_.mtval = value;

    NemuState32 after = before;
    after.pc = machine_.mtvec;
    after.gpr[0] = 0;
    return after;
  }

  std::uint32_t instructionAt(std::uint32_t pc) const {
    if (pc < resetPc_ || static_cast<std::uint64_t>(pc - resetPc_) + 4 > image_.size()) {
      fail("DUT event outside the loaded image at pc=" + hex32(pc));
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

  std::uint8_t* mappedMmioPointer(std::uint32_t address, std::size_t size) const {
    const auto inRange = [address, size](std::uint32_t base) {
      return address >= base &&
             static_cast<std::uint64_t>(address - base) + size <= kMmioSize;
    };
    if (inRange(kPlatformMmioBase)) return platformMmioSpace_ + (address - kPlatformMmioBase);
    if (inRange(kMtimecmpBase)) return mtimecmpMmioSpace_ + (address - kMtimecmpBase);
    if (inRange(kMtimePageBase)) return mtimeMmioSpace_ + (address - kMtimePageBase);
    return nullptr;
  }

  void compareStore(const DifftestCommit& commit) const {
    const auto address = checkedAddress(commit.memAddr, "store address");
    std::array<std::uint8_t, 4> referenceBytes{};

    if (auto* mapped = mappedMmioPointer(address, referenceBytes.size())) {
      std::memcpy(referenceBytes.data(), mapped, referenceBytes.size());
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

  void observePlatformStore(const DifftestCommit& commit) {
    const auto address = checkedAddress(commit.memAddr, "store address");
    if (address >= kMtimecmpBase && address < kMtimecmpBase + 8) {
      machine_.mip &= ~kMtip;
    }
  }

  [[noreturn]] void fail(const std::string& reason) const {
    std::ostringstream out;
    out << "RV32 timer DiffTest mismatch after " << checked_ << " matched events: " << reason;
    if (!trace_.empty()) {
      out << "\nRecent matched events:";
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
  std::uint8_t* platformMmioSpace_ = nullptr;
  std::uint8_t* mtimecmpMmioSpace_ = nullptr;
  std::uint8_t* mtimeMmioSpace_ = nullptr;

  std::uint32_t resetPc_ = 0;
  std::vector<std::uint8_t> image_;
  std::array<std::uint32_t, 32> dutRegs_{};
  MachineState32 machine_{};
  std::deque<std::string> trace_;
  std::optional<std::uint64_t> injectMismatchAt_;
  std::uint64_t checked_ = 0;
  std::uint64_t zicsrShadowSteps_ = 0;
  std::uint64_t trapShadowSteps_ = 0;
  std::uint64_t mretShadowSteps_ = 0;
  std::uint64_t timerInterruptShadowSteps_ = 0;
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
std::uint64_t NemuDifftest::trapShadowSteps() const { return impl_->trapShadowSteps(); }
std::uint64_t NemuDifftest::mretShadowSteps() const { return impl_->mretShadowSteps(); }
std::uint64_t NemuDifftest::timerInterruptShadowSteps() const {
  return impl_->timerInterruptShadowSteps();
}
