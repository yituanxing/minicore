#ifndef AETHERCORE_FREESTANDING_STDLIB_H
#define AETHERCORE_FREESTANDING_STDLIB_H

/* FreeRTOS tasks.c includes <stdlib.h> unconditionally, but the selected
 * configuration uses pvPortMalloc/vPortFree and requires no hosted stdlib API.
 * Keep this header intentionally minimal so a future standard-library
 * dependency fails at compile time instead of being silently supplied by the
 * build host. */

#include <stddef.h>

#endif /* AETHERCORE_FREESTANDING_STDLIB_H */
