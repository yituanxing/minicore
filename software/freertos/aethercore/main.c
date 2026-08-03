#include "FreeRTOS.h"
#include "queue.h"
#include "semphr.h"
#include "task.h"
#include "platform.h"

#include <stdint.h>

#define MESSAGE_COUNT 64U
#define EXPECTED_SUM  ( ( MESSAGE_COUNT * ( MESSAGE_COUNT + 1U ) ) / 2U )

static QueueHandle_t messageQueue;
static SemaphoreHandle_t batchSemaphore;

static volatile uint32_t producedCount;
static volatile uint32_t consumedCount;
static volatile uint32_t consumedSum;
static volatile uint32_t producerDone;
static volatile uint32_t consumerDone;

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

static void monitor_task( void * context )
{
    ( void ) context;

    while( ( producerDone == 0U ) || ( consumerDone == 0U ) )
    {
        vTaskDelay( 1 );
    }

    const TickType_t ticks = xTaskGetTickCount();
    configASSERT( producedCount == MESSAGE_COUNT );
    configASSERT( consumedCount == MESSAGE_COUNT );
    configASSERT( consumedSum == EXPECTED_SUM );
    configASSERT( ticks >= 16U );

    aether_uart_write( "FREERTOS PASS queue=64 semaphore=8 ticks>=16\n" );
    aether_exit( 0U );
}

int main( void )
{
    aether_uart_write( "FREERTOS BOOT V11.3.0 RV32IM\n" );

    messageQueue = xQueueCreate( 4, sizeof( uint32_t ) );
    batchSemaphore = xSemaphoreCreateBinary();
    configASSERT( messageQueue != NULL );
    configASSERT( batchSemaphore != NULL );

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
