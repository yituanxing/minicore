#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {
constexpr std::uint32_t kRamBase = 0x80000000U;
constexpr std::size_t kRamSize = 64ULL * 1024ULL * 1024ULL;
constexpr std::uint32_t kMtimeAddress = 0x0200bff8U;

struct CpuState {
  std::uint32_t gpr[32]{};
  std::uint32_t pc = 0;
};

struct MemoryMap {
  std::uint32_t base = 0;
  std::uint32_t size = 0;
  std::uint8_t* space = nullptr;
};

CpuState cpu;
std::vector<std::uint8_t> ram(kRamSize);
std::vector<MemoryMap> maps;
std::vector<std::uint32_t> mtimeReplay;
std::size_t mtimeReplayIndex = 0;

[[noreturn]] void fail(const char* reason, std::uint32_t pc = 0, std::uint32_t value = 0) {
  std::fprintf(
      stderr,
      "RV32 reference shim failure: %s pc=0x%08x value=0x%08x\n",
      reason,
      pc,
      value);
  std::abort();
}

std::int32_t signExtend(std::uint32_t value, unsigned bits) {
  const std::uint32_t sign = 1U << (bits - 1);
  return static_cast<std::int32_t>((value ^ sign) - sign);
}

std::uint8_t* memoryPointer(std::uint32_t address, std::size_t size) {
  if (address >= kRamBase && std::uint64_t(address - kRamBase) + size <= kRamSize) {
    return ram.data() + (address - kRamBase);
  }
  for (auto& map : maps) {
    if (address >= map.base && std::uint64_t(address - map.base) + size <= map.size) {
      return map.space + (address - map.base);
    }
  }
  fail("unmapped memory access", cpu.pc, address);
}

std::uint8_t read8(std::uint32_t address) {
  return *memoryPointer(address, 1);
}

std::uint16_t read16(std::uint32_t address) {
  const auto* bytes = memoryPointer(address, 2);
  return std::uint16_t(bytes[0]) | (std::uint16_t(bytes[1]) << 8);
}

std::uint32_t read32(std::uint32_t address) {
  // The platform timer is an asynchronous input rather than CPU state. The
  // local gate records the DUT timer-read sequence once, then replays those
  // external values into this otherwise independent instruction reference.
  if (address == kMtimeAddress && !mtimeReplay.empty()) {
    if (mtimeReplayIndex >= mtimeReplay.size()) {
      fail("mtime replay exhausted", cpu.pc, address);
    }
    return mtimeReplay[mtimeReplayIndex++];
  }

  const auto* bytes = memoryPointer(address, 4);
  return std::uint32_t(bytes[0]) |
         (std::uint32_t(bytes[1]) << 8) |
         (std::uint32_t(bytes[2]) << 16) |
         (std::uint32_t(bytes[3]) << 24);
}

void write8(std::uint32_t address, std::uint8_t value) {
  *memoryPointer(address, 1) = value;
}

void write16(std::uint32_t address, std::uint16_t value) {
  auto* bytes = memoryPointer(address, 2);
  bytes[0] = value;
  bytes[1] = value >> 8;
}

void write32(std::uint32_t address, std::uint32_t value) {
  auto* bytes = memoryPointer(address, 4);
  bytes[0] = value;
  bytes[1] = value >> 8;
  bytes[2] = value >> 16;
  bytes[3] = value >> 24;
}

std::uint32_t multiplyHighSignedSigned(std::uint32_t lhs, std::uint32_t rhs) {
  const std::int64_t result =
      std::int64_t(std::int32_t(lhs)) * std::int64_t(std::int32_t(rhs));
  return std::uint64_t(result) >> 32;
}

std::uint32_t multiplyHighSignedUnsigned(std::uint32_t lhs, std::uint32_t rhs) {
  const std::int64_t result =
      std::int64_t(std::int32_t(lhs)) * std::int64_t(std::uint64_t(rhs));
  return std::uint64_t(result) >> 32;
}

std::uint32_t multiplyHighUnsignedUnsigned(std::uint32_t lhs, std::uint32_t rhs) {
  return (std::uint64_t(lhs) * std::uint64_t(rhs)) >> 32;
}

void executeOne() {
  const std::uint32_t pc = cpu.pc;
  const std::uint32_t instruction = read32(pc);
  const std::uint32_t opcode = instruction & 0x7fU;
  const std::uint32_t rd = (instruction >> 7) & 0x1fU;
  const std::uint32_t funct3 = (instruction >> 12) & 0x7U;
  const std::uint32_t rs1 = (instruction >> 15) & 0x1fU;
  const std::uint32_t rs2 = (instruction >> 20) & 0x1fU;
  const std::uint32_t funct7 = instruction >> 25;
  const std::uint32_t lhs = cpu.gpr[rs1];
  const std::uint32_t rhs = cpu.gpr[rs2];

  std::uint32_t nextPc = pc + 4;
  std::uint32_t result = 0;
  bool writeRd = false;

  switch (opcode) {
    case 0x37:  // LUI
      result = instruction & 0xfffff000U;
      writeRd = true;
      break;

    case 0x17:  // AUIPC
      result = pc + (instruction & 0xfffff000U);
      writeRd = true;
      break;

    case 0x6f: {  // JAL
      const std::uint32_t immediate =
          ((instruction >> 31) << 20) |
          (((instruction >> 12) & 0xffU) << 12) |
          (((instruction >> 20) & 1U) << 11) |
          (((instruction >> 21) & 0x3ffU) << 1);
      result = pc + 4;
      writeRd = true;
      nextPc = pc + signExtend(immediate, 21);
      break;
    }

    case 0x67:  // JALR
      if (funct3 != 0) fail("illegal JALR", pc, instruction);
      result = pc + 4;
      writeRd = true;
      nextPc = (lhs + std::uint32_t(signExtend(instruction >> 20, 12))) & ~1U;
      break;

    case 0x63: {  // BRANCH
      const std::uint32_t immediate =
          ((instruction >> 31) << 12) |
          (((instruction >> 7) & 1U) << 11) |
          (((instruction >> 25) & 0x3fU) << 5) |
          (((instruction >> 8) & 0xfU) << 1);
      bool taken = false;
      switch (funct3) {
        case 0: taken = lhs == rhs; break;
        case 1: taken = lhs != rhs; break;
        case 4: taken = std::int32_t(lhs) < std::int32_t(rhs); break;
        case 5: taken = std::int32_t(lhs) >= std::int32_t(rhs); break;
        case 6: taken = lhs < rhs; break;
        case 7: taken = lhs >= rhs; break;
        default: fail("illegal branch", pc, instruction);
      }
      if (taken) nextPc = pc + signExtend(immediate, 13);
      break;
    }

    case 0x03: {  // LOAD
      const std::uint32_t address =
          lhs + std::uint32_t(signExtend(instruction >> 20, 12));
      switch (funct3) {
        case 0: result = std::uint32_t(std::int32_t(std::int8_t(read8(address)))); break;
        case 1: result = std::uint32_t(std::int32_t(std::int16_t(read16(address)))); break;
        case 2: result = read32(address); break;
        case 4: result = read8(address); break;
        case 5: result = read16(address); break;
        default: fail("illegal load", pc, instruction);
      }
      writeRd = true;
      break;
    }

    case 0x23: {  // STORE
      const std::uint32_t immediate =
          ((instruction >> 25) << 5) | ((instruction >> 7) & 0x1fU);
      const std::uint32_t address = lhs + std::uint32_t(signExtend(immediate, 12));
      switch (funct3) {
        case 0: write8(address, rhs); break;
        case 1: write16(address, rhs); break;
        case 2: write32(address, rhs); break;
        default: fail("illegal store", pc, instruction);
      }
      break;
    }

    case 0x13: {  // OP-IMM
      const std::int32_t immediate = signExtend(instruction >> 20, 12);
      switch (funct3) {
        case 0: result = lhs + std::uint32_t(immediate); break;
        case 2: result = std::int32_t(lhs) < immediate; break;
        case 3: result = lhs < std::uint32_t(immediate); break;
        case 4: result = lhs ^ std::uint32_t(immediate); break;
        case 6: result = lhs | std::uint32_t(immediate); break;
        case 7: result = lhs & std::uint32_t(immediate); break;
        case 1:
          if ((instruction >> 25) != 0) fail("illegal SLLI", pc, instruction);
          result = lhs << ((instruction >> 20) & 31U);
          break;
        case 5:
          if ((instruction >> 25) == 0) {
            result = lhs >> ((instruction >> 20) & 31U);
          } else if ((instruction >> 25) == 0x20) {
            result = std::uint32_t(std::int32_t(lhs) >> ((instruction >> 20) & 31U));
          } else {
            fail("illegal shift immediate", pc, instruction);
          }
          break;
        default: fail("illegal OP-IMM", pc, instruction);
      }
      writeRd = true;
      break;
    }

    case 0x33: {  // OP and M
      if (funct7 == 0x01) {
        switch (funct3) {
          case 0: result = std::uint32_t(std::uint64_t(lhs) * std::uint64_t(rhs)); break;
          case 1: result = multiplyHighSignedSigned(lhs, rhs); break;
          case 2: result = multiplyHighSignedUnsigned(lhs, rhs); break;
          case 3: result = multiplyHighUnsignedUnsigned(lhs, rhs); break;
          case 4:
            if (rhs == 0) result = 0xffffffffU;
            else if (lhs == 0x80000000U && rhs == 0xffffffffU) result = lhs;
            else result = std::uint32_t(std::int32_t(lhs) / std::int32_t(rhs));
            break;
          case 5: result = rhs == 0 ? 0xffffffffU : lhs / rhs; break;
          case 6:
            if (rhs == 0) result = lhs;
            else if (lhs == 0x80000000U && rhs == 0xffffffffU) result = 0;
            else result = std::uint32_t(std::int32_t(lhs) % std::int32_t(rhs));
            break;
          case 7: result = rhs == 0 ? lhs : lhs % rhs; break;
          default: fail("illegal M operation", pc, instruction);
        }
      } else {
        switch (funct3) {
          case 0:
            if (funct7 == 0x00) result = lhs + rhs;
            else if (funct7 == 0x20) result = lhs - rhs;
            else fail("illegal ADD/SUB", pc, instruction);
            break;
          case 1:
            if (funct7 != 0) fail("illegal SLL", pc, instruction);
            result = lhs << (rhs & 31U);
            break;
          case 2:
            if (funct7 != 0) fail("illegal SLT", pc, instruction);
            result = std::int32_t(lhs) < std::int32_t(rhs);
            break;
          case 3:
            if (funct7 != 0) fail("illegal SLTU", pc, instruction);
            result = lhs < rhs;
            break;
          case 4:
            if (funct7 != 0) fail("illegal XOR", pc, instruction);
            result = lhs ^ rhs;
            break;
          case 5:
            if (funct7 == 0x00) result = lhs >> (rhs & 31U);
            else if (funct7 == 0x20) result = std::uint32_t(std::int32_t(lhs) >> (rhs & 31U));
            else fail("illegal SRL/SRA", pc, instruction);
            break;
          case 6:
            if (funct7 != 0) fail("illegal OR", pc, instruction);
            result = lhs | rhs;
            break;
          case 7:
            if (funct7 != 0) fail("illegal AND", pc, instruction);
            result = lhs & rhs;
            break;
          default: fail("illegal OP", pc, instruction);
        }
      }
      writeRd = true;
      break;
    }

    case 0x0f:  // FENCE
      break;

    default:
      fail("unsupported opcode", pc, instruction);
  }

  if (writeRd && rd != 0) cpu.gpr[rd] = result;
  cpu.gpr[0] = 0;
  cpu.pc = nextPc;
}

void parseMtimeReplay() {
  mtimeReplay.clear();
  mtimeReplayIndex = 0;
  const char* raw = std::getenv("AETHERCORE_SHIM_MTIME_VALUES");
  if (raw == nullptr || *raw == '\0') return;

  const std::string text{raw};
  std::size_t start = 0;
  while (start < text.size()) {
    const std::size_t comma = text.find(',', start);
    const std::string token = text.substr(
        start,
        comma == std::string::npos ? std::string::npos : comma - start);
    mtimeReplay.push_back(std::uint32_t(std::stoul(token, nullptr, 0)));
    if (comma == std::string::npos) break;
    start = comma + 1;
  }
}
}  // namespace

extern "C" {
void difftest_init() {
  cpu = CpuState{};
  std::fill(ram.begin(), ram.end(), 0);
  maps.clear();
  parseMtimeReplay();
}

void difftest_memcpy(std::uint32_t address, void* buffer, std::size_t size, bool direction) {
  auto* reference = memoryPointer(address, size);
  if (direction) std::memcpy(reference, buffer, size);
  else std::memcpy(buffer, reference, size);
}

void difftest_regcpy(void* state, bool direction) {
  if (direction) std::memcpy(&cpu, state, sizeof(cpu));
  else std::memcpy(state, &cpu, sizeof(cpu));
}

void difftest_exec(std::uint64_t count) {
  while (count-- != 0) executeOne();
}

std::uint8_t* new_space(int size) {
  if (size <= 0) return nullptr;
  return static_cast<std::uint8_t*>(std::calloc(std::size_t(size), 1));
}

void add_mmio_map(
    char*,
    std::uint32_t base,
    std::uint8_t* space,
    int size,
    void (*)(std::uint32_t, int, bool)) {
  if (space == nullptr || size <= 0) fail("invalid MMIO map");
  maps.push_back(MemoryMap{base, std::uint32_t(size), space});
}
}
