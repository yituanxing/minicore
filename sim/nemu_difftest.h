#pragma once

#include <cstdint>
#include <memory>
#include <string>

struct DifftestCommit {
  std::uint64_t pc = 0;
  std::uint32_t inst = 0;
  std::uint8_t rd = 0;
  bool rdWrite = false;
  std::uint64_t rdData = 0;

  bool memValid = false;
  bool memWrite = false;
  std::uint64_t memAddr = 0;
  std::uint64_t memWdata = 0;
  std::uint8_t memWmask = 0;

  bool exception = false;
  std::uint64_t exceptionCause = 0;
  std::uint64_t exceptionValue = 0;

  // An interrupt is taken after this normal instruction retires. interruptPc
  // is the oldest younger instruction that must be replayed after MRET.
  bool interrupt = false;
  std::uint64_t interruptCause = 0;
  std::uint64_t interruptPc = 0;
};

class NemuDifftest {
 public:
  NemuDifftest(const std::string& sharedObject, const std::string& imagePath,
               std::uint64_t resetPc, std::uint64_t ramSize);
  ~NemuDifftest();

  NemuDifftest(const NemuDifftest&) = delete;
  NemuDifftest& operator=(const NemuDifftest&) = delete;
  NemuDifftest(NemuDifftest&&) noexcept;
  NemuDifftest& operator=(NemuDifftest&&) noexcept;

  void check(const DifftestCommit& commit);
  std::uint64_t checkedCommits() const;
  std::uint64_t zicsrShadowSteps() const;
  std::uint64_t trapShadowSteps() const;
  std::uint64_t mretShadowSteps() const;
  std::uint64_t interruptShadowSteps() const;

 private:
  class Impl;
  std::unique_ptr<Impl> impl_;
};
