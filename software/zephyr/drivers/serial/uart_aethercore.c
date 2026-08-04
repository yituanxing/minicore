/* SPDX-License-Identifier: Apache-2.0 */

#define DT_DRV_COMPAT zephyr_aethercore_uart

#include <zephyr/device.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/arch/riscv/sys_io.h>
#include <zephyr/sys/util.h>

#define AETHERCORE_UART_RX_DATA_OFFSET    0x0U
#define AETHERCORE_UART_RX_STATUS_OFFSET  0x4U
#define AETHERCORE_UART_RX_STATUS_READY   BIT(0)
#define AETHERCORE_UART_RX_STATUS_OVERRUN BIT(1)

struct aethercore_uart_config {
	mm_reg_t tx;
	mm_reg_t rx;
};

static int aethercore_uart_poll_in(const struct device *dev, unsigned char *c)
{
	const struct aethercore_uart_config *config = dev->config;
	uint32_t status = sys_read32(config->rx + AETHERCORE_UART_RX_STATUS_OFFSET);

	if ((status & AETHERCORE_UART_RX_STATUS_READY) == 0U) {
		return -1;
	}

	*c = (unsigned char)sys_read32(config->rx + AETHERCORE_UART_RX_DATA_OFFSET);
	return 0;
}

static void aethercore_uart_poll_out(const struct device *dev, unsigned char c)
{
	const struct aethercore_uart_config *config = dev->config;

	sys_write32((uint32_t)c, config->tx);
}

static int aethercore_uart_err_check(const struct device *dev)
{
	const struct aethercore_uart_config *config = dev->config;
	uint32_t status = sys_read32(config->rx + AETHERCORE_UART_RX_STATUS_OFFSET);

	if ((status & AETHERCORE_UART_RX_STATUS_OVERRUN) != 0U) {
		sys_write32(AETHERCORE_UART_RX_STATUS_OVERRUN,
			    config->rx + AETHERCORE_UART_RX_STATUS_OFFSET);
		return UART_ERROR_OVERRUN;
	}

	return 0;
}

static int aethercore_uart_init(const struct device *dev)
{
	ARG_UNUSED(dev);
	return 0;
}

static const struct uart_driver_api aethercore_uart_driver_api = {
	.poll_in = aethercore_uart_poll_in,
	.poll_out = aethercore_uart_poll_out,
	.err_check = aethercore_uart_err_check,
};

#define AETHERCORE_UART_INIT(inst)                                             \
	static const struct aethercore_uart_config aethercore_uart_config_##inst = { \
		.tx = DT_INST_REG_ADDR_BY_IDX(inst, 0),                              \
		.rx = DT_INST_REG_ADDR_BY_IDX(inst, 1),                              \
	};                                                                         \
	DEVICE_DT_INST_DEFINE(inst, aethercore_uart_init, NULL, NULL,              \
			      &aethercore_uart_config_##inst, PRE_KERNEL_1,          \
			      CONFIG_SERIAL_INIT_PRIORITY, &aethercore_uart_driver_api);

DT_INST_FOREACH_STATUS_OKAY(AETHERCORE_UART_INIT)
