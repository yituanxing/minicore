#pragma once

// GSIM exposes accessors rather than Verilator's public flattened data members.
// The default model remains the independently qualified G3 IMAC Top. The
// explicit Debian build instantiates G5 RV64GC/LP64D with the same host RAM
// transport; SD1 is intentionally excluded until a separate card bridge is
// modelled. Both use PA=56 and uint64_t host addresses.
#ifdef AETHERCORE_PRODUCT_GSIM_DEBIAN
#include "AetherCoreProductDebianRV64SimTop.h"
#else
#include "AetherCoreProductOpenSbiRV64SimTop.h"
#endif

#include <cstdint>

namespace aethercore::product_gsim {
#ifdef AETHERCORE_PRODUCT_GSIM_DEBIAN
using Model = SAetherCoreProductDebianRV64SimTop;
#else
using Model = SAetherCoreProductOpenSbiRV64SimTop;
#endif

struct Request {
  bool imemValid;
  std::uint64_t imemAddr;
  std::uint8_t imemBytes;
  bool memValid;
  bool memWrite;
  bool memAtomic;
  std::uint8_t memOp;
  std::uint8_t memAtomicOp;
  std::uint64_t memAddr;
  std::uint64_t memWdata;
  std::uint8_t memWmask;
  std::uint8_t memSize;
  bool ptwValid;
  std::uint64_t ptwAddr;
};

struct Response {
  std::uint32_t imemInst = 0;
  bool imemFault = false;
  bool memReady = true;
  std::uint64_t memRdata = 0;
  bool memFault = false;
  bool ptwReady = true;
  std::uint64_t ptwRdata = 0;
  bool ptwFault = false;
};

struct Commit {
  bool valid;
  std::uint64_t pc;
  std::uint32_t inst;
  std::uint32_t rawInst;
  std::uint8_t instBytes;
  bool exception;
  std::uint64_t exceptionCause;
  std::uint64_t exceptionValue;
  bool interrupt;
  std::uint64_t interruptCause;
  std::uint64_t interruptPc;
#ifdef AETHERCORE_PRODUCT_GSIM_DEBIAN
  bool rdWrite;
  std::uint8_t rd;
  std::uint64_t rdData;
  bool fpRdWrite;
  std::uint8_t fpRd;
  std::uint64_t fpRdData;
  std::uint8_t fpFlags;
  bool fpStateDirty;
#endif
};

class Ports {
 public:
  explicit Ports(Model& model) : model_(model) {}

  void clock(bool high) { model_.set_clock(high ? 1 : 0); }
  void reset(bool active) { model_.set_reset(active ? 1 : 0); }
  void rx(bool valid, std::uint8_t byte) {
    model_.set_io$$rxValid(valid ? 1 : 0);
    model_.set_io$$rxByte(valid ? byte : 0);
  }

  Request request() const {
    return {
      static_cast<bool>(model_.get_io$$imemValid()),
      static_cast<std::uint64_t>(model_.get_io$$imemAddr()),
      static_cast<std::uint8_t>(model_.get_io$$imemBytes()),
      static_cast<bool>(model_.get_io$$memValid()),
      static_cast<bool>(model_.get_io$$memWrite()),
      static_cast<bool>(model_.get_io$$memAtomic()),
      static_cast<std::uint8_t>(model_.get_io$$memOp()),
      static_cast<std::uint8_t>(model_.get_io$$memAtomicOp()),
      static_cast<std::uint64_t>(model_.get_io$$memAddr()),
      static_cast<std::uint64_t>(model_.get_io$$memWdata()),
      static_cast<std::uint8_t>(model_.get_io$$memWmask()),
      static_cast<std::uint8_t>(model_.get_io$$memSize()),
      static_cast<bool>(model_.get_io$$ptwValid()),
      static_cast<std::uint64_t>(model_.get_io$$ptwAddr()),
    };
  }

  // GSIM G4 unqualified-only transport: read three validity bits first, then
  // only the addresses and payloads consumed by the real host RAM callback.
  // No host wait state, ready/valid, fault or store acceptance is changed.
  // Strict G3 same-cycle qualification keeps request() above verbatim.
  Request requestFastUnqualified() const {
    Request result{};
    result.imemValid = static_cast<bool>(model_.get_io$$imemValid());
    result.memValid = static_cast<bool>(model_.get_io$$memValid());
    result.ptwValid = static_cast<bool>(model_.get_io$$ptwValid());
    if (result.imemValid) {
      result.imemAddr = static_cast<std::uint64_t>(model_.get_io$$imemAddr());
      result.imemBytes = static_cast<std::uint8_t>(model_.get_io$$imemBytes());
    }
    if (result.memValid) {
      result.memWrite = static_cast<bool>(model_.get_io$$memWrite());
      result.memAtomic = static_cast<bool>(model_.get_io$$memAtomic());
      result.memAddr = static_cast<std::uint64_t>(model_.get_io$$memAddr());
      result.memSize = static_cast<std::uint8_t>(model_.get_io$$memSize());
      // memOp is never consumed by readResponses/commitAcceptedMemory;
      // atomicOp and write data matter only for their corresponding requests.
      if (result.memAtomic) {
        result.memAtomicOp = static_cast<std::uint8_t>(model_.get_io$$memAtomicOp());
      }
      if (result.memWrite || result.memAtomic) {
        result.memWdata = static_cast<std::uint64_t>(model_.get_io$$memWdata());
        result.memWmask = static_cast<std::uint8_t>(model_.get_io$$memWmask());
      }
    }
    if (result.ptwValid) {
      result.ptwAddr = static_cast<std::uint64_t>(model_.get_io$$ptwAddr());
    }
    return result;
  }

  void respond(const Response& response) {
    model_.set_io$$imemInst(response.imemInst);
    model_.set_io$$imemFault(response.imemFault ? 1 : 0);
    model_.set_io$$memReady(response.memReady ? 1 : 0);
    model_.set_io$$memRdata(response.memRdata);
    model_.set_io$$memFault(response.memFault ? 1 : 0);
    model_.set_io$$ptwReady(response.ptwReady ? 1 : 0);
    model_.set_io$$ptwRdata(response.ptwRdata);
    model_.set_io$$ptwFault(response.ptwFault ? 1 : 0);
  }

  Commit commit() const {
    Commit result{
      static_cast<bool>(model_.get_io$$commit$$valid()),
      static_cast<std::uint64_t>(model_.get_io$$commit$$pc()),
      static_cast<std::uint32_t>(model_.get_io$$commit$$inst()),
      static_cast<std::uint32_t>(model_.get_io$$commit$$rawInst()),
      static_cast<std::uint8_t>(model_.get_io$$commit$$instBytes()),
      static_cast<bool>(model_.get_io$$commit$$exception()),
      static_cast<std::uint64_t>(model_.get_io$$commit$$exceptionCause()),
      static_cast<std::uint64_t>(model_.get_io$$commit$$exceptionValue()),
      static_cast<bool>(model_.get_io$$commit$$interrupt()),
      static_cast<std::uint64_t>(model_.get_io$$commit$$interruptCause()),
      static_cast<std::uint64_t>(model_.get_io$$commit$$interruptPc()),
    };
#ifdef AETHERCORE_PRODUCT_GSIM_DEBIAN
    result.rdWrite = static_cast<bool>(model_.get_io$$commit$$rdWrite());
    result.rd = static_cast<std::uint8_t>(model_.get_io$$commit$$rd());
    result.rdData = static_cast<std::uint64_t>(model_.get_io$$commit$$rdData());
    result.fpRdWrite = static_cast<bool>(model_.get_io$$commit$$floating$$rdWrite());
    result.fpRd = static_cast<std::uint8_t>(model_.get_io$$commit$$floating$$rd());
    result.fpRdData = static_cast<std::uint64_t>(model_.get_io$$commit$$floating$$rdData());
    result.fpFlags = static_cast<std::uint8_t>(model_.get_io$$commit$$floating$$fflags());
    result.fpStateDirty = static_cast<bool>(model_.get_io$$commit$$floating$$stateDirty());
#endif
    return result;
  }

  // The bounded Linux-prefix *non-differential* runner only needs the
  // architectural commit/exception/interrupt counters and recent PCs.
  // Read sparse payloads only when valid. Strict G3 same-cycle differential
  // continues to use commit() above without changing its observable fields.
  Commit commitFastUnqualified() const {
    Commit result{};
    result.valid = static_cast<bool>(model_.get_io$$commit$$valid());
    result.interrupt = static_cast<bool>(model_.get_io$$commit$$interrupt());
    if (result.valid) {
      result.pc = static_cast<std::uint64_t>(model_.get_io$$commit$$pc());
      result.inst = static_cast<std::uint32_t>(model_.get_io$$commit$$inst());
      result.instBytes = static_cast<std::uint8_t>(model_.get_io$$commit$$instBytes());
      result.exception = static_cast<bool>(model_.get_io$$commit$$exception());
      if (result.exception) {
        result.exceptionCause = static_cast<std::uint64_t>(model_.get_io$$commit$$exceptionCause());
        result.exceptionValue = static_cast<std::uint64_t>(model_.get_io$$commit$$exceptionValue());
      }
    }
    if (result.interrupt) {
      result.interruptCause = static_cast<std::uint64_t>(model_.get_io$$commit$$interruptCause());
    }
    return result;
  }

  std::uint64_t mtime() const { return model_.get_io$$mtime(); }
  std::uint64_t mtimecmp() const { return model_.get_io$$mtimecmp(); }
  bool timerInterrupt() const { return model_.get_io$$timerInterrupt(); }
  bool supervisorExternalInterrupt() const {
    return model_.get_io$$supervisorExternalInterrupt();
  }
  bool uartInterrupt() const { return model_.get_io$$uartInterrupt(); }
  bool uartRxInterrupt() const { return model_.get_io$$uartRxInterrupt(); }
  bool uartValid() const { return model_.get_io$$uartValid(); }
  std::uint8_t uartByte() const { return model_.get_io$$uartByte(); }
  bool rxReady() const { return model_.get_io$$rxReady(); }
  bool exitValid() const { return model_.get_io$$exitValid(); }
  std::uint64_t exitCode() const { return model_.get_io$$exitCode(); }
  bool halted() const { return model_.get_io$$halted(); }

 private:
  Model& model_;
};
}  // namespace aethercore::product_gsim
