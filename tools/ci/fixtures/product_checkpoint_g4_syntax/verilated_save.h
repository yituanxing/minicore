#pragma once

// Checks host API usage only; actual --savable serialization is proven by CI.
class VerilatedSave {
 public:
  void open(const char*) {}
  template <typename Top> VerilatedSave& operator<<(Top&) { return *this; }
  void close() {}
};
class VerilatedRestore {
 public:
  void open(const char*) {}
  template <typename Top> VerilatedRestore& operator>>(Top&) { return *this; }
  void close() {}
};
