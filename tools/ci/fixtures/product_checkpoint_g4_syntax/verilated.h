#pragma once

// C++ *syntax-only* test double. Never use for real Product simulation.
#include <cstdint>
class VerilatedContext {
 public:
  void commandArgs(int, char**) {}
  std::uint64_t time() const { return time_; }
  void timeInc(std::uint64_t n) { time_ += n; }
 private:
  std::uint64_t time_ = 0;
};
