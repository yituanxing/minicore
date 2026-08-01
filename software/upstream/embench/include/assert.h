#ifndef AETHERCORE_FREESTANDING_ASSERT_H
#define AETHERCORE_FREESTANDING_ASSERT_H

#define assert(expression) do { if (!(expression)) { for (;;) {} } } while (0)

#endif
