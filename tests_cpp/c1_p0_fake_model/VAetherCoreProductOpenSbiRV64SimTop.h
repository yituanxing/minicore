#pragma once

// Compile-only fake of *host-visible ports*.  This does not implement a CPU,
// run RTL, or produce a PASS; CI compiles the actual top separately.
#include "verilated.h"

#include <cstdint>

struct VAetherCoreProductOpenSbiRV64SimTop {
  explicit VAetherCoreProductOpenSbiRV64SimTop(VerilatedContext*) {}
  void eval() {}

  std::uint8_t reset = 0, clock = 0;
  std::uint8_t io_rxValid = 0, io_rxByte = 0;
  std::uint8_t io_imemValid = 0, io_imemBytes = 0, io_imemFault = 0;
  std::uint64_t io_imemAddr = 0;
  std::uint32_t io_imemInst = 0;
  std::uint8_t io_memValid = 0, io_memWrite = 0, io_memAtomic = 0;
  std::uint8_t io_memSize = 0, io_memReady = 0, io_memFault = 0;
  std::uint64_t io_memAddr = 0, io_memWdata = 0, io_memWmask = 0;
  std::uint64_t io_memRdata = 0;
  std::uint8_t io_memAtomicOp = 0;
  std::uint8_t io_ptwValid = 0, io_ptwReady = 0, io_ptwFault = 0;
  std::uint64_t io_ptwAddr = 0, io_ptwRdata = 0;
  std::uint8_t io_uartValid = 0, io_exitValid = 0;
  std::uint8_t io_timerInterrupt = 0, io_supervisorExternalInterrupt = 0;
  std::uint8_t io_uartInterrupt = 0, io_uartRxInterrupt = 0;
  std::uint8_t io_commit_valid = 0, io_commit_exception = 0;
  std::uint8_t io_commit_interrupt = 0, io_commit_instBytes = 0;
  std::uint64_t io_commit_pc = 0;
  std::uint32_t io_commit_inst = 0, io_commit_rawInst = 0;
};
