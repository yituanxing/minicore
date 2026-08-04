#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>

#define WORKER_STACK_SIZE 1024
#define WORKER_PRIORITY 1
#define HANDOFF_COUNT 4

K_THREAD_STACK_DEFINE(worker_stack, WORKER_STACK_SIZE);
static struct k_thread worker_thread;
K_SEM_DEFINE(step_sem, 0, 1);
K_SEM_DEFINE(done_sem, 0, 1);

static void worker(void *arg1, void *arg2, void *arg3)
{
	ARG_UNUSED(arg1);
	ARG_UNUSED(arg2);
	ARG_UNUSED(arg3);

	printk("AETHERCORE ZEPHYR WORKER READY\n");

	for (int i = 0; i < HANDOFF_COUNT; ++i) {
		k_sem_take(&step_sem, K_FOREVER);
		printk("AETHERCORE ZEPHYR WORKER step=%d\n", i);
	}

	k_sem_give(&done_sem);
}

int main(void)
{
	printk("AETHERCORE ZEPHYR BOOT\n");

	k_thread_create(&worker_thread,
			worker_stack,
			K_THREAD_STACK_SIZEOF(worker_stack),
			worker,
			NULL,
			NULL,
			NULL,
			K_PRIO_PREEMPT(WORKER_PRIORITY),
			0,
			K_NO_WAIT);

	for (int i = 0; i < HANDOFF_COUNT; ++i) {
		printk("AETHERCORE ZEPHYR MAIN give=%d\n", i);
		k_sem_give(&step_sem);
		k_sleep(K_MSEC(1));
	}

	k_sem_take(&done_sem, K_FOREVER);
	printk("AETHERCORE ZEPHYR PASS handoffs=%d\n", HANDOFF_COUNT);
	return 0;
}
