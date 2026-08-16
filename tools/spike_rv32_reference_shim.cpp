#include "config.h"
#include "processor.h"
#include "simif.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <memory>
#include <vector>

namespace {
constexpr std::uint32_t kRamBase = 0x80000000U;
constexpr std::size_t kRamSize = 64U * 1024U * 1024U;

struct ReferenceState32 {
  std::uint32_t gpr[32];
  std::uint32_t pc;
};

static_assert(sizeof(ReferenceState32) == 132);
static_assert(offsetof(ReferenceState32, pc) == 32 * sizeof(std::uint32_t));

[[noreturn]] void fail(const char* message) {
  std::fprintf(stderr, "RV32 Spike reference error: %s\n", message);
  std::abort();
}

bool contains(reg_t base, std::size_t size, reg_t address, std::size_t length) {
  if (address < base) return false;
  const std::uint64_t offset = static_cast<std::uint64_t>(address - base);
  return offset <= size && length <= size - static_cast<std::size_t>(offset);
}

class BareSpikeSim final : public simif_t {
 public:
  BareSpikeSim() : ram_(kRamSize, 0) {}

  char* addr_to_mem(reg_t address) override {
    if (!contains(kRamBase, ram_.size(), address, 1)) return nullptr;
    return reinterpret_cast<char*>(ram_.data() + static_cast<std::size_t>(address - kRamBase));
  }

  bool mmio_load(reg_t address, std::size_t length, std::uint8_t* bytes) override {
    if (mmio_ == nullptr || !contains(mmioBase_, mmioSize_, address, length)) return false;
    std::memcpy(bytes, mmio_ + static_cast<std::size_t>(address - mmioBase_), length);
    return true;
  }

  bool mmio_store(reg_t address, std::size_t length, const std::uint8_t* bytes) override {
    if (mmio_ == nullptr || !contains(mmioBase_, mmioSize_, address, length)) return false;
    std::memcpy(mmio_ + static_cast<std::size_t>(address - mmioBase_), bytes, length);
    return true;
  }

  void proc_reset(unsigned) override {}
  const char* get_symbol(std::uint64_t) override { return nullptr; }

  void copy(reg_t address, void* buffer, std::size_t length, bool toReference) {
    if (contains(kRamBase, ram_.size(), address, length)) {
      auto* host = ram_.data() + static_cast<std::size_t>(address - kRamBase);
      if (toReference) std::memcpy(host, buffer, length);
      else std::memcpy(buffer, host, length);
      return;
    }
    if (mmio_ != nullptr && contains(mmioBase_, mmioSize_, address, length)) {
      auto* host = mmio_ + static_cast<std::size_t>(address - mmioBase_);
      if (toReference) std::memcpy(host, buffer, length);
      else std::memcpy(buffer, host, length);
      return;
    }
    fail("difftest_memcpy address is outside reference RAM/MMIO");
  }

  void addMmio(reg_t base, std::uint8_t* space, std::size_t size) {
    if (space == nullptr || size == 0) fail("invalid passive MMIO mapping");
    mmioBase_ = base;
    mmio_ = space;
    mmioSize_ = size;
  }

 private:
  std::vector<std::uint8_t> ram_;
  reg_t mmioBase_ = 0;
  std::uint8_t* mmio_ = nullptr;
  std::size_t mmioSize_ = 0;
};

std::unique_ptr<BareSpikeSim> gSim;
std::unique_ptr<processor_t> gProcessor;
std::vector<void*> gSpaces;

void requireReady() {
  if (!gSim || !gProcessor) fail("reference used before difftest_init");
}
}  // namespace

extern "C" void difftest_init() {
  gProcessor.reset();
  gSim = std::make_unique<BareSpikeSim>();
  gProcessor = std::make_unique<processor_t>(
      "RV32IMC_Zicsr", "M", DEFAULT_VARCH, gSim.get(), 0, false, stderr, std::cerr);
  gProcessor->set_pmp_num(0);
}

extern "C" void difftest_memcpy(std::uint32_t address, void* buffer,
                                  std::size_t length, bool direction) {
  requireReady();
  gSim->copy(address, buffer, length, direction);
}

extern "C" void difftest_regcpy(void* rawState, bool direction) {
  requireReady();
  auto* state = static_cast<ReferenceState32*>(rawState);
  auto* spike = gProcessor->get_state();
  if (direction) {
    for (std::size_t index = 0; index < 32; ++index) {
      spike->XPR.write(index, state->gpr[index]);
    }
    spike->pc = state->pc;
  } else {
    for (std::size_t index = 0; index < 32; ++index) {
      state->gpr[index] = static_cast<std::uint32_t>(spike->XPR[index]);
    }
    state->pc = static_cast<std::uint32_t>(spike->pc);
  }
}

extern "C" void difftest_exec(std::uint64_t instructions) {
  requireReady();
  gProcessor->step(static_cast<std::size_t>(instructions));
}

extern "C" std::uint8_t* new_space(int size) {
  if (size <= 0) fail("new_space requires a positive size");
  void* space = std::calloc(static_cast<std::size_t>(size), 1);
  if (space == nullptr) fail("new_space allocation failed");
  gSpaces.push_back(space);
  return static_cast<std::uint8_t*>(space);
}

using IoCallback = void (*)(std::uint32_t, int, bool);
extern "C" void add_mmio_map(char*, std::uint32_t base, std::uint8_t* space,
                              int size, IoCallback callback) {
  requireReady();
  if (callback != nullptr) fail("Spike reference only supports passive MMIO mappings");
  if (size <= 0) fail("add_mmio_map requires a positive size");
  gSim->addMmio(base, space, static_cast<std::size_t>(size));
}
