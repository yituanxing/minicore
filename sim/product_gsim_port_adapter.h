#pragma once

// GSIM exposes accessors rather than Verilator's public flattened data members.
// The instantiated model is *Product G3*, not historical V2/PA32. Keep every
// physical address as uint64_t: Product's host aperture uses PA=56.
#include "AetherCoreProductOpenSbiRV64SimTop.h"

#include <cstdint>

namespace aethercore::product_gsim {
using Model = SAetherCoreProductOpenSbiRV64SimTop;

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
    return {
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
