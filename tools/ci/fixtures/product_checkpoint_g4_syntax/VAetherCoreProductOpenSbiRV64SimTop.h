#pragma once

// Synthetic Product port types exclusively for -fsyntax-only G4 C++ checks.
// Does not execute RTL or certify an actual Verilator-generated interface.
#include "verilated.h"
#include <cstdint>

class VAetherCoreProductOpenSbiRV64SimTop {
 public:
  explicit VAetherCoreProductOpenSbiRV64SimTop(VerilatedContext*) {}
  void eval() {}
  std::uint8_t reset = 0, clock = 0;
  std::uint8_t io_rxValid = 0, io_rxByte = 0, io_rxReady = 0;
  std::uint8_t io_imemValid = 0, io_imemBytes = 0, io_imemFault = 0;
  std::uint64_t io_imemAddr = 0;
  std::uint32_t io_imemInst = 0;
  std::uint8_t io_memValid = 0, io_memReady = 0, io_memFault = 0;
  std::uint8_t io_memWrite = 0, io_memAtomic = 0, io_memAtomicOp = 0;
  std::uint8_t io_memSize = 0;
  std::uint64_t io_memAddr = 0, io_memWdata = 0, io_memWmask = 0, io_memRdata = 0;
  std::uint8_t io_ptwValid = 0, io_ptwReady = 0, io_ptwFault = 0;
  std::uint64_t io_ptwAddr = 0, io_ptwRdata = 0;
  std::uint8_t io_commit_valid = 0, io_commit_instBytes = 0;
  std::uint8_t io_commit_exception = 0, io_commit_interrupt = 0;
  std::uint64_t io_commit_pc = 0, io_commit_exceptionCause = 0;
  std::uint64_t io_commit_exceptionValue = 0, io_commit_interruptCause = 0;
  std::uint64_t io_commit_interruptPc = 0;
  std::uint32_t io_commit_rawInst = 0;
  std::uint8_t io_uartValid = 0, io_uartByte = 0, io_uartRxInterrupt = 0;
  std::uint8_t io_uartInterrupt = 0, io_supervisorExternalInterrupt = 0;
  std::uint8_t io_timerInterrupt = 0, io_exitValid = 0, io_exitCode = 0;
  std::uint64_t io_mtime = 0, io_mtimecmp = 0;
};
