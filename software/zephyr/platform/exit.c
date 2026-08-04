/* SPDX-License-Identifier: Apache-2.0 */

#define DT_DRV_COMPAT zephyr_aethercore_exit

#include <aethercore/exit.h>

#include <zephyr/devicetree.h>
#include <zephyr/sys/sys_io.h>
#include <zephyr/sys/util.h>

BUILD_ASSERT(DT_NUM_INST_STATUS_OKAY(DT_DRV_COMPAT) == 1,
	     "AetherCore Zephyr qualification requires exactly one exit register");

void aethercore_exit(uint32_t code)
{
	sys_write32(code, DT_INST_REG_ADDR(0));

	/* The simulator terminates on the MMIO write. WFI is a deterministic
	 * fail-closed fallback if an integration harness forgets to observe it. */
	for (;;) {
		__asm__ volatile ("wfi" ::: "memory");
	}
}
