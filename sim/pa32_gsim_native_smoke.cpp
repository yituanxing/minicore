// Native GSIM execution smoke. Does not claim firmware or UART correctness.
// PA32 firmware/host-memory protocol comes in a separate differential runner.
#include <cstdio>
#include <cstdint>
#include <memory>
#include "AetherCoreV2OpenSbiRV64SimTop.h"

int main() {
  using Model = SAetherCoreV2OpenSbiRV64SimTop;
  auto dut = std::make_unique<Model>();
  dut->set_reset(1);
  for (int i = 0; i < 32; ++i) dut->step();
  dut->set_reset(0);
  for (int i = 0; i < 256; ++i) dut->step();
  if (dut->cycles != 288) {
    std::fprintf(stderr, "AETHERCORE_PA32_GSIM_NATIVE_SMOKE_FAIL cycles=%llu\n",
                 static_cast<unsigned long long>(dut->cycles));
    return 2;
  }
  std::printf("AETHERCORE_PA32_GSIM_NATIVE_SMOKE_PASS cycles=%llu\n",
              static_cast<unsigned long long>(dut->cycles));
  return 0;
}
