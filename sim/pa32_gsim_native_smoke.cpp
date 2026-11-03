// Execution smoke: GSIM-generated full PA32 virtual FPGA, not a mock model.
// No firmware is injected. One GSIM step must equal one architectural tick.
#include <cstdio>
#include <cstdint>
#include <memory>
#include "pa32_gsim_port_adapter.h"

int main() {
  using aethercore::gsim::Model;
  auto dut = std::make_unique<Model>();
  aethercore::gsim::Ports ports(*dut);
  ports.respond({});
  ports.rx(false, 0);

  // GSIM step() is an entire abstract hardware tick, NOT Verilator eval().
  // In the prior experiment two step() calls per alleged cycle advanced
  // the 10 MHz timebase ~248 times in only 256 x 20 MHz cycles.
  ports.clock(true);
  ports.reset(true);
  for (int i = 0; i < 32; ++i) dut->step();
  const auto before = ports.mtime();
  ports.reset(false);
  std::uint64_t retired = 0;
  for (int i = 0; i < 256; ++i) {
    dut->step();
    if (ports.commit().valid) ++retired;
  }
  const auto after = ports.mtime();
  const auto tickDelta = after - before;
  // The board is 20 MHz and its timebase is 10 MHz. Allow the 16-cycle
  // board reset stretcher plus one rounding tick.
  if (dut->cycles != 288 || tickDelta < 118 || tickDelta > 122) {
    std::fprintf(stderr,
                 "AETHERCORE_PA32_GSIM_NATIVE_SMOKE_FAIL steps=%llu mtime_before=%llu mtime_after=%llu\n",
                 static_cast<unsigned long long>(dut->cycles),
                 static_cast<unsigned long long>(before),
                 static_cast<unsigned long long>(after));
    return 2;
  }
  std::printf("AETHERCORE_PA32_GSIM_NATIVE_SMOKE_PASS cycles=288 steps=%llu mtime_before=%llu mtime_after=%llu retired=%llu\n",
              static_cast<unsigned long long>(dut->cycles),
              static_cast<unsigned long long>(before),
              static_cast<unsigned long long>(after),
              static_cast<unsigned long long>(retired));
  return 0;
}
