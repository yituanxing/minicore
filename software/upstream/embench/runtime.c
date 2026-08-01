/* SPDX-License-Identifier: MIT */

#include <stddef.h>
#include "string.h"
#include "stdlib.h"

void *memcpy(void *dest, const void *src, size_t count) {
  unsigned char *out = (unsigned char *)dest;
  const unsigned char *in = (const unsigned char *)src;
  for (size_t i = 0; i < count; ++i) out[i] = in[i];
  return dest;
}

void *memmove(void *dest, const void *src, size_t count) {
  unsigned char *out = (unsigned char *)dest;
  const unsigned char *in = (const unsigned char *)src;
  if (out <= in || out >= in + count) {
    for (size_t i = 0; i < count; ++i) out[i] = in[i];
  } else {
    for (size_t i = count; i != 0; --i) out[i - 1] = in[i - 1];
  }
  return dest;
}

void *memset(void *dest, int value, size_t count) {
  unsigned char *out = (unsigned char *)dest;
  for (size_t i = 0; i < count; ++i) out[i] = (unsigned char)value;
  return dest;
}

int memcmp(const void *lhs, const void *rhs, size_t count) {
  const unsigned char *a = (const unsigned char *)lhs;
  const unsigned char *b = (const unsigned char *)rhs;
  for (size_t i = 0; i < count; ++i) {
    if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
  }
  return 0;
}

void *memchr(const void *ptr, int value, size_t count) {
  const unsigned char *bytes = (const unsigned char *)ptr;
  const unsigned char target = (unsigned char)value;
  for (size_t i = 0; i < count; ++i) {
    if (bytes[i] == target) return (void *)(bytes + i);
  }
  return NULL;
}

size_t strlen(const char *text) {
  size_t length = 0;
  while (text[length] != '\0') ++length;
  return length;
}

int strcmp(const char *lhs, const char *rhs) {
  while (*lhs != '\0' && *lhs == *rhs) {
    ++lhs;
    ++rhs;
  }
  return (unsigned char)*lhs - (unsigned char)*rhs;
}

int strncmp(const char *lhs, const char *rhs, size_t count) {
  for (size_t i = 0; i < count; ++i) {
    const unsigned char a = (unsigned char)lhs[i];
    const unsigned char b = (unsigned char)rhs[i];
    if (a != b) return a - b;
    if (a == 0) return 0;
  }
  return 0;
}

char *strcpy(char *dest, const char *src) {
  char *out = dest;
  do {
    *out++ = *src;
  } while (*src++ != '\0');
  return dest;
}

char *strncpy(char *dest, const char *src, size_t count) {
  size_t i = 0;
  for (; i < count && src[i] != '\0'; ++i) dest[i] = src[i];
  for (; i < count; ++i) dest[i] = '\0';
  return dest;
}

int abs(int value) { return value < 0 ? -value : value; }
long labs(long value) { return value < 0 ? -value : value; }
