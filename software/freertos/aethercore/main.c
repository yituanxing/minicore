#include "FreeRTOS.h"
#include "queue.h"
#include "semphr.h"
#include "task.h"
#include "platform.h"

#include <stdint.h>

#define MESSAGE_COUNT              64U
#define EXPECTED_SUM               ( ( MESSAGE_COUNT * ( MESSAGE_COUNT + 1U ) ) / 2U )
#define TICKLESS_PROOF_DELAY_TICKS 32U
#define MINIMUM_SUPPRESSED_TICKS    2U
#define EXPECTED_UART_RX_BYTE       0x5aU
#define UART_ISR_COMPLETION_COUNT   3U
#define UART_ISR_TASK_PRIORITY      4U
#define MUTEX_LOW_TASK_PRIORITY     1U
#define MUTEX_HIGH_TASK_PRIORITY    4U

static QueueHandle_t messageQueue;
static SemaphoreHandle_t batchSemaphore;

static volatile uint32_t producedCount;
static volatile uint32_t consumedCount;
static volatile uint32_t consumedSum;
static volatile uint32_t producerDone;
static volatile uint32_t consumerDone;

#if AETHERCORE_FREERTOS_EXTERNAL_IRQ
    static QueueHandle_t uartRxQueue;
    static SemaphoreHandle_t uartRxSignalSemaphore;
    static SemaphoreHandle_t uartRxDoneSemaphore;
    static TaskHandle_t uartRxNotificationTaskHandle;
    static SemaphoreHandle_t priorityInheritanceMutex;
    static SemaphoreHandle_t priorityInheritanceReady;
    static SemaphoreHandle_t priorityInheritanceDone;
    static volatile uint32_t uartRxQueueTaskDone;
    static volatile uint32_t uartRxSemaphoreTaskDone;
    static volatile uint32_t uartRxNotificationTaskDone;
    static volatile uint32_t uartRxObservedByte;
    static volatile uint32_t inheritedMutexPriority;
    static volatile uint32_t mutexLowTaskDone;
    static volatile uint32_t mutexHighTaskDone;
#endif

static void producer_task( void * context )
{
    ( void ) context;

    for( uint32_t value = 1; value <= MESSAGE_COUNT; ++value )
    {
        configASSERT( xQueueSend( messageQueue, &value, portMAX_DELAY ) == pdPASS );
        producedCount = value;

        if( ( value % 8U ) == 0U )
        {
            configASSERT( xSemaphoreGive( batchSemaphore ) == pdPASS );
        }

        if( ( value % 4U ) == 0U )
        {
            vTaskDelay( 1 );
        }
        else
        {
            taskYIELD();
        }
    }

    producerDone = 1U;
    vTaskDelete( NULL );
}

static void consumer_task( void * context )
{
    ( void ) context;

    for( uint32_t index = 1; index <= MESSAGE_COUNT; ++index )
    {
        uint32_t value = 0;
        configASSERT( xQueueReceive( messageQueue, &value, portMAX_DELAY ) == pdPASS );
        configASSERT( value == index );

        consumedSum += value;
        consumedCount = index;

        if( ( index % 8U ) == 0U )
        {
            configASSERT( xSemaphoreTake( batchSemaphore, portMAX_DELAY ) == pdPASS );
        }
    }

    consumerDone = 1U;
    vTaskDelete( NULL );
}

#if AETHERCORE_FREERTOS_EXTERNAL_IRQ
static void uart_rx_queue_task( void * context )
{
    uint8_t byte = 0U;

    ( void ) context;
    configASSERT( xQueueReceive( uartRxQueue, &byte, portMAX_DELAY ) == pdPASS );
    configASSERT( byte == EXPECTED_UART_RX_BYTE );

    uartRxObservedByte = byte;
    uartRxQueueTaskDone = 1U;
    configASSERT( xSemaphoreGive( uartRxDoneSemaphore ) == pdPASS );
    vTaskDelete( NULL );
}

static void uart_rx_semaphore_task( void * context )
{
    ( void ) context;
    configASSERT(
        xSemaphoreTake( uartRxSignalSemaphore, portMAX_DELAY ) == pdPASS );

    uartRxSemaphoreTaskDone = 1U;
    configASSERT( xSemaphoreGive( uartRxDoneSemaphore ) == pdPASS );
    vTaskDelete( NULL );
}

static void uart_rx_notification_task( void * context )
{
    ( void ) context;
    configASSERT( ulTaskNotifyTake( pdTRUE, portMAX_DELAY ) == 1U );

    uartRxNotificationTaskDone = 1U;
    configASSERT( xSemaphoreGive( uartRxDoneSemaphore ) == pdPASS );
    vTaskDelete( NULL );
}

static void mutex_high_task( void * context )
{
    ( void ) context;
    configASSERT(
        xSemaphoreTake( priorityInheritanceReady, portMAX_DELAY ) == pdPASS );
    configASSERT(
        xSemaphoreTake( priorityInheritanceMutex, portMAX_DELAY ) == pdPASS );

    mutexHighTaskDone = 1U;
    configASSERT( xSemaphoreGive( priorityInheritanceMutex ) == pdPASS );
    configASSERT( xSemaphoreGive( priorityInheritanceDone ) == pdPASS );
    vTaskDelete( NULL );
}

static void mutex_low_task( void * context )
{
    ( void ) context;
    configASSERT(
        xSemaphoreTake( priorityInheritanceMutex, portMAX_DELAY ) == pdPASS );

    /* Giving the ready semaphore preempts to the high-priority task. That task
     * blocks on the held mutex, so execution returns here only after FreeRTOS
     * has inherited MUTEX_HIGH_TASK_PRIORITY into this low-priority owner. */
    configASSERT( xSemaphoreGive( priorityInheritanceReady ) == pdPASS );
    inheritedMutexPriority = ( uint32_t ) uxTaskPriorityGet( NULL );
    configASSERT( inheritedMutexPriority == MUTEX_HIGH_TASK_PRIORITY );

    mutexLowTaskDone = 1U;
    configASSERT( xSemaphoreGive( priorityInheritanceMutex ) == pdPASS );
    vTaskDelete( NULL );
}
#endif

static void monitor_task( void * context )
{
    ( void ) context;

    while( ( producerDone == 0U ) || ( consumerDone == 0U ) )
    {
        vTaskDelay( 1 );
    }

#if AETHERCORE_FREERTOS_EXTERNAL_IRQ
    configASSERT(
        xSemaphoreTake( priorityInheritanceDone, portMAX_DELAY ) == pdPASS );
    configASSERT( mutexLowTaskDone == 1U );
    configASSERT( mutexHighTaskDone == 1U );
    configASSERT( inheritedMutexPriority == MUTEX_HIGH_TASK_PRIORITY );

    /* Blocking on a counting semaphore leaves every application task asleep,
     * so the idle task can enter Tickless WFI before the simulator injects one
     * UART byte. The ISR must wake three independent kernel object paths. */
    for( uint32_t completion = 0U;
         completion < UART_ISR_COMPLETION_COUNT;
         completion++ )
    {
        configASSERT(
            xSemaphoreTake( uartRxDoneSemaphore, portMAX_DELAY ) == pdPASS );
    }
#endif

    /* With every application task blocked for a long interval, the idle task
     * must suppress periodic ticks, execute WFI with MIE masked, wake from the
     * raw interrupt request, compensate skipped kernel ticks, and then release
     * this task at its original deadline. */
    vTaskDelay( TICKLESS_PROOF_DELAY_TICKS );

    const TickType_t ticks = xTaskGetTickCount();
    configASSERT( producedCount == MESSAGE_COUNT );
    configASSERT( consumedCount == MESSAGE_COUNT );
    configASSERT( consumedSum == EXPECTED_SUM );
    configASSERT( ticks >= ( TickType_t ) ( 16U + TICKLESS_PROOF_DELAY_TICKS ) );
    configASSERT( aetherTicklessEntries >= 1U );
    configASSERT( aetherTicklessWakeups >= 1U );
    configASSERT( aetherTicklessSuppressedTicks >= MINIMUM_SUPPRESSED_TICKS );

#if AETHERCORE_FREERTOS_EXTERNAL_IRQ
    configASSERT( uartRxObservedByte == EXPECTED_UART_RX_BYTE );
    configASSERT( uartRxQueueTaskDone == 1U );
    configASSERT( uartRxSemaphoreTaskDone == 1U );
    configASSERT( uartRxNotificationTaskDone == 1U );
    configASSERT( aetherUartRxInterrupts == 1U );
    configASSERT( aetherUartRxBytes == 1U );
    configASSERT( aetherUartRxSemaphoreSignals == 1U );
    configASSERT( aetherUartRxNotifications == 1U );
    configASSERT( aetherUartRxYields >= 1U );
    configASSERT( aetherTicklessEarlyWakeups >= 1U );
    aether_uart_write( "FREERTOS MUTEX PASS inherited=4\n" );
    aether_uart_write(
        "FREERTOS IRQ PASS queue=1 semaphore=1 notify=1 claim=1 yield>=1 early>=1\n" );
#endif

    aether_uart_write( "FREERTOS TICKLESS PASS sleep>=1 wake>=1 suppressed>=2\n" );
    aether_uart_write( "FREERTOS PASS queue=64 semaphore=8 ticks>=48\n" );
    aether_exit( 0U );
}

int main( void )
{
    aether_uart_write( "FREERTOS BOOT V11.3.0 RV32IM\n" );

    messageQueue = xQueueCreate( 4, sizeof( uint32_t ) );
    batchSemaphore = xSemaphoreCreateBinary();
    configASSERT( messageQueue != NULL );
    configASSERT( batchSemaphore != NULL );

#if AETHERCORE_FREERTOS_EXTERNAL_IRQ
    uartRxQueue = xQueueCreate( 4, sizeof( uint8_t ) );
    uartRxSignalSemaphore = xSemaphoreCreateBinary();
    uartRxDoneSemaphore =
        xSemaphoreCreateCounting( UART_ISR_COMPLETION_COUNT, 0U );
    priorityInheritanceMutex = xSemaphoreCreateMutex();
    priorityInheritanceReady = xSemaphoreCreateBinary();
    priorityInheritanceDone = xSemaphoreCreateBinary();
    configASSERT( uartRxQueue != NULL );
    configASSERT( uartRxSignalSemaphore != NULL );
    configASSERT( uartRxDoneSemaphore != NULL );
    configASSERT( priorityInheritanceMutex != NULL );
    configASSERT( priorityInheritanceReady != NULL );
    configASSERT( priorityInheritanceDone != NULL );

    configASSERT(
        xTaskCreate( uart_rx_queue_task,
                     "uart-q",
                     256,
                     NULL,
                     UART_ISR_TASK_PRIORITY,
                     NULL ) == pdPASS );
    configASSERT(
        xTaskCreate( uart_rx_semaphore_task,
                     "uart-sem",
                     256,
                     NULL,
                     UART_ISR_TASK_PRIORITY,
                     NULL ) == pdPASS );
    configASSERT(
        xTaskCreate( uart_rx_notification_task,
                     "uart-notify",
                     256,
                     NULL,
                     UART_ISR_TASK_PRIORITY,
                     &uartRxNotificationTaskHandle ) == pdPASS );
    configASSERT( uartRxNotificationTaskHandle != NULL );
    configASSERT(
        xTaskCreate( mutex_high_task,
                     "mutex-high",
                     256,
                     NULL,
                     MUTEX_HIGH_TASK_PRIORITY,
                     NULL ) == pdPASS );
    configASSERT(
        xTaskCreate( mutex_low_task,
                     "mutex-low",
                     256,
                     NULL,
                     MUTEX_LOW_TASK_PRIORITY,
                     NULL ) == pdPASS );

    aether_uart_rx_start( uartRxQueue,
                          uartRxSignalSemaphore,
                          uartRxNotificationTaskHandle );
    aether_uart_write( "FREERTOS IRQ ARMED\n" );
#endif

    configASSERT(
        xTaskCreate( consumer_task, "consumer", 256, NULL, 3, NULL ) == pdPASS );
    configASSERT(
        xTaskCreate( producer_task, "producer", 256, NULL, 2, NULL ) == pdPASS );
    configASSERT(
        xTaskCreate( monitor_task, "monitor", 256, NULL, 1, NULL ) == pdPASS );

    vTaskStartScheduler();
    aether_uart_write( "FREERTOS SCHEDULER RETURNED\n" );
    aether_exit( 0xa4U );
}
