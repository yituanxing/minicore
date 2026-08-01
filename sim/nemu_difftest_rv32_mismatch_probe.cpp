#include "nemu_difftest_rv32.h"

#include <cstdint>
#include <exception>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
constexpr std::uint64_t kRamBase = 0x80000000ULL;
constexpr std::uint64_t kRamSize = 64ULL * 1024ULL * 1024ULL;

std::uint32_t readFirstInstruction(const std::string& imagePath) {
  std::ifstream input(imagePath, std::ios::binary);
  if (!input) throw std::runtime_error("cannot open RV32 mismatch image");
  std::uint8_t bytes[4]{};
  input.read(reinterpret_cast<char*>(bytes), sizeof(bytes));
  if (input.gcount() != static_cast<std::streamsize>(sizeof(bytes))) {
    throw std::runtime_error("RV32 mismatch image is shorter than one instruction");
  }
  return std::uint32_t(bytes[0]) |
         (std::uint32_t(bytes[1]) << 8) |
         (std::uint32_t(bytes[2]) << 16) |
         (std::uint32_t(bytes[3]) << 24);
}
}  // namespace

int main(int argc, char** argv) {
  if (argc != 3) {
    std::cerr << "usage: nemu_difftest_rv32_mismatch_probe <NEMU_SO> <rv32.bin>\n";
    return 64;
  }

  try {
    NemuDifftest difftest(argv[1], argv[2], kRamBase, kRamSize);

    DifftestCommit perturbed{};
    perturbed.pc = kRamBase;
    perturbed.inst = readFirstInstruction(argv[2]);
    perturbed.rd = 1;
    perturbed.rdWrite = true;
    perturbed.rdData = 1;  // The real first startup instruction does not write x1.

    try {
      difftest.check(perturbed);
    } catch (const std::exception& error) {
      const std::string message = error.what();
      const bool failedAtFirstCommit =
          message.find("RV32 DiffTest mismatch after 0 matched commits") != std::string::npos;
      const bool identifiedX1 =
          message.find("after reference execution: x1") != std::string::npos;

      if (!failedAtFirstCommit || !identifiedX1 || difftest.checkedCommits() != 0) {
        std::cerr << "FAIL: RV32 DiffTest rejected the perturbed commit for an unexpected reason:\n"
                  << message << '\n';
        return 2;
      }

      std::cout << "PASS: deliberate first-commit RV32 x1 mismatch was detected\n"
                << message << '\n';
      return 0;
    }

    std::cerr << "FAIL: intentionally unequal RV32 retirement unexpectedly matched NEMU\n";
    return 3;
  } catch (const std::exception& error) {
    std::cerr << "FAIL: RV32 mismatch probe setup failed: " << error.what() << '\n';
    return 1;
  }
}
