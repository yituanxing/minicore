/* Paired same-RAM QEMU vs true Product G5 integer-retirement subset test.
 *
 * Exact QEMU 64-step oracle and 256MiB RAM originate from ONE SHA-sealed run.
 * This deliberately does not assert whole-system DiffTest: counters, FP,
 * CSRs and IRQ/device state are not restored from QEMU to Product G5.
 */

#define AETHERCORE_QEMU_RAM_TRACE_EMBED
#include "product_c1_g5_qemu_ram_first_retire_main.cpp"
#include "paired_qemu_trace.h"

#include <algorithm>
#include <vector>

namespace {
int pairedRun(int argc, char** argv) {
  if (argc != 3)
    throw std::runtime_error("usage: G5_QEMU_PAIRED64 RAM256M CYCLE_BOUND");
  const auto bound = boundedCycles(argv[2]);
  if (std::filesystem::file_size(argv[1]) != aethercore::l32sim::kRamSize)
    throw std::runtime_error("paired QEMU RAM must be 256MiB");
  Memory original;
  original.loadAtBase(argv[1]);
  Memory live;
  live.restore(original.snapshot());
  auto expected = original.snapshot();
  const auto initialFingerprint = original.fingerprint();
  if (original.readInstruction(kPhysicalPc, 2) != kFirstOpcode ||
      original.readData(kRootPteAddr, 8) != kRootPte ||
      original.readData(kLeafPteAddr, 8) != kLeafPte ||
      kPairedSteps[0].pc != kGuestPc ||
      kPairedSteps[0].raw != kFirstOpcode ||
      kPairedInitialGprs[2] != kSpBefore)
    throw std::runtime_error("paired QEMU source RAM/pagewalk/first oracle mismatch");
  for (const auto& diff : kPairedFinalDdrDelta) {
    if (!original.contains(diff.pa, 1))
      throw std::runtime_error("out-of-range paired QEMU DDR delta");
    expected.bytes[static_cast<std::size_t>(diff.pa - aethercore::l32sim::kRamBase)] = diff.value;
  }

  VerilatedContext context;
  Product top(&context);
  top.reset = 1;
  top.clock = 0;
  seedInputs(top);
  PageWalk firstWalk;
  for (unsigned i = 0; i < 4; ++i) step(top, context, live, firstWalk);
  top.reset = 0;
  top.io_warmSeed_load = 1;
  top.io_warmSeed_csr_valid = 1;
  step(top, context, live, firstWalk);
  top.io_warmSeed_load = 0;
  top.io_warmSeed_csr_valid = 0;
  for (unsigned i = 1; i < 32; ++i) {
    top.io_warmSeed_gpr_valid = 1;
    top.io_warmSeed_gpr_bits_index = i;
    top.io_warmSeed_gpr_bits_data = kPairedInitialGprs[i];
    step(top, context, live, firstWalk);
  }
  top.io_warmSeed_gpr_valid = 0;
  for (unsigned i = 0; i < 2; ++i) {
    step(top, context, live, firstWalk);
    requireQuietDevices(top);
    if (top.io_commit_valid || top.io_imemValid || top.io_memValid || top.io_ptwValid ||
        top.io_mtime != 0)
      throw std::runtime_error("G5 advanced during held same-RAM seed");
  }
  if (live.fingerprint() != initialFingerprint)
    throw std::runtime_error("G5 mutated paired QEMU RAM before release");
  top.io_warmSeed_hold = 0;

  std::array<std::uint64_t, 32> gprs = kPairedInitialGprs;
  aethercore::l32sim::TransportRuntimeState transport;
  std::size_t ordinal = 0;
  for (std::uint64_t cycle = 0; cycle < bound; ++cycle) {
    if (ordinal == 0)
      step(top, context, live, firstWalk);
    else
      aethercore::l32sim::step(top, context, live, transport, false, 0);
    if (top.io_uartValid || top.io_exitValid || top.io_timerInterrupt ||
        top.io_supervisorExternalInterrupt || top.io_uartInterrupt ||
        top.io_uartRxInterrupt || top.io_commit_interrupt)
      throw std::runtime_error("unrestored device/interrupt state before 64 retirement comparison");
    if (!top.io_commit_valid) continue;
    if (ordinal >= kPairedSteps.size())
      throw std::runtime_error("too many RTL retirements in paired oracle window");
    const auto& oracle = kPairedSteps[ordinal];
    if (top.io_commit_exception || top.io_commit_pc != oracle.pc ||
        top.io_commit_rawInst != oracle.raw ||
        top.io_commit_instBytes != oracle.bytes) {
      std::cerr << "G5_QEMU_PAIRED64_FIRST_PC_OR_INST_DIFFERENCE step=" << (ordinal + 1)
                << " qemu_pc=0x" << std::hex << oracle.pc
                << " rtl_pc=0x" << top.io_commit_pc
                << " qemu_inst=0x" << oracle.raw
                << " rtl_inst=0x" << top.io_commit_rawInst
                << std::dec << '\n';
      return 3;
    }
    if (top.io_commit_rdWrite) {
      const auto rd = static_cast<unsigned>(top.io_commit_rd);
      if (rd > 31) throw std::runtime_error("invalid RTL destination register");
      if (rd != 0) gprs[rd] = top.io_commit_rdData;
    }
    for (unsigned i = 0; i < 32; ++i) {
      if (gprs[i] != oracle.gprs[i]) {
        std::cerr << "G5_QEMU_PAIRED64_FIRST_XPR_DIFFERENCE step=" << (ordinal + 1)
                  << " x=" << i << " pc=0x" << std::hex << oracle.pc
                  << " expected=0x" << oracle.gprs[i]
                  << " actual=0x" << gprs[i] << std::dec << '\n';
        return 3;
      }
    }
    std::cout << "G5_QEMU_PAIRED64_INTEGER_RETIRE_MATCH"
              << " n=" << (ordinal + 1) << " pc=0x" << std::hex << oracle.pc
              << " raw=0x" << oracle.raw << std::dec
              << " bytes=" << static_cast<unsigned>(oracle.bytes)
              << " cycle=" << cycle << '\n';
    ++ordinal;
    if (ordinal != kPairedSteps.size()) continue;
    // The QEMU end-of-window DDR delta is an aggregate, not per-instruction;
    // compare all 256MiB bytes only after EXACTLY 64 RTL retirements.
    const auto actual = live.snapshot();
    if (actual.bytes != expected.bytes) {
      auto mismatch = std::mismatch(actual.bytes.begin(), actual.bytes.end(),
                                    expected.bytes.begin());
      const auto off = static_cast<std::size_t>(
          std::distance(actual.bytes.begin(), mismatch.first));
      std::cerr << "G5_QEMU_PAIRED64_FINAL_DDR_DIFFERENCE"
                << " first_pa=0x" << std::hex
                << (aethercore::l32sim::kRamBase + off)
                << " expected=0x" << static_cast<unsigned>(expected.bytes[off])
                << " rtl=0x" << static_cast<unsigned>(actual.bytes[off])
                << std::dec << '\n';
      return 3;
    }
    // Only the first guest cycle uses the pre-retirement PageWalk probe.
    // Subsequent cycles use the write-capable shared memory transport, so
    // requiring this probe to have witnessed both PTEs would be a false fail.
    // Exact RAM/PTE identity was checked above and all 64 PCs were compared.
    if (original.fingerprint() != initialFingerprint)
      throw std::runtime_error("paired source DDR or first Sv39 walk changed");
    std::cout << "PRODUCT_G5_SAME_QEMU_RAM_64_INTEGER_RETIRE_AND_FINAL_DDR_MATCH_PARTIAL_DIFFTEST"
              << " retirements=" << ordinal << " cycles_after_release=" << cycle
              << " same_qemu_capture_ram=1 integer_xpr_after_every_retire=1"
              << " full_256m_ddr_end_match=1 fpr_csr_mcycle_devices_not_compared=1"
              << " not_full_qemu_handoff=1 not_linux_boot=1 not_sd1=1\n";
    return 0;
  }
  std::cerr << "G5_QEMU_PAIRED64_STOP_CYCLE_BOUND retirements=" << ordinal
            << " expected=64 cycles=" << bound << '\n';
  return 3;
}
} // namespace

int main(int argc, char** argv) {
  try { return pairedRun(argc, argv); }
  catch (const std::exception& e) {
    std::cerr << "PRODUCT_G5_QEMU_PAIRED64_REJECTED: " << e.what() << '\n';
    return 2;
  }
}
