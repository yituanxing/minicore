#include "nemu_difftest.h"

#include <cstdint>
#include <exception>
#include <iostream>
#include <string>

namespace {
constexpr std::uint64_t kRamBase = 0x80000000ULL;
constexpr std::uint64_t kRamSize = 64ULL * 1024ULL * 1024ULL;
constexpr std::uint32_t kAddiX1X0Seven = 0x00700093U;
}  // namespace

int main(int argc, char** argv) {
  if (argc != 3) {
    std::cerr << "usage: nemu_difftest_mismatch_probe <NEMU_SO> <forwarding.bin>\n";
    return 64;
  }

  try {
    NemuDifftest difftest(argv[1], argv[2], kRamBase, kRamSize);

    DifftestCommit perturbed{};
    perturbed.pc = kRamBase;
    perturbed.inst = kAddiX1X0Seven;
    perturbed.rawInst = perturbed.inst;
    perturbed.rd = 1;
    perturbed.rdWrite = true;
    perturbed.rdData = 6;  // The architectural ADDI result is 7. Intentionally unequal.

    try {
      difftest.check(perturbed);
    } catch (const std::exception& error) {
      const std::string message = error.what();
      const bool failedAtFirstCommit =
          message.find("DiffTest mismatch after 0 matched commits") != std::string::npos;
      const bool identifiedX1 =
          message.find("after reference execution: x1") != std::string::npos;

      if (!failedAtFirstCommit || !identifiedX1 || difftest.checkedCommits() != 0) {
        std::cerr << "FAIL: DiffTest rejected the perturbed commit for an unexpected reason:\n"
                  << message << '\n';
        return 2;
      }

      std::cout << "PASS: deliberate first-commit x1 mismatch was detected\n"
                << message << '\n';
      return 0;
    }

    std::cerr << "FAIL: intentionally unequal retirement unexpectedly matched NEMU\n";
    return 3;
  } catch (const std::exception& error) {
    std::cerr << "FAIL: mismatch probe setup failed: " << error.what() << '\n';
    return 1;
  }
}
