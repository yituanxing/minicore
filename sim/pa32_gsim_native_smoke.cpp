// Execution smoke: GSIM-generated full PA32 virtual FPGA, not a mock model.
// No firmware is injected.  This checks actual clock, reset and timebase state.
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

  auto clockCycle = [&]() {
    ports.clock(false);
    dut->step(); // falling/settle phase
    ports.clock(true);
    dut->step(); // rising/architectural edge
  };
  ports.reset(true);
  for (int i = 0; i < 32; ++i) clockCycle();
  const auto before = ports.mtime();
  ports.reset(false);
  std::uint64_t retired = 0;
  for (int i = 0; i < 256; ++i) {
    clockCycle();
    if (ports.commit().valid) ++retired;
  }
  const auto after = ports.mtime();
  if (dut->cycles != 576 || after <= before) {
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
