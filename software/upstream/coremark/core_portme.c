/* SPDX-License-Identifier: Apache-2.0 */
#include "coremark.h"
#include "core_portme.h"

#if VALIDATION_RUN
volatile ee_s32 seed1_volatile = 0x3415;
volatile ee_s32 seed2_volatile = 0x3415;
volatile ee_s32 seed3_volatile = 0x66;
#elif PROFILE_RUN
volatile ee_s32 seed1_volatile = 0x8;
volatile ee_s32 seed2_volatile = 0x8;
volatile ee_s32 seed3_volatile = 0x8;
#else
volatile ee_s32 seed1_volatile = 0x0;
volatile ee_s32 seed2_volatile = 0x0;
volatile ee_s32 seed3_volatile = 0x66;
#endif

volatile ee_s32 seed4_volatile = ITERATIONS;
volatile ee_s32 seed5_volatile = 0;

ee_u32 default_num_contexts = 1;

static volatile ee_s32 error_status;
static CORE_TICKS start_ticks;
static CORE_TICKS stop_ticks;

static int starts_with(const char *text, const char *prefix)
{
    while (*prefix != '\0') {
        if (*text++ != *prefix++)
            return 0;
    }
    return 1;
}

void coremark_reset_status(void)
{
    error_status = 0;
}

int coremark_status(void)
{
    return error_status == 0 ? 0 : 1;
}

int ee_printf(const char *fmt, ...)
{
    if (starts_with(fmt, "ERROR!") ||
        starts_with(fmt, "Errors detected") ||
        starts_with(fmt, "Cannot validate")) {
        error_status = 1;
    }
    return 0;
}

void start_time(void)
{
    start_ticks = 0;
}

void stop_time(void)
{
    /* This corpus validates computation only; it does not publish a score. */
    stop_ticks = 10;
}

CORE_TICKS get_time(void)
{
    return stop_ticks - start_ticks;
}

secs_ret time_in_secs(CORE_TICKS ticks)
{
    return (secs_ret)ticks;
}

void portable_init(core_portable *p, int *argc, char *argv[])
{
    (void)argc;
    (void)argv;

    if (sizeof(ee_ptr_int) != sizeof(ee_u8 *))
        error_status = 1;
    if (sizeof(ee_u32) != 4)
        error_status = 1;

    p->portable_id = 1;
}

void portable_fini(core_portable *p)
{
    p->portable_id = 0;
}
