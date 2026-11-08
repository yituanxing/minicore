/* Experimental 256-retirement QEMU -> actual Product G5 subset DiffTest.
 *
 * QEMU DDR + 256 GDB steps come from one capture.  Integer PC/rawInst/XPR
 * compare after every REAL RTL retirement.  End RAM comparison uses the
 * precise ROB-retirement memory trace, not younger speculative bus writes.
 * FPR, all CSRs, timer, platform device/IRQ state are NOT synchronized.
 */

#define AETHERCORE_QEMU_RAM_TRACE_EMBED
#include "product_c1_g5_qemu_ram_first_retire_main.cpp"
#include "paired_qemu_trace.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace {

std::size_t retiredStoreWidth(std::uint64_t mask) {
  if (mask == 0 || mask > 0xff || (mask & (mask + 1)) != 0)
    throw std::runtime_error("unsupported committed store byte mask");
  std::size_t bytes = 0;
  for (; mask != 0; mask >>= 1) ++bytes;
  if (bytes != 1 && bytes != 2 && bytes != 4 && bytes != 8)
    throw std::runtime_error("unsupported committed store width");
  return bytes;
}

int paired256Run(int argc, char** argv) {
  if (argc != 3)
    throw std::runtime_error("usage: G5_QEMU_PAIRED256 RAM256M CYCLE_BOUND");
  const auto cycleBound = boundedCycles(argv[2]);
  if (std::filesystem::file_size(argv[1]) != aethercore::l32sim::kRamSize)
    throw std::runtime_error("QEMU paired256 RAM extent must be exactly 256MiB");
  if (kPairedSteps.size() != 256)
    throw std::runtime_error("QEMU paired256 header must contain 256 entries");

  Memory original;
  original.loadAtBase(argv[1]);
  const auto base = original.snapshot();
  const auto originalFingerprint = original.fingerprint();
  Memory live;
  live.restore(base);
  Memory committed;
  committed.restore(base);
  auto qemuEnd = base;
  for (const auto& delta : kPairedFinalDdrDelta) {
    if (!original.contains(delta.pa, 1))
      throw std::runtime_error("QEMU 256 final-DDR patch out of range");
    qemuEnd.bytes[static_cast<std::size_t>(delta.pa - aethercore::l32sim::kRamBase)] =
        delta.value;
  }
  if (original.readInstruction(kPhysicalPc, 2) != kFirstOpcode ||
      original.readData(kRootPteAddr, 8) != kRootPte ||
      original.readData(kLeafPteAddr, 8) != kLeafPte ||
      kPairedSteps[0].pc != kGuestPc ||
      kPairedSteps[0].raw != kFirstOpcode ||
      kPairedInitialGprs[2] != kSpBefore)
    throw std::runtime_error("same-capture QEMU Sv39 PC/PTE/XPR seed mismatch");

  VerilatedContext context;
  Product top(&context);
  top.reset = 1;
  top.clock = 0;
  seedInputs(top);
  PageWalk firstPageWalk;
  for (unsigned i = 0; i < 4; ++i) step(top, context, live, firstPageWalk);
  top.reset = 0;
  top.io_warmSeed_load = 1;
  top.io_warmSeed_csr_valid = 1;
  step(top, context, live, firstPageWalk);
  top.io_warmSeed_load = 0;
  top.io_warmSeed_csr_valid = 0;
  for (unsigned i = 1; i < 32; ++i) {
    top.io_warmSeed_gpr_valid = 1;
    top.io_warmSeed_gpr_bits_index = i;
    top.io_warmSeed_gpr_bits_data = kPairedInitialGprs[i];
    step(top, context, live, firstPageWalk);
  }
  top.io_warmSeed_gpr_valid = 0;
  for (unsigned i = 0; i < 2; ++i) {
    step(top, context, live, firstPageWalk);
    requireQuietDevices(top);
    if (top.io_commit_valid || top.io_imemValid || top.io_memValid ||
        top.io_ptwValid || top.io_mtime != 0)
      throw std::runtime_error("G5 advanced while 256 XPR/PMP/CSR seed held");
  }
  if (live.fingerprint() != originalFingerprint)
    throw std::runtime_error("source DDR modified before guest release");
  top.io_warmSeed_hold = 0;

  std::array<std::uint64_t, 32> architecturalXpr = kPairedInitialGprs;
  aethercore::l32sim::TransportRuntimeState transport;
  std::size_t retired = 0;
  std::size_t committedStores = 0;
  for (std::uint64_t cycle = 0; cycle < cycleBound; ++cycle) {
    // The initial no-write transport exposes the existing immutable first
    // Sv39 walk probe; later cycles require the qualified mutable transport.
    if (retired == 0 && cycle == 0)
      step(top, context, live, firstPageWalk);
    else
      aethercore::l32sim::step(top, context, live, transport, false, 0);

    if (top.io_uartValid || top.io_exitValid || top.io_timerInterrupt ||
        top.io_supervisorExternalInterrupt || top.io_uartInterrupt ||
        top.io_uartRxInterrupt || top.io_commit_interrupt)
      throw std::runtime_error("unsynchronized device/interrupt during integer-only window");
    if (!top.io_commit_valid) continue;
    if (retired >= kPairedSteps.size())
      throw std::runtime_error("RTL retirement exceeds source QEMU oracle");
    const auto& qemu = kPairedSteps[retired];
    if (top.io_commit_exception || top.io_commit_pc != qemu.pc ||
        top.io_commit_rawInst != qemu.raw ||
        top.io_commit_instBytes != qemu.bytes) {
      std::cerr << "G5_QEMU_PAIRED256_FIRST_PC_OR_INST_DIFFERENCE step=" << (retired + 1)
                << " qemu_pc=0x" << std::hex << qemu.pc
                << " rtl_pc=0x" << top.io_commit_pc
                << " qemu_inst=0x" << qemu.raw
                << " rtl_inst=0x" << top.io_commit_rawInst << std::dec << '\n';
      return 3;
    }
    if (top.io_commit_rdWrite) {
      const auto rd = static_cast<unsigned>(top.io_commit_rd);
      if (rd > 31) throw std::runtime_error("G5 invalid destination XPR");
      if (rd != 0) architecturalXpr[rd] = top.io_commit_rdData;
    }
    for (unsigned i = 0; i < 32; ++i) {
      if (architecturalXpr[i] != qemu.gprs[i]) {
        std::cerr << "G5_QEMU_PAIRED256_FIRST_XPR_DIFFERENCE step=" << (retired + 1)
                  << " x=" << i << " pc=0x" << std::hex << qemu.pc
                  << " expected=0x" << qemu.gprs[i]
                  << " actual=0x" << architecturalXpr[i] << std::dec << '\n';
        return 3;
      }
    }

    // Store response may be accepted before ROB retirement in an OoO core.
    // Track only the precise head-owned memory trace in a separate DDR view.
    if (top.io_commit_memWrite) {
      if (!top.io_commit_memValid)
        throw std::runtime_error("committed store lacks valid memory trace");
      const auto pa = static_cast<std::uint64_t>(top.io_commit_memAddr);
      const auto mask = static_cast<std::uint64_t>(top.io_commit_memWmask);
      const auto width = retiredStoreWidth(mask);
      if (!committed.contains(pa, width))
        throw std::runtime_error("committed store outside 256MiB paired RAM");
      committed.writeMasked(pa, top.io_commit_memWdata, mask, width);
      ++committedStores;
    }

    std::cout << "G5_QEMU_PAIRED256_INTEGER_RETIRE_MATCH n=" << (retired + 1)
              << " pc=0x" << std::hex << qemu.pc << " raw=0x" << qemu.raw
              << std::dec << " bytes=" << static_cast<unsigned>(qemu.bytes)
              << " cycle=" << cycle << '\n';
    ++retired;
    if (retired != kPairedSteps.size()) continue;

    const auto committedEnd = committed.snapshot();
    if (committedEnd.bytes != qemuEnd.bytes) {
      const auto mismatch = std::mismatch(committedEnd.bytes.begin(),
                                         committedEnd.bytes.end(), qemuEnd.bytes.begin());
      const auto offset = static_cast<std::size_t>(
          std::distance(committedEnd.bytes.begin(), mismatch.first));
      std::cerr << "G5_QEMU_PAIRED256_COMMITTED_DDR_DIFFERENCE first_pa=0x"
                << std::hex << (aethercore::l32sim::kRamBase + offset)
                << " qemu=0x" << static_cast<unsigned>(qemuEnd.bytes[offset])
                << " rtl=0x" << static_cast<unsigned>(committedEnd.bytes[offset])
                << std::dec << '\n';
      return 3;
    }
    const auto transportEnd = live.snapshot();
    const auto advancedBytes = static_cast<std::size_t>(
        std::mismatch(transportEnd.bytes.begin(), transportEnd.bytes.end(),
                      qemuEnd.bytes.begin()).first - transportEnd.bytes.begin());
    const bool liveMatches = advancedBytes == transportEnd.bytes.size();
    if (original.fingerprint() != originalFingerprint)
      throw std::runtime_error("immutable QEMU source DDR changed");

    std::cout << "PRODUCT_G5_SAME_QEMU_RAM_256_INTEGER_RETIRE_AND_COMMITTED_DDR_MATCH_PARTIAL_DIFFTEST"
              << " retirements=" << retired << " cycles_after_release=" << cycle
              << " committed_stores=" << committedStores
              << " commit_shadow_ddr_match=1"
              << " live_transport_ddr_match=" << (liveMatches ? 1 : 0)
              << " live_transport_first_diff_pa=0x" << std::hex
              << (liveMatches ? 0 : aethercore::l32sim::kRamBase + advancedBytes)
              << std::dec
              << " integer_xpr_after_each_retire=1"
              << " csr_fpr_interrupt_device_not_diffed=1"
              << " not_full_qemu_handoff=1 not_linux_boot=1 not_sd1=1\n";
    return 0;
  }
  std::cerr << "G5_QEMU_PAIRED256_STOP_CYCLE_BOUND retirements=" << retired
            << " expected=256 cycles=" << cycleBound << '\n';
  return 3;
}
} // namespace

int main(int argc, char** argv) {
  try { return paired256Run(argc, argv); }
  catch (const std::exception& err) {
    std::cerr << "PRODUCT_G5_QEMU_PAIRED256_REJECTED: " << err.what() << '\n';
    return 2;
  }
}
