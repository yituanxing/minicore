#include <stdint.h>

static uint32_t initialized_state = 0x13579bdfu;
static uint32_t zero_area[8];

static uint32_t mix(uint32_t value) {
  value ^= value << 5;
  value += 0x9e3779b9u;
  value ^= value >> 7;
  return value;
}

static void fill_words(uint32_t *output, uint32_t count, uint32_t seed) {
  for (uint32_t index = 0; index < count; ++index) {
    seed = mix(seed + index);
    output[index] = seed;
  }
}

static uint32_t fold_words(const uint32_t *input, uint32_t count) {
  uint32_t checksum = 0x811c9dc5u;
  for (uint32_t index = 0; index < count; ++index) {
    checksum ^= input[index];
    checksum = (checksum << 3) | (checksum >> 29);
    checksum += 0x01020304u;
  }
  return checksum;
}

int main(void) {
  uint32_t source[16];
  uint32_t copy[16];

  for (uint32_t index = 0; index < 8; ++index) {
    if (zero_area[index] != 0) return 1;
  }
  if (initialized_state != 0x13579bdfu) return 2;

  fill_words(source, 16, 0x12345678u);
  for (uint32_t index = 0; index < 16; ++index) {
    copy[index] = source[index];
  }
  for (uint32_t index = 0; index < 16; ++index) {
    if (copy[index] != source[index]) return 3;
  }

  zero_area[3] = source[5];
  if (zero_area[3] != source[5]) return 4;
  if (fold_words(copy, 16) != 0x3a11d50bu) return 5;

  initialized_state ^= copy[0];
  if (initialized_state != 0xe04464c8u) return 6;
  return 0;
}
