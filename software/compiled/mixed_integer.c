#include <stdint.h>

#define NOINLINE __attribute__((noinline))
#define N 7

volatile uint32_t mixed_seed = UINT32_C(0x31415926);
volatile uint64_t mixed_sink;

NOINLINE static uint32_t lcg_next(uint32_t value) {
  return value * UINT32_C(1664525) + UINT32_C(1013904223);
}

NOINLINE static void fill_matrices(int32_t a[N][N], int32_t b[N][N]) {
  uint32_t state = mixed_seed;
  for (uint64_t i = 0; i < N; ++i) {
    for (uint64_t j = 0; j < N; ++j) {
      state = lcg_next(state);
      a[i][j] = (int32_t)((state >> 16) % 201U) - 100;
      state = lcg_next(state);
      b[i][j] = (int32_t)((state >> 16) % 201U) - 100;
    }
  }
}

NOINLINE static void matrix_multiply(
    const int32_t a[N][N], const int32_t b[N][N], int64_t c[N][N]) {
  for (uint64_t i = 0; i < N; ++i) {
    for (uint64_t j = 0; j < N; ++j) {
      int64_t sum = 0;
      for (uint64_t k = 0; k < N; ++k) {
        sum += (int64_t)a[i][k] * (int64_t)b[k][j];
      }
      c[i][j] = sum;
    }
  }
}

NOINLINE static uint64_t matrix_checksum(const int64_t c[N][N]) {
  uint64_t acc = UINT64_C(0xcbf29ce484222325);
  for (uint64_t i = 0; i < N; ++i) {
    for (uint64_t j = 0; j < N; ++j) {
      const uint64_t index = i * N + j;
      acc ^= (uint64_t)c[i][j] + index * UINT64_C(0x9e37);
      acc *= UINT64_C(0x100000001b3);
    }
  }
  return acc;
}

NOINLINE static uint64_t division_chain(uint64_t acc) {
  for (uint64_t i = 1; i < 98; ++i) {
    const int64_t magnitude = (int64_t)((acc ^
        (i * UINT64_C(0x9e3779b97f4a7c15))) >> 1);
    const int64_t value = (i & 1U) != 0 ? -magnitude : magnitude;
    const int64_t divisor = (int64_t)(i % 17U) + 1;
    const int64_t quotient = value / divisor;
    const int64_t remainder = value % divisor;
    if (quotient * divisor + remainder != value) return 0;

    acc ^= (uint64_t)quotient;
    acc += (uint64_t)remainder * UINT64_C(0x100000001b3);
    acc = (acc << 9) | (acc >> 55);
  }
  return acc;
}

int main(void) {
  int32_t a[N][N];
  int32_t b[N][N];
  int64_t c[N][N];

  fill_matrices(a, b);
  matrix_multiply(a, b, c);

  if (c[0][0] != -10501 || c[0][1] != -12923 ||
      c[0][3] != 23294 || c[6][6] != -18215) {
    return 1;
  }

  const uint64_t first = matrix_checksum(c);
  if (first != UINT64_C(0xf4f31bdc65bfcc5e)) return 2;

  const uint64_t final = division_chain(first);
  if (final != UINT64_C(0xdd824284d6d2042a)) return 3;

  mixed_sink = final;
  return 0;
}
