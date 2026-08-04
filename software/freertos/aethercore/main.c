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

static QueueHandle_t messageQueue;
static SemaphoreHandle_t batchSemaphore;

static volatile uint32_t producedCount;
static volatile uint32_t consumedCount;
static volatile uint32_t consumedSum;
static volatile uint32_t producerDone;
static volatile uint32_t consumerDone;

#if AETHERCORE_FREERTOS_EXTERNAL_IRQ
    static QueueHandle_t uartRxQueue;
    static volatile uint32_t uartRxTaskDone;
    static volatile uint32_t uartRxObservedByte;
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
static void uart_rx_task( void * context )
{
    uint8_t byte = 0U;

    ( void ) context;
    configASSERT( xQueueReceive( uartRxQueue, &byte, portMAX_DELAY ) == pdPASS );
    configASSERT( byte == EXPECTED_UART_RX_BYTE );

    uartRxObservedByte = byte;
    uartRxTaskDone = 1U;
    vTaskDelete( NULL );
}
#endif

static void monitor_task( void * context )
{
    ( void ) context;

    while( ( producerDone == 0U ) || ( consumerDone == 0U )
#if AETHERCORE_FREERTOS_EXTERNAL_IRQ
           || ( uartRxTaskDone == 0U )
#endif
         )
    {
        vTaskDelay( 1 );
    }

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
    configASSERT( aetherUartRxInterrupts == 1U );
    configASSERT( aetherUartRxBytes == 1U );
    configASSERT( aetherUartRxYields >= 1U );
    configASSERT( aetherTicklessEarlyWakeups >= 1U );
    aether_uart_write( "FREERTOS IRQ PASS rx=1 claim=1 yield>=1 early>=1\n" );
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
    configASSERT( uartRxQueue != NULL );
    aether_uart_rx_start( uartRxQueue );
    configASSERT(
        xTaskCreate( uart_rx_task, "uart-rx", 256, NULL, 4, NULL ) == pdPASS );
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
