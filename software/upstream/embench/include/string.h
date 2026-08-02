#ifndef AETHERCORE_FREESTANDING_STRING_H
#define AETHERCORE_FREESTANDING_STRING_H

#include <stddef.h>

void *memcpy(void *dest, const void *src, size_t count);
void *memmove(void *dest, const void *src, size_t count);
void *memset(void *dest, int value, size_t count);
int memcmp(const void *lhs, const void *rhs, size_t count);
void *memchr(const void *ptr, int value, size_t count);
char *strchr(const char *text, int value);
size_t strlen(const char *text);
int strcmp(const char *lhs, const char *rhs);
int strncmp(const char *lhs, const char *rhs, size_t count);
char *strcpy(char *dest, const char *src);
char *strncpy(char *dest, const char *src, size_t count);

#endif
