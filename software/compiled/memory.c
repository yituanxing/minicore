#include <stdint.h>

typedef struct {
  uint32_t tag;
  uint16_t length;
  uint8_t flags;
  uint8_t payload[9];
} Packet;

static const uint8_t source[32] = {
    0x10, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
    0x01, 0x12, 0x24, 0x48, 0x81, 0x18, 0x2a, 0x3c,
    0x4e, 0x50, 0x62, 0x74, 0x86, 0x98, 0xaa, 0xbc,
    0xce, 0xd0, 0xe2, 0xf4, 0x06, 0x17, 0x28, 0x39,
};

static const uint32_t table[8] = {
    0x10203040U, 0x55667788U, 0xdeadbeefU, 0x13579bdfU,
    0x2468ace0U, 0x0badc0deU, 0xc001d00dU, 0x7f00ff00U,
};

static uint8_t target[32];
static uint64_t bss_words[16];
volatile uint64_t memory_sink;

__attribute__((noinline)) static void fill_bytes(uint8_t* dst, uint8_t value, uint64_t count) {
  for (uint64_t i = 0; i < count; ++i) dst[i] = value;
}

__attribute__((noinline)) static void copy_bytes(
    uint8_t* dst, const uint8_t* src, uint64_t count) {
  for (uint64_t i = 0; i < count; ++i) dst[i] = src[i];
}

__attribute__((noinline)) static uint64_t fnv1a64(const uint8_t* data, uint64_t count) {
  uint64_t hash = UINT64_C(1469598103934665603);
  for (uint64_t i = 0; i < count; ++i) {
    hash = (hash ^ data[i]) * UINT64_C(1099511628211);
  }
  return hash;
}

__attribute__((noinline)) static uint64_t sum_table(const uint32_t* values, uint64_t count) {
  uint64_t sum = 0;
  for (uint64_t i = 0; i < count; ++i) sum += values[i];
  return sum;
}

int main(void) {
  for (uint64_t i = 0; i < 16; ++i) {
    if (bss_words[i] != 0) return 1;
  }

  fill_bytes(target, 0xa5U, 32);
  for (uint64_t i = 0; i < 32; ++i) {
    if (target[i] != 0xa5U) return 2;
  }

  copy_bytes(target, source, 32);
  for (uint64_t i = 0; i < 32; ++i) {
    if (target[i] != source[i]) return 3;
  }

  const uint64_t hash = fnv1a64(target, 32);
  if (hash != UINT64_C(0xe3989c9197f60806)) return 4;

  Packet packet = {
      .tag = 0x41544852U,
      .length = 9,
      .flags = 0x5a,
      .payload = {1, 3, 5, 7, 9, 11, 13, 15, 17},
  };
  uint8_t* raw = (uint8_t*)&packet;
  raw[7] ^= 0x20U;
  if (packet.payload[0] != 33U || packet.payload[8] != 17U) return 5;

  const uint64_t table_sum = sum_table(table, 8);
  if (table_sum != UINT64_C(0x00000002c6a53f61)) return 6;

  bss_words[2] = packet.tag;
  bss_words[5] = hash;
  bss_words[9] = table_sum;
  if (bss_words[2] != 0x41544852U || bss_words[5] != hash || bss_words[9] != table_sum) {
    return 7;
  }

  memory_sink = hash ^ table_sum ^ packet.tag ^ packet.payload[0];
  return 0;
}
