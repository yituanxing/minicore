/* SPDX-License-Identifier: GPL-3.0-or-later */

#include "support.h"

void initialise_board(void) {}

void __attribute__((noinline)) start_trigger(void) {
  __asm__ volatile("" ::: "memory");
}

void __attribute__((noinline)) stop_trigger(void) {
  __asm__ volatile("" ::: "memory");
}
