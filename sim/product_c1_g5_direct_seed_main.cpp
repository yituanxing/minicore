/* Isolated real Product G5 RTL direct architectural preload, without MRET.
 * Synthetic Sv39 page tables + one QEMU-observed opcode ONLY. Not Linux/QEMU
 * warm-resume, SD1, or whole architectural-state equivalence.
 *
 * Usage: VAetherCoreProductDebianWarmSeedRV64SimTop RAM256M.bin [max-cycles]
 */

#include "VAetherCoreProductDebianWarmSeedRV64SimTop.h"
#include "l32_opensbi_runtime.h"
#include "verilated.h"

#include <cstdint>
#include <filesystem>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
using Product = VAetherCoreProductDebianWarmSeedRV64SimTop;
using aethercore::l32sim::Memory;

constexpr std::uint64_t kGuestPc = 0xffffffff808c3490ULL;
constexpr std::uint64_t kPhysicalPc = 0x80ac3490ULL;
constexpr std::uint64_t kSpBefore = 0xffffffc80000bec0ULL;
constexpr std::uint64_t kSpAfter = 0xffffffc80000be90ULL;
constexpr std::uint64_t kRootPa = 0x80200000ULL;
constexpr std::uint64_t kL1Pa = 0x80201000ULL;
constexpr std::uint64_t kSatp = (8ULL << 60) | (kRootPa >> 12);
constexpr std::uint64_t kRootPteAddr = kRootPa + 510 * 8;
constexpr std::uint64_t kLeafPteAddr = kL1Pa + 4 * 8;
constexpr std::uint64_t kRootPte = ((kL1Pa >> 12) << 10) | 1;
constexpr std::uint64_t kLeafPte = ((0x80a00000ULL >> 12) << 10) | 0xcf;
constexpr std::uint32_t kFirstOpcode = 0x7179;
constexpr std::uint32_t kIllegalCsr = 0x300026f3;

std::uint64_t parseCycles(const char* text) {
  const std::string value(text);
  if (value.empty() || value[0] == '-') throw std::runtime_error("invalid max-cycles");
  std::size_t consumed = 0;
  const auto result = std::stoull(value, &consumed, 0);
  if (consumed != value.size() || result == 0 || result > 1000000)
    throw std::runtime_error("bounded max-cycles must be 1..1000000");
  return result;
}

void initializeWarmInputs(Product& top) {
  top.io_warmSeed_hold = 1;
  top.io_warmSeed_load = 0;
  top.io_warmSeed_pc = kGuestPc;
  top.io_warmSeed_csr_valid = 0;
  top.io_warmSeed_csr_privilege = 1;  // Supervisor, not MRET's privilege transition
  top.io_warmSeed_csr_mstatus = 0xa00000088ULL;
  top.io_warmSeed_csr_satp = kSatp;
  top.io_warmSeed_gpr_valid = 0;
  top.io_warmSeed_gpr_bits_index = 2;
  top.io_warmSeed_gpr_bits_data = kSpBefore;
#define INIT_PMP(N)                              \
  top.io_warmSeed_csr_pmpConfig_##N = 0;         \
  top.io_warmSeed_csr_pmpAddress_##N = 0
  INIT_PMP(0); INIT_PMP(1); INIT_PMP(2); INIT_PMP(3);
  INIT_PMP(4); INIT_PMP(5); INIT_PMP(6); INIT_PMP(7);
  INIT_PMP(8); INIT_PMP(9); INIT_PMP(10); INIT_PMP(11);
  INIT_PMP(12); INIT_PMP(13); INIT_PMP(14); INIT_PMP(15);
#undef INIT_PMP
  top.io_warmSeed_csr_pmpConfig_0 = 0x1f;
  top.io_warmSeed_csr_pmpAddress_0 = 0x21ffffff;
  top.io_rxValid = 0;
  top.io_rxByte = 0;
}

struct WalkEvidence {
  bool root = false;
  bool leaf = false;
  std::uint64_t requests = 0;
  void observe(std::uint64_t addr, std::uint64_t result) {
    ++requests;
    if (addr == kRootPteAddr && result == kRootPte) root = true;
    else if (addr == kLeafPteAddr && result == kLeafPte) leaf = true;
    else throw std::runtime_error("actual G5 RTL page walk differs from source DDR");
  }
};

void drive(Product& top, const Memory& memory, WalkEvidence& walk) {
  const bool imemValid = top.io_imemValid;
  const auto imemAddr = static_cast<std::uint64_t>(top.io_imemAddr);
  const auto imemBytes = static_cast<std::size_t>(top.io_imemBytes);
  const bool imemFault = imemValid &&
    ((imemBytes != 2 && imemBytes != 4) || !memory.contains(imemAddr, imemBytes));
  top.io_imemFault = imemFault;
  top.io_imemInst = (!imemValid || imemFault) ? 0
    : memory.readInstruction(imemAddr, imemBytes);

  const bool memValid = top.io_memValid;
  const auto memAddr = static_cast<std::uint64_t>(top.io_memAddr);
  const auto memBytes = memValid ? aethercore::l32sim::dataBytesFromMemSize(
    static_cast<std::uint32_t>(top.io_memSize)) : std::size_t{1};
  const bool memFault = memValid && !memory.contains(memAddr, memBytes);
  top.io_memReady = 1;
  top.io_memFault = memFault;
  if (memValid && (top.io_memWrite || top.io_memAtomic))
    throw std::runtime_error("synthetic direct-seed case forbids all DDR stores/atomics");
  top.io_memRdata = (!memValid || memFault) ? 0 : memory.readData(memAddr, memBytes);

  const bool ptwValid = top.io_ptwValid;
  const auto ptwAddr = static_cast<std::uint64_t>(top.io_ptwAddr);
  const bool ptwFault = ptwValid && !memory.contains(ptwAddr, 8);
  top.io_ptwReady = 1;
  top.io_ptwFault = ptwFault;
  top.io_ptwRdata = (!ptwValid || ptwFault) ? 0 : memory.readData(ptwAddr, 8);
  // Count PTW handshakes in step(), not twice during combinational settling.
  (void)walk;
}

void step(Product& top, VerilatedContext& context, const Memory& memory,
          WalkEvidence& walk) {
  top.clock = 0;
  drive(top, memory, walk);
  top.eval();
  drive(top, memory, walk);
  top.eval();
  if (!top.reset && top.io_ptwValid) {
    if (top.io_ptwFault) throw std::runtime_error("RTL PTW accessed non-DDR");
    walk.observe(top.io_ptwAddr, top.io_ptwRdata);
  }
  if (!top.reset && top.io_memValid && (top.io_memWrite || top.io_memAtomic))
    throw std::runtime_error("RTL attempted source DDR mutation");
  top.clock = 1;
  top.eval();
  context.timeInc(1);
}

void ensureNoDeviceOrInterrupt(const Product& top) {
  if (top.io_uartValid || top.io_exitValid || top.io_timerInterrupt ||
      top.io_supervisorExternalInterrupt || top.io_uartInterrupt ||
      top.io_uartRxInterrupt || top.io_commit_interrupt)
    throw std::runtime_error("unrestored platform device or interrupt activity");
  if (top.io_commit_exception && !top.io_commit_valid)
    throw std::runtime_error("out-of-retirement exception sideband");
}

int run(int argc, char** argv) {
  if (argc != 3) throw std::runtime_error("usage: rtl-direct-seed RAM256M max-cycles");
  if (std::filesystem::file_size(argv[1]) != aethercore::l32sim::kRamSize)
    throw std::runtime_error("source DDR must be exactly 256 MiB");
  const auto maxCycles = parseCycles(argv[2]);
  Memory memory;
  memory.loadAtBase(argv[1]);
  const auto originalFingerprint = memory.fingerprint();
  if (memory.readInstruction(kPhysicalPc, 2) != kFirstOpcode ||
      memory.readInstruction(kPhysicalPc + 2, 4) != kIllegalCsr ||
      memory.readData(kRootPteAddr, 8) != kRootPte ||
      memory.readData(kLeafPteAddr, 8) != kLeafPte)
    throw std::runtime_error("first source bytes or Sv39 table mismatch");

  VerilatedContext context;
  Product top(&context);
  top.reset = 1;
  top.clock = 0;
  initializeWarmInputs(top);
  WalkEvidence walk;
  // Reset first; all architectural state is seeded only with reset deasserted
  // and warmHold asserted. This avoids RegInit overriding injected values.
  for (unsigned cycle = 0; cycle != 4; ++cycle) step(top, context, memory, walk);
  top.reset = 0;
  top.io_warmSeed_load = 1;
  top.io_warmSeed_csr_valid = 1;
  top.io_warmSeed_gpr_valid = 1;
  step(top, context, memory, walk);
  top.io_warmSeed_load = 0;
  top.io_warmSeed_csr_valid = 0;
  top.io_warmSeed_gpr_valid = 0;
  for (unsigned cycle = 0; cycle != 2; ++cycle) {
    step(top, context, memory, walk);
    ensureNoDeviceOrInterrupt(top);
    if (top.io_commit_valid || top.io_imemValid || top.io_memValid || top.io_ptwValid)
      throw std::runtime_error("warmHold allowed a guest request/retirement");
    if (top.io_mtime != 0)
      throw std::runtime_error("mtime must remain frozen during warmHold");
  }
  if (memory.fingerprint() != originalFingerprint)
    throw std::runtime_error("source RAM changed during architectural seed");
  std::cout << "PRODUCT_C1_G5_RTL_DIRECT_SEED_HOLD_DDR_UNCHANGED_NO_MRET"
            << " pc=0x" << std::hex << kGuestPc << " sp=0x" << kSpBefore
            << std::dec << " architecture_only=1\n";
  top.io_warmSeed_hold = 0;

  bool retiredFirst = false;
  for (std::uint64_t cycle = 0; cycle < maxCycles; ++cycle) {
    step(top, context, memory, walk);
    ensureNoDeviceOrInterrupt(top);
    if (!top.io_commit_valid) continue;
    const auto pc = static_cast<std::uint64_t>(top.io_commit_pc);
    const auto raw = static_cast<std::uint32_t>(top.io_commit_rawInst);
    if (!retiredFirst) {
      if (top.io_commit_exception || pc != kGuestPc || raw != kFirstOpcode ||
          top.io_commit_instBytes != 2 || !top.io_commit_rdWrite ||
          top.io_commit_rd != 2 || top.io_commit_rdData != kSpAfter ||
          !walk.root || !walk.leaf)
        throw std::runtime_error("direct-seeded first RVC PC/SP/width/PTW mismatch");
      retiredFirst = true;
      std::cout << "PRODUCT_C1_G5_RTL_DIRECT_SEED_FIRST_RVC_RETIRED"
                << " cycle=" << cycle << " pc=0x" << std::hex << pc
                << " raw=0x" << raw << " sp=0x" << kSpAfter << std::dec
                << " width=2 rd=x2\n";
      continue;
    }
    if (!top.io_commit_exception || pc != kGuestPc + 2 ||
        raw != kIllegalCsr || top.io_commit_inst != kIllegalCsr ||
        top.io_commit_exceptionCause != 2 ||
        top.io_commit_exceptionValue != kIllegalCsr)
      throw std::runtime_error("S-mode synthetic illegal CSR next-PC sentinel failed");
    if (memory.fingerprint() != originalFingerprint)
      throw std::runtime_error("source DDR mutated after release");
    std::cout << "PRODUCT_C1_G5_RTL_DIRECT_ARCH_SEED_RVC_PTW_SP_NEXTPC_PASS_NOT_QEMU_OR_SD1"
              << " cycles=" << cycle << " physical_first=0x" << std::hex
              << kPhysicalPc << std::dec << " ptw_requests=" << walk.requests
              << " no_mret=1 no_guest_ddr_writes=1 s_mode_csr_trap=1\n";
    return 0;
  }
  throw std::runtime_error("direct-seeded G5 RVC bounded fixture timed out");
}
}  // namespace

int main(int argc, char** argv) {
  try { return run(argc, argv); }
  catch (const std::exception& error) {
    std::cerr << "PRODUCT_C1_G5_DIRECT_SEED_HOST_REJECTED: " << error.what() << '\n';
    return 2;
  }
}
