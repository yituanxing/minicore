/* Isolated Product G4 C1 architectural-resume experiment (not Debian SD1).
 *
 * Usage: VProductC1P0 RAM256M.bin restore-flat.bin state-424.bin
 *         0x18000 0x80100000 MAX_CYCLES
 *
 * The first image is an exact 256-MiB physical RAM image (currently generated
 * only as a synthetic P0 fixture, NOT captured from QEMU/Linux). The
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

#include "VAetherCoreProductOpenSbiRV64SimTop.h"
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
using Product = VAetherCoreProductOpenSbiRV64SimTop;

constexpr std::uint32_t kMret = 0x30200073U;
constexpr std::uint64_t kP0PmpAddr0 = 0x21ffffffULL;
constexpr std::uint64_t kP0PmpCfg0 = 0x1fULL;
constexpr std::size_t kP0StateOffset = 0x18000;
constexpr std::uint64_t kP0GuestPc = 0x80100000ULL;
constexpr std::uint64_t kP0MarkerAddr = 0x80101000ULL;
constexpr unsigned kP0GuestCommits = 5;

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
    throw std::runtime_error("invalid P0 input size: " + path);
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
    throw std::runtime_error("P0 state table field out of bounds");
  std::uint64_t value = 0;
  for (std::size_t i = 0; i < 8; ++i)
    value |= std::uint64_t(bytes[offset + i]) << (8 * i);
  return value;
}

// This runner deliberately owns a separate reference-order transport.  The
// shared driveMemory/step accept concrete Memory&, whose read methods are not
// virtual; substituting a derived type would silently bypass the overlay.
void driveP0Memory(Product& top, const Memory& memory,
                   const BootOverlay& overlay) {
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
    throw std::runtime_error("P0 pre-resume atomic transaction is forbidden");
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
}

void stepP0(Product& top, VerilatedContext& context, Memory& memory,
            const BootOverlay& overlay) {
  top.clock = 0;
  top.io_rxValid = 0;
  top.io_rxByte = 0;
  // Match the shared reference transport's two low-phase drive/evals.  The
  // overlay is consulted afresh after each eval, including imem/data/PTW.
  driveP0Memory(top, memory, overlay);
  top.eval();
  driveP0Memory(top, memory, overlay);
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
    // Synthetic P0 fixture only: once resumed, the sole permitted writes
    // are four aligned, full-width words in its 32-byte result mailbox.
    if (!overlay.active() && (atomic || top.io_memWrite)) {
      const bool markerStore = !atomic && top.io_memWrite && bytes == 8 &&
          address >= kP0MarkerAddr && address < kP0MarkerAddr + 32 &&
          (address - kP0MarkerAddr) % 8 == 0 &&
          static_cast<std::uint64_t>(top.io_memWmask) == 0xff;
      if (!markerStore)
        throw std::runtime_error("P0 guest attempted non-marker memory write");
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
        "usage: PRODUCT_C1_P0 RAM256M restore-flat state-424 0x18000 "
        "target-pc max-cycles");
  if (aethercore::l32sim::dataMemoryWaitCycles() != 0)
    throw std::runtime_error("P0 requires zero data-memory wait cycles");
  const char* settle = std::getenv("SIM_SETTLE_MODE");
  if (settle != nullptr && std::string(settle) != "reference")
    throw std::runtime_error("P0 requires reference settle mode");
  const auto ramBytes = std::filesystem::file_size(argv[1]);
  if (ramBytes != aethercore::l32sim::kRamSize)
    throw std::runtime_error("P0 RAM must be exact unmodified 256 MiB");
  const auto tableOffset = parseNumber(argv[4], "state-table offset");
  if (tableOffset != kP0StateOffset)
    throw std::runtime_error("P0 restore state must be at 0x80018000");
  const auto targetPc = parseNumber(argv[5], "target PC");
  if (targetPc < aethercore::c1p0::kGuestMinimumPc ||
      targetPc >= aethercore::l32sim::kRamBase + aethercore::l32sim::kRamSize ||
      (targetPc & 1U))
    throw std::runtime_error("P0 target PC must be aligned outside first MiB");
  if (targetPc != kP0GuestPc)
    throw std::runtime_error("P0 runner accepts only the fixed synthetic target");
  const auto maxCycles = parseNumber(argv[6], "max cycles");
  if (maxCycles == 0 || maxCycles > 1000000)
    throw std::runtime_error("P0 must use a bounded 1..1000000-cycle budget");

  auto image = loadBytes(argv[2], aethercore::c1p0::kRestoreLimit -
                                   aethercore::c1p0::kRestoreBase);
  auto table = loadBytes(argv[3], aethercore::c1p0::kStateTableBytes);
  if (le64(table, 0) != targetPc || le64(table, 152) != kP0PmpAddr0 ||
      le64(table, 160) != kP0PmpCfg0)
    throw std::runtime_error("P0 state PC/PMP does not match qualified aperture");
  constexpr std::size_t gprOffset = 168;
  const auto expectedX5 = le64(table, gprOffset + 5 * 8);
  const auto expectedX6 = le64(table, gprOffset + 6 * 8);
  const auto expectedX10 = le64(table, gprOffset + 10 * 8);
  const auto expectedX11 = le64(table, gprOffset + 11 * 8);
  const auto expectedX12 = le64(table, gprOffset + 12 * 8);
  if (expectedX10 != kP0MarkerAddr || expectedX12 == UINT64_MAX)
    throw std::runtime_error("P0 GPR fixture marker/payload contract mismatch");

  Memory memory;
  memory.loadAtBase(argv[1]);
  const auto sourceMemoryFingerprint = memory.fingerprint();
  BootOverlay overlay(memory, std::move(image), std::move(table),
                      static_cast<std::size_t>(tableOffset));

  VerilatedContext context;
  Product top(&context);
  top.reset = 1;
  top.clock = 0;
  top.io_rxValid = 0;
  top.io_rxByte = 0;
  driveP0Memory(top, memory, overlay);
  top.eval();

  std::uint64_t commits = 0;
  bool sawMret = false;
  unsigned guestCommits = 0;
  for (std::uint64_t cycle = 0; cycle < maxCycles; ++cycle) {
    stepP0(top, context, memory, overlay);
    if (cycle == 3) top.reset = 0;
    if (top.reset) continue;
    if (top.io_uartValid || top.io_exitValid || top.io_timerInterrupt ||
        top.io_supervisorExternalInterrupt || top.io_uartInterrupt ||
        top.io_uartRxInterrupt)
      throw std::runtime_error("P0 encountered unexported device/interrupt activity");
    if (top.io_commit_exception || top.io_commit_interrupt)
      throw std::runtime_error("P0 RTL exception/interrupt before target retirement");
    if (!top.io_commit_valid) continue;
    ++commits;
    const auto pc = static_cast<std::uint64_t>(top.io_commit_pc);
    const auto instruction = static_cast<std::uint32_t>(top.io_commit_inst);
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
        std::cerr << "PRODUCT_C1_P0_RTL_MRET_RETIRED cycles=" << cycle
                  << " commits=" << commits << " pc=0x" << std::hex << pc
                  << std::dec << " original_ddr_unchanged=1 overlay_disabled=1\n";
      }
      continue;
    }
    if (pc != targetPc + guestCommits * 4ULL)
      throw std::runtime_error("P0 synthetic guest retirement PC mismatch");
    const auto width = static_cast<unsigned>(top.io_commit_instBytes);
    if (width != 2 && width != 4)
      throw std::runtime_error("P0 resumed instruction has invalid width");
    const auto sourceInst = memory.readInstruction(pc, width);
    const auto fetched = static_cast<std::uint32_t>(top.io_commit_rawInst);
    if ((width == 2 && (sourceInst & 0xffffU) != (fetched & 0xffffU)) ||
        (width == 4 && sourceInst != fetched))
      throw std::runtime_error("P0 first retired instruction differs from source RAM");
    if (width != 4)
      throw std::runtime_error("P0 synthetic fixture must retire 32-bit instructions");
    ++guestCommits;
    if (guestCommits == kP0GuestCommits) {
      const auto actualX5 = memory.readData(kP0MarkerAddr, 8);
      const auto actualX6 = memory.readData(kP0MarkerAddr + 8, 8);
      const auto actualX11 = memory.readData(kP0MarkerAddr + 16, 8);
      const auto actualX12 = memory.readData(kP0MarkerAddr + 24, 8);
      if (actualX5 != expectedX5 || actualX6 != expectedX6 ||
          actualX11 != expectedX11 || actualX12 != expectedX12 + 1)
        throw std::runtime_error("P0 GPR restore marker mismatch after five retirements");
      std::cout << "PRODUCT_C1_P0_RTL_SYNTHETIC_FIXTURE_PASS_NOT_QEMU_HANDOFF"
                << " cycles=" << cycle << " total_commits=" << commits
                << " guest_commits=" << guestCommits
                << " target_pc=0x" << std::hex << targetPc
                << " marker_addr=0x" << kP0MarkerAddr
                << " x5=0x" << actualX5
                << " x6=0x" << actualX6
                << " x11=0x" << actualX11
                << " x12=0x" << actualX12 << std::dec
                << " source_ram_preserved_at_mret=1"
                << " pmp_ddr_napot=1 one_hart=1 no_irq=1 no_sd1=1"
                << " reserved_mib_later_reuse_unqualified=1"
                << " cache_tlb_transfer_unqualified=1\n";
      return 0;
    }
  }
  throw std::runtime_error("P0 expired before first target retirement");
}

}  // namespace

int main(int argc, char** argv) {
  try {
    return run(argc, argv);
  } catch (const std::exception& error) {
    std::cerr << "PRODUCT_C1_P0_HOST_REJECTED: " << error.what() << '\n';
    return 2;
  }
}
