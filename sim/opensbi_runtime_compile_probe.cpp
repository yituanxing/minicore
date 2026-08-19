#include "VAetherCoreOpenSbiRV64SimTop.h"
#include "verilated.h"

#include "l32_opensbi_runtime.h"

#include <cstddef>
#include <cstdint>
#include <stdexcept>

int main(int argc, char** argv) {
  VerilatedContext context;
  context.commandArgs(argc, argv);
  VAetherCoreOpenSbiRV64SimTop top{&context};
  aethercore::l32sim::Memory memory;

  static_assert(sizeof(top.io_memRdata) == 8,
                "RV64 OpenSBI data response must be 64 bits");
  static_assert(sizeof(top.io_ptwRdata) == 8,
                "Sv39 OpenSBI PTE response must be 64 bits");

  if (aethercore::l32sim::dataBytesFromMemSize(0) != 1 ||
      aethercore::l32sim::dataBytesFromMemSize(1) != 2 ||
      aethercore::l32sim::dataBytesFromMemSize(2) != 4 ||
      aethercore::l32sim::dataBytesFromMemSize(3) != 8)
    return 2;

  const auto address = aethercore::l32sim::kRamBase + 0x100;
  memory.writeMasked(address, UINT64_C(0x1122334455667788), 0xff, 8);
  if (memory.readData(address, 8) != UINT64_C(0x1122334455667788))
    return 3;

  // A word store on the shared byte-addressed RAM must preserve the untouched
  // upper half of the surrounding RV64 dword, matching the core's low-byte
  // mask contract for SW/AMO.W/SC.W.
  memory.writeMasked(address, UINT64_C(0x00000000aabbccdd), 0x0f, 4);
  if (memory.readData(address, 1) != UINT64_C(0xdd) ||
      memory.readData(address, 2) != UINT64_C(0xccdd) ||
      memory.readData(address, 4) != UINT64_C(0xaabbccdd) ||
      memory.readData(address, 8) != UINT64_C(0x11223344aabbccdd))
    return 4;

  bool rejectedWideMask = false;
  try {
    memory.writeMasked(address, 0, 0x10, 4);
  } catch (const std::runtime_error&) {
    rejectedWideMask = true;
  }
  if (!rejectedWideMask)
    return 5;

  aethercore::l32sim::initialize(top, memory);
  top.reset = 0;
  (void)aethercore::l32sim::step(top, context, memory, false, 0);
  return 0;
}
