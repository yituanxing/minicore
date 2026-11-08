/* Deliberately unilateral Product G5 RTL retirement trace from signed QEMU RAM.
 *
 * The SHA-checked QEMU image is read into an immutable source Memory and a
 * SEPARATE writable 256MiB DDR model.  Only the first retirement has an
 * independently captured QEMU architectural oracle.  Subsequent source-byte
 * comparisons check the original image, NOT QEMU execution or complete
 * architectural state.  There is no FPR/PLIC/UART/CLINT/complete CSR replay.
 * A bounded window of valid DUT retirements must never be called DiffTest,
 * full QEMU handoff, or Debian service/login success.
 */

#define AETHERCORE_QEMU_RAM_TRACE_EMBED
#include "product_c1_g5_qemu_ram_first_retire_main.cpp"

#include <limits>
#include <optional>

namespace {
constexpr std::uint64_t kSatpPpnMask = (1ULL << 44) - 1ULL;

std::uint64_t retireLimit(const char* raw) {
  const std::string value(raw);
  if (value.empty() || value[0] == '-')
    throw std::runtime_error("retire count must be a positive integer");
  std::size_t end = 0;
  const auto count = std::stoull(value, &end, 10);
  if (end != value.size() || count == 0 || count > 256)
    throw std::runtime_error("DUT-only window must request 1..256 retirements");
  return count;
}

// Translate only against the immutable original QEMU snapshot.  The live
// post-seed RTL can update PTEs; this is specifically not its dynamic PTW.
std::optional<std::uint64_t> originalInstructionPa(
    const Memory& original, std::uint64_t va) {
  const auto vpn2 = (va >> 30) & 0x1ff;
  const auto vpn1 = (va >> 21) & 0x1ff;
  const auto vpn0 = (va >> 12) & 0x1ff;
  const std::array<std::uint64_t, 3> vpn{vpn0, vpn1, vpn2};
  std::uint64_t base = (kSourceSatp & kSatpPpnMask) << 12;
  for (int level = 2; level >= 0; --level) {
    const auto ptePa = base + 8 * vpn[static_cast<std::size_t>(level)];
    if (!original.contains(ptePa, 8)) return std::nullopt;
    const auto pte = original.readData(ptePa, 8);
    const bool v = pte & 1;
    const bool r = pte & 2;
    const bool w = pte & 4;
    const bool x = pte & 8;
    if (!v || (w && !r)) return std::nullopt;
    const auto ppn = (pte >> 10) & kSatpPpnMask;
    if (r || x) {
      if (!x) return std::nullopt;
      const auto lowBits = static_cast<unsigned>(12 + 9 * level);
      const auto lowerMask = (1ULL << lowBits) - 1ULL;
      const auto pa = ((ppn << 12) & ~lowerMask) | (va & lowerMask);
      if (((ppn << 12) & lowerMask) != 0 || !original.contains(pa, 2))
        return std::nullopt;
      return pa;
    }
    if (level == 0) return std::nullopt;
    base = ppn << 12;
  }
  return std::nullopt;
}

void printRetirement(const Product& top, std::uint64_t ordinal,
                     std::uint64_t cycle) {
  std::cout << "G5_QEMU_RAM_DUT_ONLY_RETIRE"
            << " n=" << std::dec << ordinal << " cycle=" << cycle
            << " pc=0x" << std::hex << top.io_commit_pc
            << " raw=0x" << top.io_commit_rawInst
            << " canonical=0x" << top.io_commit_inst << std::dec
            << " bytes=" << static_cast<unsigned>(top.io_commit_instBytes)
            << " rd_write=" << static_cast<unsigned>(top.io_commit_rdWrite)
            << " rd=" << static_cast<unsigned>(top.io_commit_rd)
            << " rd_data=0x" << std::hex << top.io_commit_rdData
            << " mem_valid=" << static_cast<unsigned>(top.io_commit_memValid)
            << " mem_write=" << static_cast<unsigned>(top.io_commit_memWrite)
            << " mem_addr=0x" << top.io_commit_memAddr
            << " mem_wdata=0x" << top.io_commit_memWdata
            << " mem_wmask=0x" << top.io_commit_memWmask
            << " exception=" << static_cast<unsigned>(top.io_commit_exception)
            << " exception_cause=0x" << top.io_commit_exceptionCause
            << " exception_value=0x" << top.io_commit_exceptionValue
            << " interrupt=" << static_cast<unsigned>(top.io_commit_interrupt)
            << " interrupt_cause=0x" << top.io_commit_interruptCause
            << std::dec << '\n';
}

int runWindow(int argc, char** argv) {
  if (argc != 4)
    throw std::runtime_error("usage: G5_QEMU_RAM_RETIRE_WINDOW RAM256M CYCLE_BOUND RETIRE_COUNT<=256");
  const auto cycleBound = boundedCycles(argv[2]);
  const auto target = retireLimit(argv[3]);
  if (std::filesystem::file_size(argv[1]) != aethercore::l32sim::kRamSize)
    throw std::runtime_error("source file is not exactly 256MiB");

  Memory original;
  original.loadAtBase(argv[1]);
  // writable DDR is a distinct allocation, never mmap/write source fixture.
  Memory live;
  live.restore(original.snapshot());
  const auto sourceFingerprint = original.fingerprint();
  if (original.readInstruction(kPhysicalPc, 2) != kFirstOpcode ||
      original.readData(kRootPteAddr, 8) != kRootPte ||
      original.readData(kLeafPteAddr, 8) != kLeafPte ||
      originalInstructionPa(original, kGuestPc) != kPhysicalPc ||
      kCapturedGprs[0] != 0 || kCapturedGprs[2] != kSpBefore)
    throw std::runtime_error("authenticated QEMU Sv39 and full XPR identity rejected");

  VerilatedContext ctx;
  Product top(&ctx);
  top.reset = 1;
  top.clock = 0;
  seedInputs(top);
  PageWalk firstWalk;
  for (unsigned i = 0; i < 4; ++i) step(top, ctx, live, firstWalk);
  top.reset = 0;
  top.io_warmSeed_load = 1;
  top.io_warmSeed_csr_valid = 1;
  step(top, ctx, live, firstWalk);
  top.io_warmSeed_load = 0;
  top.io_warmSeed_csr_valid = 0;
  for (unsigned i = 1; i < 32; ++i) {
    top.io_warmSeed_gpr_valid = 1;
    top.io_warmSeed_gpr_bits_index = i;
    top.io_warmSeed_gpr_bits_data = kCapturedGprs[i];
    step(top, ctx, live, firstWalk);
  }
  top.io_warmSeed_gpr_valid = 0;
  for (unsigned i = 0; i < 2; ++i) {
    step(top, ctx, live, firstWalk);
    requireQuietDevices(top);
    if (top.io_commit_valid || top.io_imemValid || top.io_memValid || top.io_ptwValid ||
        top.io_mtime != 0)
      throw std::runtime_error("guest advanced during held QEMU architectural preload");
  }
  if (live.fingerprint() != sourceFingerprint)
    throw std::runtime_error("writable DDR mutated before warm release");
  top.io_warmSeed_hold = 0;

  aethercore::l32sim::TransportRuntimeState transport;
  std::uint64_t retired = 0;
  std::uint64_t csrSensitive = 0;
  std::uint64_t guestStores = 0;
  std::uint64_t observedPtwCandidates = 0;
  for (std::uint64_t cycle = 0; cycle < cycleBound; ++cycle) {
    const bool dataStoreBefore = top.io_memValid && top.io_memReady &&
        (top.io_memWrite || top.io_memAtomic);
    // Reuse the already REAL-RTL-PASSed first-retire transport and its strict
    // captured root/leaf PTW observer. Only afterwards enable writable DDR.
    if (retired == 0) step(top, ctx, live, firstWalk);
    else aethercore::l32sim::step(top, ctx, live, transport, false, 0);
    if (dataStoreBefore) ++guestStores;  // descriptive only, not a bus handshake proof

    // These are post-rising-edge visible signals, NOT a complete PTW event log.
    if (top.io_ptwValid && observedPtwCandidates < 32) {
      ++observedPtwCandidates;
      const auto pa = static_cast<std::uint64_t>(top.io_ptwAddr);
      std::cout << "G5_QEMU_RAM_DUT_ONLY_PTW_CANDIDATE cycle=" << cycle
                << " pa=0x" << std::hex << pa
                << " live_pte=0x" << top.io_ptwRdata << std::dec
                << " source_pte=";
      if (original.contains(pa, 8))
        std::cout << "0x" << std::hex << original.readData(pa, 8) << std::dec;
      else std::cout << "outside-original-ddr";
      std::cout << '\n';
    }
    if (top.io_uartValid || top.io_exitValid || top.io_timerInterrupt ||
        top.io_supervisorExternalInterrupt || top.io_uartInterrupt ||
        top.io_uartRxInterrupt) {
      std::cerr << "G5_QEMU_RAM_DUT_ONLY_STOP_UNRESTORED_DEVICE cycle=" << cycle
                << " last_retired=" << retired << " pc=0x" << std::hex
                << top.io_commit_pc << std::dec << '\n';
      return 3;
    }
    if (!top.io_commit_valid) continue;
    ++retired;
    printRetirement(top, retired, cycle);
    if (retired == 1) {
      if (top.io_commit_exception || top.io_commit_interrupt ||
          top.io_commit_pc != kGuestPc || top.io_commit_rawInst != kFirstOpcode ||
          top.io_commit_instBytes != 2 || !top.io_commit_rdWrite ||
          top.io_commit_rd != 2 || top.io_commit_rdData != kSpAfter ||
          !firstWalk.root || !firstWalk.leaf)
        throw std::runtime_error("first QEMU-oracle retirement diverged: see DUT-only retirement fields");
      std::cout << "G5_QEMU_RAM_FIRST_RETIRE_REAL_QEMU_ORACLE_MATCH_AND_UNRESTORED_STATE\n";
    }
    if (top.io_commit_exception || top.io_commit_interrupt) {
      std::cerr << "G5_QEMU_RAM_DUT_ONLY_STOP_FIRST_EXCEPTION_OR_INTERRUPT"
                << " n=" << retired << " pc=0x" << std::hex
                << top.io_commit_pc << " cause=0x" << top.io_commit_exceptionCause
                << std::dec << '\n';
      return 3;
    }
    const auto opBytes = static_cast<std::size_t>(top.io_commit_instBytes);
    const auto pa = originalInstructionPa(original, top.io_commit_pc);
    if (!pa || (opBytes != 2 && opBytes != 4) || !original.contains(*pa, opBytes)) {
      std::cerr << "G5_QEMU_RAM_DUT_ONLY_STOP_ORIGINAL_SV39_PC_UNMAPPED"
                << " n=" << retired << " pc=0x" << std::hex
                << top.io_commit_pc << std::dec << '\n';
      return 3;
    }
    auto originalOpcode = original.readInstruction(*pa, opBytes);
    if (opBytes == 4 && (top.io_commit_pc & 0xfff) == 0xffe) {
      // The high halfword may live on a non-contiguous translated page.
      const auto nextPa = originalInstructionPa(original, top.io_commit_pc + 2);
      if (!nextPa || !original.contains(*nextPa, 2)) {
        std::cerr << "G5_QEMU_RAM_DUT_ONLY_STOP_ORIGINAL_CROSS_PAGE_INSTRUCTION_UNMAPPED"
                  << " n=" << retired << " pc=0x" << std::hex
                  << top.io_commit_pc << std::dec << '\n';
        return 3;
      }
      originalOpcode = original.readInstruction(*pa, 2) |
          (original.readInstruction(*nextPa, 2) << 16);
    }
    if (originalOpcode != top.io_commit_rawInst) {
      std::cerr << "G5_QEMU_RAM_DUT_ONLY_FIRST_SOURCE_STATIC_OPCODE_MISMATCH"
                << " n=" << retired << " pc=0x" << std::hex
                << top.io_commit_pc << " source_pa=0x" << *pa
                << " original=0x" << originalOpcode
                << " rtl=0x" << top.io_commit_rawInst << std::dec
                << " caveat=mutable_guest_page_tables_or_self_modifying_code_possible\n";
      return 3;
    }
    // System opcode signals an unverified CSR or trap transition, not QEMU
    // parity. Track it without fabricating inaccessible CSR state.
    if ((top.io_commit_rawInst & 0x7f) == 0x73) {
      ++csrSensitive;
      std::cout << "G5_QEMU_RAM_DUT_ONLY_CSR_UNVERIFIED n=" << retired
                << " pc=0x" << std::hex << top.io_commit_pc << std::dec << '\n';
    }
    if (retired == target) {
      if (original.fingerprint() != sourceFingerprint)
        throw std::runtime_error("immutable QEMU RAM source fingerprint changed");
      std::cout << "G5_REAL_QEMU_RAM_DUT_ONLY_RETIRE_WINDOW_OBSERVED_NOT_DIFFTEST"
                << " retirements=" << retired << " cycles=" << cycle
                << " unverified_csr_opcode_count=" << csrSensitive
                << " candidate_ptw_samples=" << observedPtwCandidates
                << " approximate_write_cycles=" << guestStores
                << " immutable_qemu_source=1 separate_writable_ddr=1"
                << " first_qemu_oracle_only=1 remaining_qemu_oracle_absent=1"
                << " incomplete_csr_fp_plic_uart_clint_lrsc=1"
                << " not_qemu_full_handoff=1 not_difftest=1 not_sd1=1\n";
      return 0;
    }
  }
  std::cerr << "G5_QEMU_RAM_DUT_ONLY_STOP_CYCLE_BOUND"
            << " retirements=" << retired << " requested=" << target
            << " cycles=" << cycleBound << '\n';
  return 3;
}
} // namespace

int main(int argc, char** argv) {
  try { return runWindow(argc, argv); }
  catch (const std::exception& e) {
    std::cerr << "G5_QEMU_RAM_DUT_ONLY_REJECTED: " << e.what() << '\n';
    return 2;
  }
}
