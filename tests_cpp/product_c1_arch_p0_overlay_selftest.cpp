/* Host-only Product C1 P0 transport check (no Verilator or QEMU required).
 * c++ -std=c++20 -O2 -DAETHERCORE_GSIM_HOST_MEMORY_ONLY -Isim \
 *   tests_cpp/product_c1_arch_p0_overlay_selftest.cpp -o /tmp/product-c1-p0-overlay-test
 */

#include "product_c1_arch_p0_overlay.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <vector>

using aethercore::c1p0::BootOverlay;
using aethercore::c1p0::kRestoreBase;
using aethercore::l32sim::Memory;

int main() {
  Memory memory;
  // The source RAM is intentionally different from the shim and state.
  memory.writeMasked(kRestoreBase, 0x8899aabbccddeeffULL, 0xff, 8);
  memory.writeMasked(kRestoreBase + 0x18000, 0x0102030405060708ULL, 0xff, 8);
  memory.writeMasked(kRestoreBase + 0x100000, 0x123456789abcdef0ULL, 0xff, 8);

  std::vector<std::uint8_t> flat(0x18000 + 424, 0);
  flat[0] = 0x93;
  flat[1] = 0x02;
  flat[2] = 0x10;
  flat[3] = 0x00;
  std::vector<std::uint8_t> table(424, 0);
  table[0] = 0x55;
  table[1] = 0x44;
  table[2] = 0x33;
  table[3] = 0x22;
  BootOverlay overlay(memory, flat, table, 0x18000);

  // The imem and data/PTW paths must read the identical *overlay* bytes.
  assert(overlay.contains(kRestoreBase, 4));
  assert(overlay.readInstruction(kRestoreBase, 4) == 0x00100293U);
  assert(overlay.readInstruction(kRestoreBase, 2) == 0x0293U);
  assert(overlay.readData(kRestoreBase + 0x18000, 8) == 0x22334455ULL);
  assert(overlay.readData(kRestoreBase + 0x100000, 8) == 0x123456789abcdef0ULL);
  assert(memory.readData(kRestoreBase, 8) == 0x8899aabbccddeeffULL);
  assert(memory.readData(kRestoreBase + 0x18000, 8) == 0x0102030405060708ULL);
  bool rejected = false;
  try {
    overlay.rejectWrite(kRestoreBase + 0x18000, 8);
  } catch (const std::runtime_error&) {
    rejected = true;
  }
  assert(rejected);

  overlay.retireMret();
  assert(!overlay.active());
  assert(overlay.readInstruction(kRestoreBase, 4) == 0xccddeeffU);
  assert(overlay.readData(kRestoreBase + 0x18000, 8) == 0x0102030405060708ULL);
  assert(overlay.readData(kRestoreBase + 0x100000, 8) == 0x123456789abcdef0ULL);

  rejected = false;
  try {
    BootOverlay invalid(memory, flat, std::vector<std::uint8_t>(423, 0), 0x18000);
    (void)invalid;
  } catch (const std::runtime_error&) {
    rejected = true;
  }
  assert(rejected);
  std::cout << "PRODUCT_C1_P0_HOST_OVERLAY_SELFTEST_PASS"
            << " imem=1 data=1 ptw=1 source_ram_preserved=1"
            << " mret_removal=1 invalid_size_rejected=1\n";
}
