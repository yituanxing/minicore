#pragma once
// GSIM-generated PA32 top ABI observed in CI 41562232548.
// The GSIM module name and IO boundary are the exact same Chisel design as
// the canonical Verilator PA32 virtual FPGA; never infer ports at runtime.
#include "AetherCoreV2OpenSbiRV64SimTop.h"
#include <cstdint>

namespace aethercore::gsim {
using Model = SAetherCoreV2OpenSbiRV64SimTop;

struct Request {
  bool imemValid;
  std::uint32_t imemAddr;
  std::uint8_t imemBytes;
  bool memValid;
  bool memWrite;
  bool memAtomic;
  std::uint8_t memAtomicOp;
  std::uint32_t memAddr;
  std::uint64_t memWdata;
  std::uint8_t memWmask;
  std::uint8_t memSize;
  bool ptwValid;
  std::uint32_t ptwAddr;
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
  bool exception;
  std::uint64_t exceptionCause;
  bool interrupt;
  std::uint64_t interruptCause;
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

  Request request() {
    return {
      static_cast<bool>(model_.get_io$$imemValid()),
      model_.get_io$$imemAddr(),
      model_.get_io$$imemBytes(),
      static_cast<bool>(model_.get_io$$memValid()),
      static_cast<bool>(model_.get_io$$memWrite()),
      static_cast<bool>(model_.get_io$$memAtomic()),
      model_.get_io$$memAtomicOp(),
      model_.get_io$$memAddr(),
      model_.get_io$$memWdata(),
      model_.get_io$$memWmask(),
      model_.get_io$$memSize(),
      static_cast<bool>(model_.get_io$$ptwValid()),
      model_.get_io$$ptwAddr(),
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

  Commit commit() {
    return {
      static_cast<bool>(model_.get_io$$commit$$valid()),
      model_.get_io$$commit$$pc(),
      model_.get_io$$commit$$inst(),
      static_cast<bool>(model_.get_io$$commit$$exception()),
      model_.get_io$$commit$$exceptionCause(),
      static_cast<bool>(model_.get_io$$commit$$interrupt()),
      model_.get_io$$commit$$interruptCause(),
    };
  }

  std::uint64_t mtime() { return model_.get_io$$mtime(); }
  std::uint64_t mtimecmp() { return model_.get_io$$mtimecmp(); }
  bool uartValid() { return model_.get_io$$uartValid(); }
  std::uint8_t uartByte() { return model_.get_io$$uartByte(); }
  bool boardResetActive() { return model_.get_io$$boardResetActive(); }
  bool exitValid() { return model_.get_io$$exitValid(); }
  std::uint64_t exitCode() { return model_.get_io$$exitCode(); }

 private:
  Model& model_;
};
} // namespace aethercore::gsim
