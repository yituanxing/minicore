#include "VAetherCoreOpenSbiRV64SimTop.h"
#include "verilated.h"

#include "l32_opensbi_runtime.h"

#include <cstddef>
#include <cstdint>
#include <type_traits>

int main(int argc, char** argv) {
  VerilatedContext context;
  context.commandArgs(argc, argv);
  VAetherCoreOpenSbiRV64SimTop top{&context};
  aethercore::l32sim::Memory memory;

  static_assert(sizeof(top.io_memRdata) == 8,
                "RV64 OpenSBI data response must be 64 bits");
  static_assert(sizeof(top.io_ptwRdata) == 8,
                "Sv39 OpenSBI PTE response must be 64 bits");
  static_assert(aethercore::l32sim::dataBytesFromMemSize(0) == 1);
  static_assert(aethercore::l32sim::dataBytesFromMemSize(1) == 2);
  static_assert(aethercore::l32sim::dataBytesFromMemSize(2) == 4);
  static_assert(aethercore::l32sim::dataBytesFromMemSize(3) == 8);

  aethercore::l32sim::initialize(top, memory);
  top.reset = 0;
  (void)aethercore::l32sim::step(top, context, memory, false, 0);
  return 0;
}
