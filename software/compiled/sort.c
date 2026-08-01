#include <stdint.h>

#define NOINLINE __attribute__((noinline))

static volatile int64_t source_values[32] = {
    37, -12, 5, 99, 0, -44, 18, 18,
    7, -1, 63, -27, 42, 11, -90, 76,
    3, 55, -8, 21, 14, -33, 88, 2,
    67, -15, 31, 4, 100, -64, 9, 23,
};

static const int64_t expected_values[32] = {
    -90, -64, -44, -33, -27, -15, -12, -8,
    -1, 0, 2, 3, 4, 5, 7, 9,
    11, 14, 18, 18, 21, 23, 31, 37,
    42, 55, 63, 67, 76, 88, 99, 100,
};

volatile uint64_t sort_sink;

NOINLINE static void copy_values(int64_t* dst) {
  for (uint64_t i = 0; i < 32; ++i) dst[i] = source_values[i];
}

NOINLINE static void insertion_sort(int64_t* values, uint64_t count) {
  for (uint64_t i = 1; i < count; ++i) {
    const int64_t key = values[i];
    uint64_t j = i;
    while (j != 0 && values[j - 1] > key) {
      values[j] = values[j - 1];
      --j;
    }
    values[j] = key;
  }
}

NOINLINE static void quick_sort(int64_t* values, int64_t lo, int64_t hi) {
  if (lo >= hi) return;

  int64_t i = lo;
  int64_t j = hi;
  const int64_t pivot = values[(lo + hi) / 2];
  while (i <= j) {
    while (values[i] < pivot) ++i;
    while (values[j] > pivot) --j;
    if (i <= j) {
      const int64_t tmp = values[i];
      values[i] = values[j];
      values[j] = tmp;
      ++i;
      --j;
    }
  }

  if (lo < j) quick_sort(values, lo, j);
  if (i < hi) quick_sort(values, i, hi);
}

NOINLINE static uint64_t checksum(const int64_t* values) {
  uint64_t acc = UINT64_C(0xcbf29ce484222325);
  for (uint64_t i = 0; i < 32; ++i) {
    acc ^= (uint64_t)values[i] + i * UINT64_C(0x9e3779b9);
    acc *= UINT64_C(0x100000001b3);
  }
  return acc;
}

int main(void) {
  int64_t insertion[32];
  int64_t quick[32];
  copy_values(insertion);
  copy_values(quick);

  insertion_sort(insertion, 32);
  quick_sort(quick, 0, 31);

  for (uint64_t i = 0; i < 32; ++i) {
    if (insertion[i] != expected_values[i]) return 1;
    if (quick[i] != expected_values[i]) return 2;
    if (insertion[i] != quick[i]) return 3;
  }

  const uint64_t a = checksum(insertion);
  const uint64_t b = checksum(quick);
  if (a != b) return 4;
  sort_sink = a;
  return 0;
}
