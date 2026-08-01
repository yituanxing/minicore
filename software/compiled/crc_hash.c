#include <stdint.h>

#define NOINLINE __attribute__((noinline))

volatile uint32_t crc_seed = UINT32_C(0x12345678);
volatile uint64_t crc_sink;

NOINLINE static uint32_t xorshift32(uint32_t value) {
  value ^= value << 13;
  value ^= value >> 17;
  value ^= value << 5;
  return value;
}

NOINLINE static void fill_data(uint8_t* data, uint64_t count) {
  uint32_t state = crc_seed;
  for (uint64_t i = 0; i < count; ++i) {
    state = xorshift32(state);
    data[i] = (uint8_t)(state ^ (uint32_t)(i * 29U));
  }
}

NOINLINE static uint32_t crc32_bitwise(const uint8_t* data, uint64_t count) {
  uint32_t crc = UINT32_C(0xffffffff);
  for (uint64_t i = 0; i < count; ++i) {
    crc ^= data[i];
    for (uint32_t bit = 0; bit < 8; ++bit) {
      const uint32_t mask = (uint32_t)-(int32_t)(crc & 1U);
      crc = (crc >> 1) ^ (UINT32_C(0xedb88320) & mask);
    }
  }
  return crc ^ UINT32_C(0xffffffff);
}

NOINLINE static uint64_t fnv1a64(const uint8_t* data, uint64_t count) {
  uint64_t hash = UINT64_C(0xcbf29ce484222325);
  for (uint64_t i = 0; i < count; ++i) {
    hash ^= data[i];
    hash *= UINT64_C(0x100000001b3);
  }
  return hash;
}

NOINLINE static uint64_t rotate_mix(const uint8_t* data, uint64_t count) {
  uint64_t value = UINT64_C(0x9e3779b97f4a7c15);
  for (uint64_t i = 0; i < count; ++i) {
    value ^= (uint64_t)data[i] + i * UINT64_C(0x10001);
    value = (value << 7) | (value >> 57);
    value *= UINT64_C(0x2545f4914f6cdd1d);
  }
  return value;
}

int main(void) {
  uint8_t data[257];
  fill_data(data, 257);

  if (data[0] != UINT8_C(0xa5) || data[1] != UINT8_C(0xbe) ||
      data[2] != UINT8_C(0xfe) || data[256] != UINT8_C(0xe4)) {
    return 1;
  }

  const uint32_t crc = crc32_bitwise(data, 257);
  if (crc != UINT32_C(0x8c054d91)) return 2;

  const uint64_t fnv = fnv1a64(data, 257);
  if (fnv != UINT64_C(0x57a5f31c0b3b7b6a)) return 3;

  const uint64_t mixed = rotate_mix(data, 257);
  if (mixed != UINT64_C(0x8de6d956cb70c08d)) return 4;

  crc_sink = ((uint64_t)crc << 32) ^ fnv ^ mixed;
  return 0;
}
