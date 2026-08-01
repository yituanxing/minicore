#include <stdint.h>

static volatile int64_t signed_pairs[][2] = {
    {-INT64_C(9876543210123), INT64_C(1234567)},
    {INT64_C(0x123456789abc), -INT64_C(97)},
    {-INT64_C(42), INT64_C(5)},
    {INT64_C(42), -INT64_C(5)},
};

static volatile uint64_t unsigned_pairs[][2] = {
    {UINT64_C(0xfedcba9876543210), UINT64_C(0x12345)},
    {UINT64_C(0x8000000000000001), UINT64_C(17)},
    {UINT64_C(123456789012345), UINT64_C(99991)},
};

static volatile int32_t signed32_a = -123456;
static volatile int32_t signed32_b = 321;
static volatile uint32_t unsigned32_a = UINT32_C(0xf1234567);
static volatile uint32_t unsigned32_b = UINT32_C(12345);
volatile uint64_t arithmetic_sink;

__attribute__((noinline)) static int check_signed(int64_t a, int64_t b) {
  const int64_t q = a / b;
  const int64_t r = a % b;
  if (q * b + r != a) return 1;
  if (r != 0 && ((r < 0) != (a < 0))) return 2;

  const uint64_t magnitude = b < 0 ? (uint64_t)(-b) : (uint64_t)b;
  const uint64_t remainder_magnitude = r < 0 ? (uint64_t)(-r) : (uint64_t)r;
  if (remainder_magnitude >= magnitude) return 3;
  return 0;
}

__attribute__((noinline)) static int check_unsigned(uint64_t a, uint64_t b) {
  const uint64_t q = a / b;
  const uint64_t r = a % b;
  if (q * b + r != a) return 1;
  if (r >= b) return 2;
  return 0;
}

__attribute__((noinline)) static int32_t mul_i32(int32_t a, int32_t b) { return a * b; }
__attribute__((noinline)) static int32_t div_i32(int32_t a, int32_t b) { return a / b; }
__attribute__((noinline)) static int32_t rem_i32(int32_t a, int32_t b) { return a % b; }
__attribute__((noinline)) static uint32_t mul_u32(uint32_t a, uint32_t b) { return a * b; }
__attribute__((noinline)) static uint32_t div_u32(uint32_t a, uint32_t b) { return a / b; }
__attribute__((noinline)) static uint32_t rem_u32(uint32_t a, uint32_t b) { return a % b; }

int main(void) {
  uint64_t mix = 0;

  for (uint64_t i = 0; i < 4; ++i) {
    const int64_t a = signed_pairs[i][0];
    const int64_t b = signed_pairs[i][1];
    const int status = check_signed(a, b);
    if (status != 0) return 10 + (int)(3U * i) + status;
    mix ^= (uint64_t)(a / b) + ((uint64_t)(a % b) << (i + 1U));
  }

  for (uint64_t i = 0; i < 3; ++i) {
    const uint64_t a = unsigned_pairs[i][0];
    const uint64_t b = unsigned_pairs[i][1];
    const int status = check_unsigned(a, b);
    if (status != 0) return 30 + (int)(2U * i) + status;
    mix ^= (a / b) ^ ((a % b) << (i + 3U));
  }

  const int32_t sa = signed32_a;
  const int32_t sb = signed32_b;
  if (mul_i32(sa, sb) != -39629376) return 50;
  if (div_i32(sa, sb) != -384) return 51;
  if (rem_i32(sa, sb) != -192) return 52;

  const uint32_t ua = unsigned32_a;
  const uint32_t ub = unsigned32_b;
  if (mul_u32(ua, ub) != UINT32_C(0x4dddc3ef)) return 53;
  if (div_u32(ua, ub) != UINT32_C(327713)) return 54;
  if (rem_u32(ua, ub) != UINT32_C(3598)) return 55;

  arithmetic_sink = mix ^ (uint32_t)mul_i32(sa, sb) ^ mul_u32(ua, ub);
  return 0;
}
