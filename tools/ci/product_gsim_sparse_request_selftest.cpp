#include "product_gsim_port_adapter.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <initializer_list>

using Mock = SAetherCoreProductOpenSbiRV64SimTop;
using aethercore::product_gsim::Ports;
using aethercore::product_gsim::Request;

static bool sameConsumedFields(const Request& strict, const Request& fast) {
  if (strict.imemValid != fast.imemValid ||
      strict.memValid != fast.memValid || strict.ptwValid != fast.ptwValid)
    return false;
  if (strict.imemValid && (strict.imemAddr != fast.imemAddr ||
                           strict.imemBytes != fast.imemBytes)) return false;
  if (strict.memValid) {
    if (strict.memWrite != fast.memWrite || strict.memAtomic != fast.memAtomic ||
        strict.memAddr != fast.memAddr || strict.memSize != fast.memSize)
      return false;
    if (strict.memAtomic && strict.memAtomicOp != fast.memAtomicOp) return false;
    if ((strict.memWrite || strict.memAtomic) &&
        (strict.memWdata != fast.memWdata || strict.memWmask != fast.memWmask))
      return false;
  }
  return !strict.ptwValid || strict.ptwAddr == fast.ptwAddr;
}

static void probe(const char* name, Mock& model,
                  std::initializer_list<Mock::RequestPort> expectedFastReads) {
  Ports ports(model);
  model.reads.fill(0);
  const auto strict = ports.request();
  for (std::uint64_t count : model.reads) assert(count == 1);
  model.reads.fill(0);

  const auto fast = ports.requestFastUnqualified();
  if (!sameConsumedFields(strict, fast)) {
    std::fprintf(stderr, "GSIM_SPARSE_REQUEST_SELFTEST_FAIL consumed state %s\n", name);
    std::abort();
  }
  std::array<std::uint64_t, Mock::requestPortCount> expected{};
  for (auto port : expectedFastReads) expected[port] = 1;
  if (model.reads != expected) {
    std::fprintf(stderr, "GSIM_SPARSE_REQUEST_SELFTEST_FAIL getter count %s\n", name);
    std::abort();
  }
  std::printf("GSIM_SPARSE_REQUEST_CASE_PASS case=%s sparse_getters=%zu strict_getters=%zu\n",
              name, expectedFastReads.size(), model.reads.size());
}

int main() {
  using enum Mock::RequestPort;
  Mock model;
  model.imemAddr = 0x80000100; model.imemBytes = 4;
  model.memAddr = 0x80001000; model.memSize = 3;
  model.memWdata = 0x1234567812345678ULL; model.memWmask = 0xff;
  model.memAtomicOp = 5; model.memOp = 13; model.ptwAddr = 0x80002000;

  probe("no_request", model, {imemValidPort, memValidPort, ptwValidPort});
  model.imemValid = 1;
  probe("instruction_only", model, {imemValidPort, memValidPort, ptwValidPort,
                                    imemAddrPort, imemBytesPort});
  model.imemValid = 0; model.memValid = 1;
  probe("read_only", model, {imemValidPort, memValidPort, ptwValidPort,
                             memWritePort, memAtomicPort, memAddrPort, memSizePort});
  model.memWrite = 1;
  probe("ordinary_write", model, {imemValidPort, memValidPort, ptwValidPort,
                                  memWritePort, memAtomicPort, memAddrPort, memSizePort,
                                  memWdataPort, memWmaskPort});
  model.memWrite = 0; model.memAtomic = 1;
  probe("atomic_write", model, {imemValidPort, memValidPort, ptwValidPort,
                                memWritePort, memAtomicPort, memAddrPort, memSizePort,
                                memAtomicOpPort, memWdataPort, memWmaskPort});
  model.memValid = 0; model.memAtomic = 0; model.ptwValid = 1;
  probe("page_walk_only", model, {imemValidPort, memValidPort, ptwValidPort,
                                  ptwAddrPort});
  model.imemValid = 1; model.memValid = 1; model.memAtomic = 1;
  probe("all_active_atomic", model, {imemValidPort, memValidPort, ptwValidPort,
                                     imemAddrPort, imemBytesPort, memWritePort,
                                     memAtomicPort, memAddrPort, memSizePort,
                                     memAtomicOpPort, memWdataPort, memWmaskPort,
                                     ptwAddrPort});
  std::puts("PRODUCT_GSIM_SPARSE_REQUEST_SELFTEST_PASS strict_accessors_unchanged=14 fast_idle_accessors=3 cases=7 host_only=1 no_rtl=1");
}
