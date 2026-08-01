#include <stdint.h>

typedef struct {
  uint64_t a;
  uint64_t b;
  uint64_t c;
} Frame;

volatile uint64_t input_seed = 11;
volatile uint64_t global_sink;
static uint64_t bss_probe[16];

__attribute__((noinline)) static uint64_t fib(uint64_t n) {
  if (n < 2) return n;
  return fib(n - 1) + fib(n - 2);
}

__attribute__((noinline)) static uint64_t gcd_u64(uint64_t a, uint64_t b) {
  while (b != 0) {
    const uint64_t r = a % b;
    a = b;
    b = r;
  }
  return a;
}

__attribute__((noinline)) static uint64_t fold_words(
    const uint64_t* values, uint64_t count, uint64_t salt) {
  uint64_t acc = salt;
  for (uint64_t i = 0; i < count; ++i) {
    acc = (acc * 33U) ^ (values[i] + i);
  }
  return acc;
}

__attribute__((noinline)) static uint64_t frame_walk(Frame value, uint64_t depth) {
  if (depth == 0) return value.a + 2U * value.b + 3U * value.c;

  Frame next = {
      .a = value.b + depth,
      .b = value.c + value.a,
      .c = value.a + value.b + depth,
  };
  return (value.a ^ depth) + frame_walk(next, depth - 1);
}

int main(void) {
  for (uint64_t i = 0; i < 16; ++i) {
    if (bss_probe[i] != 0) return 1;
  }

  const uint64_t seed = input_seed;
  uint64_t local[12];
  for (uint64_t i = 0; i < 12; ++i) local[i] = seed + 3U * i;

  const uint64_t fib_result = fib(seed - 1U);
  if (fib_result != 55U) return 2;

  const uint64_t gcd_result = gcd_u64(1071U + (seed - 11U), 462U);
  if (gcd_result != 21U) return 3;

  const uint64_t folded = fold_words(local, 12, UINT64_C(0x123456789abcdef0));
  if (folded != UINT64_C(0x0a3caab12bb53570)) return 4;

  const Frame initial = {.a = 3, .b = 5, .c = 7};
  const uint64_t walked = frame_walk(initial, 5);
  if (walked != 698U) return 5;

  bss_probe[3] = fib_result;
  bss_probe[7] = gcd_result;
  if (bss_probe[3] + bss_probe[7] != 76U) return 6;

  global_sink = folded ^ walked ^ fib_result ^ gcd_result;
  return 0;
}
