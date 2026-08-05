#include <aethercore/exit.h>

#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>

#define RX_EXPECTED_COUNT 2U
#define RX_QUEUE_DEPTH 4U

static const uint8_t expected_bytes[RX_EXPECTED_COUNT] = { 'Z', '4' };

K_MSGQ_DEFINE(rx_queue, sizeof(uint8_t), RX_QUEUE_DEPTH, 1);
K_SEM_DEFINE(rx_done, 0, 1);

static struct k_work rx_work;
static volatile uint32_t rx_isr_calls;
static volatile bool rx_isr_context_seen;
static volatile bool rx_failed;
static uint32_t rx_work_bytes;
static bool rx_work_thread_seen;

static void signal_failure(void)
{
	rx_failed = true;
	k_sem_give(&rx_done);
}

static void rx_work_handler(struct k_work *work)
{
	uint8_t byte;

	ARG_UNUSED(work);
	if (k_is_in_isr()) {
		signal_failure();
		return;
	}
	rx_work_thread_seen = true;

	while (k_msgq_get(&rx_queue, &byte, K_NO_WAIT) == 0) {
		uint32_t index = rx_work_bytes;

		if (index >= RX_EXPECTED_COUNT || byte != expected_bytes[index]) {
			signal_failure();
			return;
		}

		printk("AETHERCORE ZEPHYR WORK byte=0x%02x index=%u\n",
		       (unsigned int)byte, (unsigned int)index);
		rx_work_bytes = index + 1U;
	}

	if (rx_work_bytes == RX_EXPECTED_COUNT) {
		k_sem_give(&rx_done);
	}
}

static void uart_rx_callback(const struct device *dev, void *user_data)
{
	bool submitted = false;
	uint8_t byte;

	ARG_UNUSED(user_data);
	if (!k_is_in_isr()) {
		signal_failure();
		return;
	}
	rx_isr_context_seen = true;

	if (uart_irq_update(dev) <= 0 || !uart_irq_rx_ready(dev)) {
		return;
	}

	rx_isr_calls++;
	while (uart_fifo_read(dev, &byte, 1) == 1) {
		if (k_msgq_put(&rx_queue, &byte, K_NO_WAIT) != 0) {
			signal_failure();
			return;
		}
		submitted = true;
	}

	if (submitted && k_work_submit(&rx_work) < 0) {
		signal_failure();
	}
}

int main(void)
{
	const struct device *uart = DEVICE_DT_GET(DT_CHOSEN(zephyr_console));
	int rc;

	printk("AETHERCORE ZEPHYR Z4 BOOT\n");
	if (!device_is_ready(uart)) {
		printk("AETHERCORE ZEPHYR IRQ FAIL reason=device-not-ready\n");
		aethercore_exit(1U);
		return 1;
	}

	k_work_init(&rx_work, rx_work_handler);
	rc = uart_irq_callback_user_data_set(uart, uart_rx_callback, NULL);
	if (rc != 0) {
		printk("AETHERCORE ZEPHYR IRQ FAIL reason=callback rc=%d\n", rc);
		aethercore_exit(2U);
		return 2;
	}

	uart_irq_rx_enable(uart);
	printk("AETHERCORE ZEPHYR IRQ ARMED\n");

	rc = k_sem_take(&rx_done, K_MSEC(250));
	uart_irq_rx_disable(uart);
	if (rc != 0) {
		printk("AETHERCORE ZEPHYR IRQ FAIL reason=timeout\n");
		aethercore_exit(3U);
		return 3;
	}
	if (rx_failed || !rx_isr_context_seen || !rx_work_thread_seen ||
	    rx_isr_calls == 0U || rx_work_bytes != RX_EXPECTED_COUNT) {
		printk("AETHERCORE ZEPHYR IRQ FAIL reason=contract isr=%u work=%u\n",
		       (unsigned int)rx_isr_calls, (unsigned int)rx_work_bytes);
		aethercore_exit(4U);
		return 4;
	}

	printk("AETHERCORE ZEPHYR IRQ PASS bytes=%u isr=%u work=%u\n",
	       RX_EXPECTED_COUNT, (unsigned int)rx_isr_calls,
	       (unsigned int)rx_work_bytes);
	aethercore_exit(0U);
	return 0;
}
