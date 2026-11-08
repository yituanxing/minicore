/* Real Product G5 first-retirement check against sealed QEMU Sv39 RAM.
 *
 * The DDR and first architectural PC/SP/SATP/mstatus/PMP values are copied
 * from the independently SHA-pinned QEMU H-off snapshot. This deliberately
 * stops after ONE genuine RTL retirement; remaining CSRs, FP state,
 * UART/PLIC/CLINT and LR/SC are NOT restored. Not a Linux handoff PASS.
 */

#include "VAetherCoreProductDebianWarmSeedRV64SimTop.h"
#include "l32_opensbi_runtime.h"
#include "qemu_captured_gprs.h"
#include "verilated.h"

#include <array>
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
constexpr std::uint64_t kSourceMstatus = 0x0000000a000000a2ULL;
constexpr std::uint64_t kSourceSatp = 0x80000000000856feULL;
constexpr std::uint64_t kSourcePmpcfg0 = 0x001f1b18181b1818ULL;
constexpr std::uint64_t kSourcePmpcfg2 = 0ULL;
constexpr std::uint64_t kRootPteAddr = 0x856feff0ULL;
constexpr std::uint64_t kLeafPteAddr = 0x8fffd020ULL;
constexpr std::uint64_t kRootPte = 0x23fff401ULL;
constexpr std::uint64_t kLeafPte = 0x202800ebULL;
constexpr std::uint16_t kFirstOpcode = 0x7179;
constexpr std::uint64_t kPmpAddrMask = (1ULL << 54) - 1ULL;
constexpr std::array<std::uint64_t,16> kSourcePmpaddr{{
  0x801000ULL, 0x802ffeULL, 0x40001ffULL, 0x20013fffULL,
  0x20007fffULL, 0x307ffffULL, 0xffffffffffffffffULL, 0ULL,
  0ULL, 0ULL, 0ULL, 0ULL, 0ULL, 0ULL, 0ULL, 0ULL
}};
static_assert(kSourcePmpcfg0 == 0x001f1b18181b1818ULL && kSourcePmpcfg2 == 0 &&
              ((kSourcePmpcfg0 >> 0) & 0xff) == 0x18 &&
              ((kSourcePmpcfg0 >> 16) & 0xff) == 0x1b &&
              ((kSourcePmpcfg0 >> 48) & 0xff) == 0x1f,
              "PMP config bytes must match the authenticated QEMU snapshot");

std::uint64_t boundedCycles(const char* arg) {
  const std::string val(arg);
  if (val.empty() || val[0] == '-') throw std::runtime_error("bad cycle bound");
  std::size_t end = 0;
  const auto n = std::stoull(val, &end, 0);
  if (end != val.size() || n == 0 || n > 1000000)
    throw std::runtime_error("real QEMU RAM first-retire bound must be 1..1000000");
  return n;
}

void seedInputs(Product& top) {
  top.io_warmSeed_hold = 1;
  top.io_warmSeed_load = 0;
  top.io_warmSeed_pc = kGuestPc;
  top.io_warmSeed_csr_valid = 0;
  top.io_warmSeed_csr_privilege = 1;
  top.io_warmSeed_csr_mstatus = kSourceMstatus;
  top.io_warmSeed_csr_satp = kSourceSatp;
  top.io_warmSeed_gpr_valid = 0;
  top.io_warmSeed_gpr_bits_index = 0;
  top.io_warmSeed_gpr_bits_data = 0;
  // Both PMP words and ALL sixteen encoded address registers originate
  // from the H-off QEMU GDB snapshot, not synthetic permissive PMP state.
#define SEED_PMP(N,CFG)                                         \
  top.io_warmSeed_csr_pmpConfig_##N = CFG;                      \
  top.io_warmSeed_csr_pmpAddress_##N = kSourcePmpaddr[N] & kPmpAddrMask
  SEED_PMP(0,0x18); SEED_PMP(1,0x18); SEED_PMP(2,0x1b);
  SEED_PMP(3,0x18); SEED_PMP(4,0x18); SEED_PMP(5,0x1b);
  SEED_PMP(6,0x1f); SEED_PMP(7,0x00); SEED_PMP(8,0x00);
  SEED_PMP(9,0x00); SEED_PMP(10,0x00); SEED_PMP(11,0x00);
  SEED_PMP(12,0x00); SEED_PMP(13,0x00); SEED_PMP(14,0x00);
  SEED_PMP(15,0x00);
#undef SEED_PMP
  top.io_rxValid = 0;
  top.io_rxByte = 0;
}

struct PageWalk {
  bool root = false;
  bool leaf = false;
  std::uint64_t count = 0;
  void observe(std::uint64_t pa, std::uint64_t word) {
    ++count;
    if (pa == kRootPteAddr && word == kRootPte) root = true;
    else if (pa == kLeafPteAddr && word == kLeafPte) leaf = true;
    else throw std::runtime_error("RTL PTW mismatches immutable real QEMU RAM");
  }
};

void drive(Product& top, const Memory& ram) {
  const bool iv = top.io_imemValid;
  const auto ia = static_cast<std::uint64_t>(top.io_imemAddr);
  const auto ib = static_cast<std::size_t>(top.io_imemBytes);
  const bool ifault = iv && ((ib != 2 && ib != 4) || !ram.contains(ia, ib));
  top.io_imemFault = ifault;
  top.io_imemInst = (iv && !ifault) ? ram.readInstruction(ia, ib) : 0;

  const bool mv = top.io_memValid;
  const auto ma = static_cast<std::uint64_t>(top.io_memAddr);
  const auto mb = mv ? aethercore::l32sim::dataBytesFromMemSize(
    static_cast<std::uint32_t>(top.io_memSize)) : std::size_t{1};
  const bool mfault = mv && !ram.contains(ma, mb);
  top.io_memReady = 1;
  top.io_memFault = mfault;
  if (mv && (top.io_memWrite || top.io_memAtomic))
    throw std::runtime_error("real QEMU RAM must not be mutated before first retirement");
  top.io_memRdata = (mv && !mfault) ? ram.readData(ma, mb) : 0;

  const bool pv = top.io_ptwValid;
  const auto pa = static_cast<std::uint64_t>(top.io_ptwAddr);
  const bool pfault = pv && !ram.contains(pa, 8);
  top.io_ptwReady = 1;
  top.io_ptwFault = pfault;
  top.io_ptwRdata = (pv && !pfault) ? ram.readData(pa, 8) : 0;
}

void step(Product& top, VerilatedContext& ctx, const Memory& ram,
          PageWalk& walk) {
  top.clock = 0;
  drive(top, ram);
  top.eval();
  drive(top, ram);
  top.eval();
  if (!top.reset && top.io_ptwValid) {
    if (top.io_ptwFault)
      throw std::runtime_error("real QEMU page-table walk went outside DDR");
    walk.observe(top.io_ptwAddr, top.io_ptwRdata);
  }
  top.clock = 1;
  top.eval();
  ctx.timeInc(1);
}

void requireQuietDevices(const Product& top) {
  if (top.io_uartValid || top.io_exitValid || top.io_timerInterrupt ||
      top.io_supervisorExternalInterrupt || top.io_uartInterrupt ||
      top.io_uartRxInterrupt || top.io_commit_interrupt)
    throw std::runtime_error("device or interrupt activity before first retire");
}

int run(int argc,char** argv) {
  if (argc != 3)
    throw std::runtime_error("usage: actual-g5-qemu-ram-first-retire RAM256M BOUND");
  if (std::filesystem::file_size(argv[1]) != aethercore::l32sim::kRamSize)
    throw std::runtime_error("QEMU source RAM length is not 256MiB");
  const auto bound = boundedCycles(argv[2]);
  Memory ram;
  ram.loadAtBase(argv[1]);
  const auto before = ram.fingerprint();
  if (ram.readInstruction(kPhysicalPc, 2) != kFirstOpcode ||
      ram.readData(kRootPteAddr, 8) != kRootPte ||
      ram.readData(kLeafPteAddr, 8) != kLeafPte)
    throw std::runtime_error("severe QEMU RAM / Sv39 source-identity mismatch");
  if (kCapturedGprs[0] != 0 || kCapturedGprs[2] != kSpBefore)
    throw std::runtime_error("signed QEMU full XPR table failed x0/x2 identity");

  VerilatedContext ctx;
  Product top(&ctx);
  top.reset = 1;
  top.clock = 0;
  seedInputs(top);
  PageWalk walk;
  for (unsigned i=0;i<4;i++) step(top,ctx,ram,walk);
  top.reset = 0;
  top.io_warmSeed_load = 1;
  top.io_warmSeed_csr_valid = 1;
  step(top,ctx,ram,walk);
  top.io_warmSeed_load = 0;
  top.io_warmSeed_csr_valid = 0;
  // The reset identity RAT / PRF supports direct architectural preload for
  // every integer register, one physical write per held clock. Unlike a
  // guest restorer these 31 writes consume no guest instruction or CSR state.
  for (unsigned i = 1; i < 32; ++i) {
    top.io_warmSeed_gpr_valid = 1;
    top.io_warmSeed_gpr_bits_index = i;
    top.io_warmSeed_gpr_bits_data = kCapturedGprs[i];
    step(top,ctx,ram,walk);
  }
  top.io_warmSeed_gpr_valid = 0;
  for (unsigned i=0;i<2;i++) {
    step(top,ctx,ram,walk);
    requireQuietDevices(top);
    if (top.io_commit_valid || top.io_imemValid || top.io_memValid || top.io_ptwValid)
      throw std::runtime_error("warmHold advanced QEMU guest before release");
    if (top.io_mtime != 0)
      throw std::runtime_error("timebase not frozen during QEMU RAM preload");
  }
  if (ram.fingerprint()!=before)
    throw std::runtime_error("QEMU RAM altered during direct seed");
  top.io_warmSeed_hold = 0;

  for (std::uint64_t cycle=0;cycle<bound;++cycle) {
    step(top,ctx,ram,walk);
    requireQuietDevices(top);
    if (!top.io_commit_valid) continue;
    if (top.io_commit_exception || top.io_commit_pc != kGuestPc ||
        top.io_commit_rawInst != kFirstOpcode || top.io_commit_instBytes != 2 ||
        !top.io_commit_rdWrite || top.io_commit_rd != 2 ||
        top.io_commit_rdData != kSpAfter || !walk.root || !walk.leaf)
      throw std::runtime_error("first real-QEMU-RAM G5 RTL retirement not equal source oracle");
    if (ram.fingerprint()!=before)
      throw std::runtime_error("real QEMU RAM changed before first retirement");
    std::cout << "PRODUCT_C1_G5_REAL_QEMU_RAM_FIRST_RETIRE_PARTIAL_ARCH_PASS"
              << " pc=0x" << std::hex << kGuestPc
              << " physical=0x" << kPhysicalPc
              << " inst=0x" << kFirstOpcode
              << " sp_after=0x" << kSpAfter << std::dec
              << " cycles_after_release=" << cycle
              << " ptw_requests=" << walk.count
              << " qemu_ram=1 qemu_all_32_xpr=1 qemu_mstatus_satp_pmp_sp=1"
              << " csr_device_fp_lrsc_incomplete=1 not_qemu_full_handoff=1 not_sd1=1\n";
    return 0;
  }
  throw std::runtime_error("no matching first retirement within strict cycle budget");
}
} // namespace

#ifndef AETHERCORE_QEMU_RAM_TRACE_EMBED
int main(int argc,char** argv) {
  try { return run(argc,argv); }
  catch (const std::exception& e) {
    std::cerr << "PRODUCT_C1_G5_REAL_QEMU_RAM_FIRST_RETIRE_REJECTED: " << e.what() << '\n';
    return 2;
  }
}
#endif
