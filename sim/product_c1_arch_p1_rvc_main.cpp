/* Isolated real Product G5 RTL first-source-RVC synthetic Sv39 experiment (not Debian SD1).
 *
 * Usage: VProductC1P1 RAM256M.bin restore-flat.bin state-424.bin
 *         0x18000 0xffffffff808c3490 MAX_CYCLES
 *
 * The first image is an exact 256-MiB physical RAM image (currently generated
 * only as a synthetic G5 RVC Sv39 fixture, NOT captured from QEMU/Linux). The
 * second must be a *separately linked* restore-only raw image whose first
 * instruction resides at 0x80000000 and whose state placeholder resides at
 * 0x80018000.  Never pass a combined guest+restore ELF/flat binary here.
 * Host read responses overlay the restore image; physical RAM is not patched.
 *
 * A target retirement is diagnostic evidence of real Product RTL execution.
 * It is NOT full QEMU architectural equivalence, SD1 boot, or Linux PASS.
 * This fixture never accesses the source's first MiB after MRET; because the
 * restore stub executes FENCE.I *before* MRET, the experiment also does NOT
 * qualify later reuse of that region, I-cache/TLB coherency, or live Linux.
 */

#include "VAetherCoreProductDebianRV64SimTop.h"
#include "product_c1_arch_p0_overlay.h"
#include "verilated.h"

#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

using aethercore::l32sim::Memory;
using aethercore::c1p0::BootOverlay;
using Product = VAetherCoreProductDebianRV64SimTop;

constexpr std::uint32_t kMret = 0x30200073U;
constexpr std::uint64_t kP0PmpAddr0 = 0x21ffffffULL;
constexpr std::uint64_t kP0PmpCfg0 = 0x1fULL;
constexpr std::size_t kP0StateOffset = 0x18000;
constexpr std::uint64_t kP0GuestPc = 0xffffffff808c3490ULL;
constexpr std::uint64_t kP1GuestPhysicalPc = 0x80ac3490ULL;
constexpr std::uint64_t kRvcInitialSp = 0xffffffc80000bec0ULL;
constexpr std::uint64_t kRvcExpectedSp = 0xffffffc80000be90ULL;
constexpr std::uint32_t kRvcFirstOpcode = 0x7179U;
constexpr unsigned kRvcGuestCommits = 1;
constexpr std::uint32_t kSModeIllegalCsrProbe = 0x300026f3U;
constexpr std::uint64_t kIllegalInstructionCause = 2;
constexpr std::uint64_t kP1RootPa = 0x80200000ULL;
constexpr std::uint64_t kP1HighL1Pa = 0x80201000ULL;
constexpr std::uint64_t kP1Satp = (8ULL << 60) | (kP1RootPa >> 12);
constexpr std::uint64_t kP1HighRootPteAddr = kP1RootPa + 510 * 8;
constexpr std::uint64_t kP1HighLeafPteAddr = kP1HighL1Pa + 4 * 8;
constexpr std::uint64_t kP1LeafPte = ((0x80a00000ULL >> 12) << 10) | 0xcf;

struct PtwEvidence {
  bool highRoot = false;
  bool highLeaf = false;
  std::uint64_t totalRequests = 0;

  void observe(std::uint64_t addr, std::uint64_t pte) {
    ++totalRequests;
    if (addr == kP1HighRootPteAddr) {
      if (pte != ((kP1HighL1Pa >> 12) << 10 | 1))
        throw std::runtime_error("G5 RTL Sv39 kernel root PTE differs from DDR");
      highRoot = true;
    } else if (addr == kP1HighLeafPteAddr) {
      if (pte != kP1LeafPte)
        throw std::runtime_error("G5 RTL Sv39 kernel 2-MiB leaf differs from DDR");
      highLeaf = true;
    } else {
      throw std::runtime_error("G5 RTL Sv39 PTW requested an unexpected address");
    }
  }

  bool complete() const { return highRoot && highLeaf; }
};

std::uint64_t parseNumber(const char* raw, const char* label) {
  const std::string source(raw);
  if (source.empty() || source[0] == '-')
    throw std::runtime_error(std::string("invalid ") + label);
  std::size_t consumed = 0;
  const auto parsed = std::stoull(source, &consumed, 0);
  if (consumed != source.size())
    throw std::runtime_error(std::string("invalid ") + label);
  return parsed;
}

std::vector<std::uint8_t> loadBytes(const std::string& path,
                                     std::size_t limit) {
  std::ifstream input(path, std::ios::binary | std::ios::ate);
  if (!input) throw std::runtime_error("cannot open P0 image: " + path);
  const auto length = static_cast<std::streamoff>(input.tellg());
  if (length <= 0 || static_cast<std::uint64_t>(length) > limit)
    throw std::runtime_error("invalid P1 input size: " + path);
  std::vector<std::uint8_t> result(static_cast<std::size_t>(length));
  input.seekg(0, std::ios::beg);
  input.read(reinterpret_cast<char*>(result.data()),
             static_cast<std::streamsize>(length));
  if (!input || input.gcount() != static_cast<std::streamsize>(length))
    throw std::runtime_error("short P0 image read: " + path);
  return result;
}

std::uint64_t le64(const std::vector<std::uint8_t>& bytes,
                   std::size_t offset) {
  if (offset > bytes.size() || 8 > bytes.size() - offset)
    throw std::runtime_error("P1 state table field out of bounds");
  std::uint64_t value = 0;
  for (std::size_t i = 0; i < 8; ++i)
    value |= std::uint64_t(bytes[offset + i]) << (8 * i);
  return value;
}

// This runner deliberately owns a separate reference-order transport.  The
// shared driveMemory/step accept concrete Memory&, whose read methods are not
// virtual; substituting a derived type would silently bypass the overlay.
void driveP0Memory(Product& top, const Memory& memory,
                   const BootOverlay& overlay, PtwEvidence& ptwEvidence) {
  const bool ivalid = top.io_imemValid;
  const auto iaddr = static_cast<std::uint64_t>(top.io_imemAddr);
  const auto ibytes = static_cast<std::size_t>(top.io_imemBytes);
  const bool invalidInstructionWidth = ibytes != 2 && ibytes != 4;
  const bool ifault = ivalid &&
      (invalidInstructionWidth || !overlay.contains(iaddr, ibytes));
  top.io_imemFault = ifault;
  top.io_imemInst = (!ivalid || ifault) ? 0
      : overlay.readInstruction(iaddr, ibytes);

  const bool dvalid = top.io_memValid;
  const auto daddr = static_cast<std::uint64_t>(top.io_memAddr);
  const auto dbytes = dvalid ? aethercore::l32sim::dataBytesFromMemSize(
      static_cast<std::uint32_t>(top.io_memSize)) : std::size_t{1};
  const bool dfault = dvalid && !overlay.contains(daddr, dbytes);
  top.io_memReady = true;  // P0 is fixed zero-wait, single hart.
  top.io_memFault = dfault;
  const bool atomic = static_cast<bool>(top.io_memAtomic);
  if (dvalid && overlay.active() && atomic)
    throw std::runtime_error("P1 pre-resume atomic transaction is forbidden");
  top.io_memRdata = (!dvalid || dfault) ? 0
      : atomic ? memory.atomicResponse(daddr, dbytes,
                                      static_cast<std::uint32_t>(top.io_memAtomicOp))
               : overlay.readData(daddr, dbytes);

  const bool ptwValid = top.io_ptwValid;
  const auto ptwAddr = static_cast<std::uint64_t>(top.io_ptwAddr);
  constexpr std::size_t ptwBytes = sizeof(top.io_ptwRdata);
  static_assert(ptwBytes == 8, "RV64 P0 requires an 8-byte page-table port");
  const bool ptwFault = ptwValid && !overlay.contains(ptwAddr, ptwBytes);
  top.io_ptwReady = true;
  top.io_ptwFault = ptwFault;
  top.io_ptwRdata = (!ptwValid || ptwFault)
      ? 0 : overlay.readData(ptwAddr, ptwBytes);
  if (ptwValid) {
    if (ptwFault)
      throw std::runtime_error("G5 RTL Sv39 page walk touched non-DDR memory");
    ptwEvidence.observe(ptwAddr, static_cast<std::uint64_t>(top.io_ptwRdata));
  }
}

void stepP0(Product& top, VerilatedContext& context, Memory& memory,
            const BootOverlay& overlay, PtwEvidence& ptwEvidence) {
  top.clock = 0;
  top.io_rxValid = 0;
  top.io_rxByte = 0;
  // Match the shared reference transport's two low-phase drive/evals.  The
  // overlay is consulted afresh after each eval, including imem/data/PTW.
  driveP0Memory(top, memory, overlay, ptwEvidence);
  top.eval();
  driveP0Memory(top, memory, overlay, ptwEvidence);
  top.eval();

  const bool handshake = !top.reset && top.io_memValid && top.io_memReady;
  const bool accepted = handshake && !top.io_memFault;
  if (accepted) {
    const auto address = static_cast<std::uint64_t>(top.io_memAddr);
    const auto bytes = aethercore::l32sim::dataBytesFromMemSize(
        static_cast<std::uint32_t>(top.io_memSize));
    const bool atomic = static_cast<bool>(top.io_memAtomic);
    if (atomic || top.io_memWrite)
      overlay.rejectWrite(address, bytes);
    // First Linux-observed RVC is register-only, followed by the synthetic
    // illegal CSR sentinel. No data writes are valid at any stage.
    if (!overlay.active() && (atomic || top.io_memWrite)) {
      throw std::runtime_error("G5 RVC guest attempted unexpected memory write/atomic");
    }
    if (atomic) {
      memory.commitAtomic(address, bytes,
                          static_cast<std::uint64_t>(top.io_memWdata),
                          static_cast<std::uint64_t>(top.io_memWmask),
                          static_cast<std::uint32_t>(top.io_memAtomicOp));
    } else if (top.io_memWrite) {
      memory.writeMasked(address,
                         static_cast<std::uint64_t>(top.io_memWdata),
                         static_cast<std::uint64_t>(top.io_memWmask), bytes);
      memory.clearReservation();
    }
  }
  top.clock = 1;
  top.eval();
  context.timeInc(1);
}

int run(int argc, char** argv) {
  if (argc != 7)
    throw std::runtime_error(
        "usage: PRODUCT_C1_P1 RAM256M restore-flat state-424 0x18000 "
        "target-pc max-cycles");
  if (aethercore::l32sim::dataMemoryWaitCycles() != 0)
    throw std::runtime_error("P0 requires zero data-memory wait cycles");
  const char* settle = std::getenv("SIM_SETTLE_MODE");
  if (settle != nullptr && std::string(settle) != "reference")
    throw std::runtime_error("P0 requires reference settle mode");
  const auto ramBytes = std::filesystem::file_size(argv[1]);
  if (ramBytes != aethercore::l32sim::kRamSize)
    throw std::runtime_error("P1 RAM must be exact unmodified 256 MiB");
  const auto tableOffset = parseNumber(argv[4], "state-table offset");
  if (tableOffset != kP0StateOffset)
    throw std::runtime_error("P0 restore state must be at 0x80018000");
  const auto targetPc = parseNumber(argv[5], "target PC");
  if (targetPc & 1U)
    throw std::runtime_error("G5 RVC Sv39 synthetic target PC must be 2-byte aligned");
  if (targetPc != kP0GuestPc)
    throw std::runtime_error("P1 runner accepts only the fixed synthetic target");
  const auto maxCycles = parseNumber(argv[6], "max cycles");
  if (maxCycles == 0 || maxCycles > 1000000)
    throw std::runtime_error("P0 must use a bounded 1..1000000-cycle budget");

  auto image = loadBytes(argv[2], aethercore::c1p0::kRestoreLimit -
                                   aethercore::c1p0::kRestoreBase);
  auto table = loadBytes(argv[3], aethercore::c1p0::kStateTableBytes);
  if (le64(table, 0) != targetPc || le64(table, 8) != 0x88 ||
      le64(table, 120) != kP1Satp || le64(table, 152) != kP0PmpAddr0 ||
      le64(table, 160) != kP0PmpCfg0)
    throw std::runtime_error("G5 RVC synthetic state PC/SATP/PMP does not match contract");
  constexpr std::size_t gprOffset = 168;
  if (le64(table, gprOffset + 2 * 8) != kRvcInitialSp)
    throw std::runtime_error("G5 RVC initial SP differs from QEMU first-retire oracle");

  Memory memory;
  memory.loadAtBase(argv[1]);
  const auto sourceMemoryFingerprint = memory.fingerprint();
  BootOverlay overlay(memory, std::move(image), std::move(table),
                      static_cast<std::size_t>(tableOffset));
  // Page-table pages are in original source RAM, never the temporary overlay.
  if (memory.readData(kP1HighRootPteAddr, 8) !=
          ((kP1HighL1Pa >> 12) << 10 | 1) ||
      memory.readData(kP1HighLeafPteAddr, 8) != kP1LeafPte ||
      memory.readInstruction(kP1GuestPhysicalPc, 2) != kRvcFirstOpcode ||
      memory.readInstruction(kP1GuestPhysicalPc + 2, 4) != kSModeIllegalCsrProbe)
    throw std::runtime_error("G5 RVC physical RAM source/PTE/sentinel mismatch");

  VerilatedContext context;
  Product top(&context);
  top.reset = 1;
  top.clock = 0;
  top.io_rxValid = 0;
  top.io_rxByte = 0;
  PtwEvidence ptwEvidence;
  driveP0Memory(top, memory, overlay, ptwEvidence);
  top.eval();

  std::uint64_t commits = 0;
  bool sawMret = false;
  unsigned guestCommits = 0;
  bool observedFirstRvc = false;
  for (std::uint64_t cycle = 0; cycle < maxCycles; ++cycle) {
    stepP0(top, context, memory, overlay, ptwEvidence);
    if (cycle == 3) top.reset = 0;
    if (top.reset) continue;
    if (top.io_uartValid || top.io_exitValid || top.io_timerInterrupt ||
        top.io_supervisorExternalInterrupt || top.io_uartInterrupt ||
        top.io_uartRxInterrupt)
      throw std::runtime_error("P0 encountered unexported device/interrupt activity");
    if (top.io_commit_interrupt)
      throw std::runtime_error("G5 RVC Sv39 RTL unexpected interrupt");
    // Some Product commit sidebands can assert without commit.valid. Never
    // ignore an interruption or an exception observed between retirements.
    if (top.io_commit_exception && !top.io_commit_valid)
      throw std::runtime_error(
          "G5 RTL synthetic Sv39 emitted exception without retirement");
    if (!top.io_commit_valid) continue;
    ++commits;
    const auto pc = static_cast<std::uint64_t>(top.io_commit_pc);
    const auto instruction = static_cast<std::uint32_t>(top.io_commit_inst);
    if (top.io_commit_exception) {
      // In S-mode, the M-only mstatus CSR must fault precisely.  A resume
      // that stayed in M-mode would retire that instruction normally.
      const auto cause = static_cast<std::uint64_t>(top.io_commit_exceptionCause);
      const auto value = static_cast<std::uint64_t>(top.io_commit_exceptionValue);
      const auto raw = static_cast<std::uint32_t>(top.io_commit_rawInst);
      if (!sawMret || !observedFirstRvc ||
          !ptwEvidence.complete() ||
          guestCommits != kRvcGuestCommits ||
          pc != targetPc + 2 ||
          instruction != kSModeIllegalCsrProbe ||
          raw != kSModeIllegalCsrProbe ||
          cause != kIllegalInstructionCause ||
          value != kSModeIllegalCsrProbe)
        throw std::runtime_error("G5 RVC next-PC S-mode sentinel contract mismatch");
      std::cout << "PRODUCT_C1_G5_RVC_RTL_SOURCE_OPCODE_SP_NEXTPC_PTW_PASS_NOT_SD1"
                << " cycles=" << cycle << " total_commits=" << commits
                << " guest_nonfaulting_commits=" << guestCommits
                << " first_pc=0x" << std::hex << targetPc
                << " first_raw=0x" << kRvcFirstOpcode
                << " first_rd=x2 expected_sp_after=0x" << kRvcExpectedSp
                << " next_pc=0x" << pc << std::dec
                << " source_ram_preserved_at_mret=1"
                << " sv39_root_and_leaf_ptw_observed=1"
                << " ptw_observations=" << ptwEvidence.totalRequests
                << " satp_mode=8 virtual_pc=1 physical_data_stores=0"
                << " s_mode_illegal_machine_csr_trap=1"
                << " pmp_ddr_napot=1 one_hart=1 no_irq=1 no_sd1=1"
                << " source_opcode_only=1 synthetic_sentinel=1"
                << " reserved_mib_later_reuse_unqualified=1"
                << " cache_tlb_transfer_unqualified=1\n";
      return 0;
    }
    if (!sawMret) {
      if (pc < aethercore::c1p0::kRestoreBase) {
        // Internal fixed BootROM at 0x1000, not host DDR.
        if (pc < 0x1000 || pc >= 0x100c)
          throw std::runtime_error("P0 unexpected pre-restore ROM retirement");
      } else if (pc >= aethercore::c1p0::kRestoreLimit) {
        throw std::runtime_error("P0 reached guest before qualified MRET");
      }
      if (pc >= aethercore::c1p0::kRestoreBase &&
          instruction == kMret) {
        sawMret = true;
        overlay.retireMret();
        if (memory.fingerprint() != sourceMemoryFingerprint)
          throw std::runtime_error("P0 source RAM changed before MRET");
        std::cerr << "PRODUCT_C1_G5_RVC_RTL_MRET_RETIRED cycles=" << cycle
                  << " commits=" << commits << " pc=0x" << std::hex << pc
                  << std::dec << " original_ddr_unchanged=1 overlay_disabled=1\n";
      }
      continue;
    }
    if (pc != targetPc || guestCommits != 0)
      throw std::runtime_error("G5 RVC first retirement PC/count differs from oracle");
    const auto width = static_cast<unsigned>(top.io_commit_instBytes);
    if (width != 2 && width != 4)
      throw std::runtime_error("P0 resumed instruction has invalid width");
    // ISA retirement reports virtual PC. The physical source bytes are only
    // obtained after the hardware MMU/PTW has translated fetch requests.
    const auto sourceInst = memory.readInstruction(kP1GuestPhysicalPc, 2);
    const auto fetched = static_cast<std::uint32_t>(top.io_commit_rawInst);
    if (width != 2 || sourceInst != kRvcFirstOpcode ||
        fetched != kRvcFirstOpcode ||
        !top.io_commit_rdWrite ||
        static_cast<unsigned>(top.io_commit_rd) != 2 ||
        static_cast<std::uint64_t>(top.io_commit_rdData) != kRvcExpectedSp)
      throw std::runtime_error("G5 RVC first retired opcode/width/rd/SP differs from source oracle");
    ++guestCommits;
    observedFirstRvc = true;
    std::cerr << "PRODUCT_C1_G5_RVC_RTL_FIRST_INSTRUCTION_RETIRED"
              << " cycle=" << cycle << " pc=0x" << std::hex << pc
              << " raw=0x" << fetched << " x2=0x"
              << static_cast<std::uint64_t>(top.io_commit_rdData)
              << std::dec << " width=2 rd=2 rdWrite=1\n";
  }
  throw std::runtime_error("G5 RVC fixture expired before first+sentinel retirement");
}

}  // namespace

int main(int argc, char** argv) {
  try {
    return run(argc, argv);
  } catch (const std::exception& error) {
    std::cerr << "PRODUCT_C1_G5_RVC_HOST_REJECTED: " << error.what() << '\n';
    return 2;
  }
}
