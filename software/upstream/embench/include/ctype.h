#ifndef AETHERCORE_FREESTANDING_CTYPE_H
#define AETHERCORE_FREESTANDING_CTYPE_H

static inline int isdigit(int value) {
  const unsigned char ch = (unsigned char)value;
  return ch >= (unsigned char)'0' && ch <= (unsigned char)'9';
}

static inline int islower(int value) {
  const unsigned char ch = (unsigned char)value;
  return ch >= (unsigned char)'a' && ch <= (unsigned char)'z';
}

static inline int isupper(int value) {
  const unsigned char ch = (unsigned char)value;
  return ch >= (unsigned char)'A' && ch <= (unsigned char)'Z';
}

static inline int isalpha(int value) {
  return islower(value) || isupper(value);
}

static inline int isalnum(int value) {
  return isalpha(value) || isdigit(value);
}

static inline int isspace(int value) {
  const unsigned char ch = (unsigned char)value;
  return ch == (unsigned char)' ' ||
         (ch >= (unsigned char)'\t' && ch <= (unsigned char)'\r');
}

static inline int isxdigit(int value) {
  const unsigned char ch = (unsigned char)value;
  return isdigit(ch) ||
         (ch >= (unsigned char)'a' && ch <= (unsigned char)'f') ||
         (ch >= (unsigned char)'A' && ch <= (unsigned char)'F');
}

static inline int tolower(int value) {
  return isupper(value) ? value + ('a' - 'A') : value;
}

static inline int toupper(int value) {
  return islower(value) ? value - ('a' - 'A') : value;
}

#endif
