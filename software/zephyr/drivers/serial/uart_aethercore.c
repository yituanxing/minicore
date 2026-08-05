/* SPDX-License-Identifier: Apache-2.0 */

#define DT_DRV_COMPAT zephyr_aethercore_uart

#include <zephyr/arch/riscv/sys_io.h>
#include <zephyr/device.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/irq.h>
#include <zephyr/sys/util.h>

#define AETHERCORE_UART_RX_DATA_OFFSET       0x0U
#define AETHERCORE_UART_RX_STATUS_OFFSET     0x4U
#define AETHERCORE_UART_RX_CONTROL_OFFSET    0x8U
#define AETHERCORE_UART_RX_STATUS_READY      BIT(0)
#define AETHERCORE_UART_RX_STATUS_OVERRUN    BIT(1)
#define AETHERCORE_UART_RX_CONTROL_IRQ_ENABLE BIT(0)

struct aethercore_uart_config {
	mm_reg_t tx;
	mm_reg_t rx;
#ifdef CONFIG_UART_INTERRUPT_DRIVEN
	uart_irq_config_func_t irq_config_func;
#endif
};

struct aethercore_uart_data {
#ifdef CONFIG_UART_INTERRUPT_DRIVEN
	uart_irq_callback_user_data_t callback;
	void *callback_data;
#endif
};

static uint32_t aethercore_uart_rx_status(const struct device *dev)
{
	const struct aethercore_uart_config *config = dev->config;

	return sys_read32(config->rx + AETHERCORE_UART_RX_STATUS_OFFSET);
}

static int aethercore_uart_poll_in(const struct device *dev, unsigned char *c)
{
	const struct aethercore_uart_config *config = dev->config;

	if ((aethercore_uart_rx_status(dev) & AETHERCORE_UART_RX_STATUS_READY) == 0U) {
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
	uint32_t status = aethercore_uart_rx_status(dev);

	if ((status & AETHERCORE_UART_RX_STATUS_OVERRUN) != 0U) {
		sys_write32(AETHERCORE_UART_RX_STATUS_OVERRUN,
			    config->rx + AETHERCORE_UART_RX_STATUS_OFFSET);
		return UART_ERROR_OVERRUN;
	}

	return 0;
}

#ifdef CONFIG_UART_INTERRUPT_DRIVEN
static int aethercore_uart_fifo_fill(const struct device *dev,
				     const uint8_t *tx_data,
				     int size)
{
	for (int i = 0; i < size; ++i) {
		aethercore_uart_poll_out(dev, tx_data[i]);
	}

	return size;
}

static int aethercore_uart_fifo_read(const struct device *dev,
				     uint8_t *rx_data,
				     const int size)
{
	const struct aethercore_uart_config *config = dev->config;
	int count = 0;

	while (count < size &&
	       (aethercore_uart_rx_status(dev) & AETHERCORE_UART_RX_STATUS_READY) != 0U) {
		rx_data[count++] =
			(uint8_t)sys_read32(config->rx + AETHERCORE_UART_RX_DATA_OFFSET);
	}

	return count;
}

static void aethercore_uart_irq_tx_enable(const struct device *dev)
{
	ARG_UNUSED(dev);
}

static void aethercore_uart_irq_tx_disable(const struct device *dev)
{
	ARG_UNUSED(dev);
}

static int aethercore_uart_irq_tx_ready(const struct device *dev)
{
	ARG_UNUSED(dev);
	return 0;
}

static int aethercore_uart_irq_tx_complete(const struct device *dev)
{
	ARG_UNUSED(dev);
	return 1;
}

static void aethercore_uart_irq_rx_enable(const struct device *dev)
{
	const struct aethercore_uart_config *config = dev->config;

	sys_write32(AETHERCORE_UART_RX_CONTROL_IRQ_ENABLE,
		    config->rx + AETHERCORE_UART_RX_CONTROL_OFFSET);
}

static void aethercore_uart_irq_rx_disable(const struct device *dev)
{
	const struct aethercore_uart_config *config = dev->config;

	sys_write32(0U, config->rx + AETHERCORE_UART_RX_CONTROL_OFFSET);
}

static int aethercore_uart_irq_rx_ready(const struct device *dev)
{
	return (aethercore_uart_rx_status(dev) & AETHERCORE_UART_RX_STATUS_READY) != 0U;
}

static void aethercore_uart_irq_err_enable(const struct device *dev)
{
	ARG_UNUSED(dev);
}

static void aethercore_uart_irq_err_disable(const struct device *dev)
{
	ARG_UNUSED(dev);
}

static int aethercore_uart_irq_is_pending(const struct device *dev)
{
	const struct aethercore_uart_config *config = dev->config;
	uint32_t control = sys_read32(config->rx + AETHERCORE_UART_RX_CONTROL_OFFSET);

	return (control & AETHERCORE_UART_RX_CONTROL_IRQ_ENABLE) != 0U &&
	       aethercore_uart_irq_rx_ready(dev);
}

static int aethercore_uart_irq_update(const struct device *dev)
{
	ARG_UNUSED(dev);
	return 1;
}

static void aethercore_uart_irq_callback_set(const struct device *dev,
					     uart_irq_callback_user_data_t callback,
					     void *callback_data)
{
	struct aethercore_uart_data *data = dev->data;

	data->callback = callback;
	data->callback_data = callback_data;
}

static void aethercore_uart_isr(const struct device *dev)
{
	struct aethercore_uart_data *data = dev->data;

	if (data->callback != NULL) {
		data->callback(dev, data->callback_data);
	}
}
#endif

static int aethercore_uart_init(const struct device *dev)
{
	const struct aethercore_uart_config *config = dev->config;

	sys_write32(0U, config->rx + AETHERCORE_UART_RX_CONTROL_OFFSET);
	sys_write32(AETHERCORE_UART_RX_STATUS_OVERRUN,
		    config->rx + AETHERCORE_UART_RX_STATUS_OFFSET);
#ifdef CONFIG_UART_INTERRUPT_DRIVEN
	config->irq_config_func(dev);
#endif
	return 0;
}

static const struct uart_driver_api aethercore_uart_driver_api = {
	.poll_in = aethercore_uart_poll_in,
	.poll_out = aethercore_uart_poll_out,
	.err_check = aethercore_uart_err_check,
#ifdef CONFIG_UART_INTERRUPT_DRIVEN
	.fifo_fill = aethercore_uart_fifo_fill,
	.fifo_read = aethercore_uart_fifo_read,
	.irq_tx_enable = aethercore_uart_irq_tx_enable,
	.irq_tx_disable = aethercore_uart_irq_tx_disable,
	.irq_tx_ready = aethercore_uart_irq_tx_ready,
	.irq_tx_complete = aethercore_uart_irq_tx_complete,
	.irq_rx_enable = aethercore_uart_irq_rx_enable,
	.irq_rx_disable = aethercore_uart_irq_rx_disable,
	.irq_rx_ready = aethercore_uart_irq_rx_ready,
	.irq_err_enable = aethercore_uart_irq_err_enable,
	.irq_err_disable = aethercore_uart_irq_err_disable,
	.irq_is_pending = aethercore_uart_irq_is_pending,
	.irq_update = aethercore_uart_irq_update,
	.irq_callback_set = aethercore_uart_irq_callback_set,
#endif
};

#ifdef CONFIG_UART_INTERRUPT_DRIVEN
#define AETHERCORE_UART_IRQ_DECLARE(inst)                                      \
	static void aethercore_uart_irq_config_##inst(const struct device *dev)
#define AETHERCORE_UART_IRQ_FIELD(inst)                                        \
	.irq_config_func = aethercore_uart_irq_config_##inst,
#define AETHERCORE_UART_IRQ_DEFINE(inst)                                       \
	static void aethercore_uart_irq_config_##inst(const struct device *dev) \
	{                                                                          \
		ARG_UNUSED(dev);                                                   \
		IRQ_CONNECT(DT_INST_IRQN(inst), DT_INST_IRQ(inst, priority),       \
			    aethercore_uart_isr, DEVICE_DT_INST_GET(inst), 0);      \
		irq_enable(DT_INST_IRQN(inst));                                    \
	}
#else
#define AETHERCORE_UART_IRQ_DECLARE(inst)
#define AETHERCORE_UART_IRQ_FIELD(inst)
#define AETHERCORE_UART_IRQ_DEFINE(inst)
#endif

#define AETHERCORE_UART_INIT(inst)                                             \
	AETHERCORE_UART_IRQ_DECLARE(inst);                                      \
	static struct aethercore_uart_data aethercore_uart_data_##inst;         \
	static const struct aethercore_uart_config aethercore_uart_config_##inst = { \
		.tx = DT_INST_REG_ADDR_BY_IDX(inst, 0),                         \
		.rx = DT_INST_REG_ADDR_BY_IDX(inst, 1),                         \
		AETHERCORE_UART_IRQ_FIELD(inst)                                 \
	};                                                                         \
	DEVICE_DT_INST_DEFINE(inst, aethercore_uart_init, NULL,                   \
			      &aethercore_uart_data_##inst,                         \
			      &aethercore_uart_config_##inst, PRE_KERNEL_1,        \
			      CONFIG_SERIAL_INIT_PRIORITY, &aethercore_uart_driver_api); \
	AETHERCORE_UART_IRQ_DEFINE(inst)

DT_INST_FOREACH_STATUS_OKAY(AETHERCORE_UART_INIT)
