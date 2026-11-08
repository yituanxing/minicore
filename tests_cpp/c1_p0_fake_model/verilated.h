#pragma once

// Compile-only P0 transport ABI stub. NEVER use to qualify RTL execution.
#include <cstdint>

class VerilatedContext {
 public:
  void timeInc(std::uint64_t) {}
};
