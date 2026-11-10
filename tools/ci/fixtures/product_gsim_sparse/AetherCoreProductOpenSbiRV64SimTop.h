#pragma once

// Host-only stand-in for the GSIM generated accessor API. Never used to
// generate or simulate Product RTL: only compiles the sparse-port selftest.
#include <array>
#include <cstddef>
#include <cstdint>

struct SAetherCoreProductOpenSbiRV64SimTop {
  enum RequestPort : std::size_t {
    imemValidPort, imemAddrPort, imemBytesPort,
    memValidPort, memWritePort, memAtomicPort, memOpPort,
    memAtomicOpPort, memAddrPort, memWdataPort, memWmaskPort,
    memSizePort, ptwValidPort, ptwAddrPort, requestPortCount,
  };

  mutable std::array<std::uint64_t, requestPortCount> reads{};
  std::uint64_t imemValid=0, imemAddr=0, imemBytes=0;
  std::uint64_t memValid=0, memWrite=0, memAtomic=0, memOp=0;
  std::uint64_t memAtomicOp=0, memAddr=0, memWdata=0, memWmask=0, memSize=0;
  std::uint64_t ptwValid=0, ptwAddr=0;

#define GSIM_TEST_REQUEST_GETTER(NAME) \
  std::uint64_t get_io$$##NAME() const { ++reads[NAME##Port]; return NAME; }
  GSIM_TEST_REQUEST_GETTER(imemValid)
  GSIM_TEST_REQUEST_GETTER(imemAddr)
  GSIM_TEST_REQUEST_GETTER(imemBytes)
  GSIM_TEST_REQUEST_GETTER(memValid)
  GSIM_TEST_REQUEST_GETTER(memWrite)
  GSIM_TEST_REQUEST_GETTER(memAtomic)
  GSIM_TEST_REQUEST_GETTER(memOp)
  GSIM_TEST_REQUEST_GETTER(memAtomicOp)
  GSIM_TEST_REQUEST_GETTER(memAddr)
  GSIM_TEST_REQUEST_GETTER(memWdata)
  GSIM_TEST_REQUEST_GETTER(memWmask)
  GSIM_TEST_REQUEST_GETTER(memSize)
  GSIM_TEST_REQUEST_GETTER(ptwValid)
  GSIM_TEST_REQUEST_GETTER(ptwAddr)
#undef GSIM_TEST_REQUEST_GETTER

#define GSIM_TEST_OTHER_GETTER(NAME) \
  std::uint64_t get_io$$##NAME() const { return 0; }
  GSIM_TEST_OTHER_GETTER(commit$$valid)
  GSIM_TEST_OTHER_GETTER(commit$$pc)
  GSIM_TEST_OTHER_GETTER(commit$$inst)
  GSIM_TEST_OTHER_GETTER(commit$$rawInst)
  GSIM_TEST_OTHER_GETTER(commit$$instBytes)
  GSIM_TEST_OTHER_GETTER(commit$$exception)
  GSIM_TEST_OTHER_GETTER(commit$$exceptionCause)
  GSIM_TEST_OTHER_GETTER(commit$$exceptionValue)
  GSIM_TEST_OTHER_GETTER(commit$$interrupt)
  GSIM_TEST_OTHER_GETTER(commit$$interruptCause)
  GSIM_TEST_OTHER_GETTER(commit$$interruptPc)
  GSIM_TEST_OTHER_GETTER(mtime)
  GSIM_TEST_OTHER_GETTER(mtimecmp)
  GSIM_TEST_OTHER_GETTER(timerInterrupt)
  GSIM_TEST_OTHER_GETTER(supervisorExternalInterrupt)
  GSIM_TEST_OTHER_GETTER(uartInterrupt)
  GSIM_TEST_OTHER_GETTER(uartRxInterrupt)
  GSIM_TEST_OTHER_GETTER(uartValid)
  GSIM_TEST_OTHER_GETTER(uartByte)
  GSIM_TEST_OTHER_GETTER(rxReady)
  GSIM_TEST_OTHER_GETTER(exitValid)
  GSIM_TEST_OTHER_GETTER(exitCode)
  GSIM_TEST_OTHER_GETTER(halted)
#undef GSIM_TEST_OTHER_GETTER

#define GSIM_TEST_SETTER(NAME) \
  void set_io$$##NAME(std::uint64_t) {}
  GSIM_TEST_SETTER(imemInst)
  GSIM_TEST_SETTER(imemFault)
  GSIM_TEST_SETTER(memReady)
  GSIM_TEST_SETTER(memRdata)
  GSIM_TEST_SETTER(memFault)
  GSIM_TEST_SETTER(ptwReady)
  GSIM_TEST_SETTER(ptwRdata)
  GSIM_TEST_SETTER(ptwFault)
  GSIM_TEST_SETTER(rxValid)
  GSIM_TEST_SETTER(rxByte)
#undef GSIM_TEST_SETTER
  void set_clock(std::uint64_t) {}
  void set_reset(std::uint64_t) {}
};
