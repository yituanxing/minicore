/* SPDX-License-Identifier: Apache-2.0 */

#ifndef AETHERCORE_ZEPHYR_EXIT_H
#define AETHERCORE_ZEPHYR_EXIT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void aethercore_exit(uint32_t code) __attribute__((noreturn));

#ifdef __cplusplus
}
#endif

#endif /* AETHERCORE_ZEPHYR_EXIT_H */
