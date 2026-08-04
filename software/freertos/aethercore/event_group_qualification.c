#include "FreeRTOS.h"
#include "event_groups.h"
#include "task.h"
#include "timers.h"
#include "platform.h"

#include <stdint.h>

#if AETHERCORE_FREERTOS_EXTERNAL_IRQ

#define EVENT_BIT_FIRST   ( 1UL << 0 )
#define EVENT_BIT_SECOND  ( 1UL << 1 )
#define EVENT_BITS_ALL    ( EVENT_BIT_FIRST | EVENT_BIT_SECOND )
#define EVENT_TASK_PRIORITY 3U
#define EVENT_WAITER_PRIORITY 4U
#define KERNEL_OBJECT_REPORT_PRIORITY 2U
#define SOFTWARE_TIMER_PERIOD_TICKS 7U

static EventGroupHandle_t qualificationEventGroup;
static TimerHandle_t qualificationSoftwareTimer;
static volatile uint32_t eventGroupProducerDone;
static volatile uint32_t eventGroupWaiterDone;

volatile uint32_t aetherEventGroupDone;
volatile uint32_t aetherSoftwareTimerDone;
extern volatile uint32_t aetherStreamBufferDone;
extern volatile uint32_t aetherMessageBufferDone;

void aether_start_buffer_qualification( void );

static void software_timer_callback( TimerHandle_t timer )
{
    configASSERT( timer == qualificationSoftwareTimer );
    configASSERT( xTimerIsTimerActive( timer ) == pdFALSE );
    configASSERT( aetherSoftwareTimerDone == 0U );

    aetherSoftwareTimerDone = 1U;
}

static void event_group_waiter_task( void * context )
{
    EventBits_t observed;

    ( void ) context;
    observed = xEventGroupWaitBits( qualificationEventGroup,
                                    EVENT_BITS_ALL,
                                    pdTRUE,
                                    pdTRUE,
                                    portMAX_DELAY );

    configASSERT( ( observed & EVENT_BITS_ALL ) == EVENT_BITS_ALL );
    configASSERT(
        ( xEventGroupGetBits( qualificationEventGroup ) & EVENT_BITS_ALL ) == 0U );
    configASSERT( eventGroupProducerDone == 1U );

    eventGroupWaiterDone = 1U;
    aetherEventGroupDone = 1U;
    vTaskDelete( NULL );
}

static void event_group_producer_task( void * context )
{
    EventBits_t result;

    ( void ) context;
    result = xEventGroupSetBits( qualificationEventGroup, EVENT_BIT_FIRST );
    configASSERT( ( result & EVENT_BIT_FIRST ) != 0U );
    configASSERT( eventGroupWaiterDone == 0U );

    taskYIELD();

    eventGroupProducerDone = 1U;

    /*
     * Setting the second bit releases the higher-priority waiter. Because that
     * waiter requested clear-on-exit, it may run and clear both bits before
     * xEventGroupSetBits() returns to this producer. The waiter-side checks are
     * therefore the architectural proof; the returned bit mask is not stable
     * across the required priority handoff.
     */
    ( void ) xEventGroupSetBits( qualificationEventGroup, EVENT_BIT_SECOND );

    vTaskDelete( NULL );
}

static void kernel_object_report_task( void * context )
{
    ( void ) context;

    while( ( aetherEventGroupDone == 0U ) ||
           ( aetherSoftwareTimerDone == 0U ) ||
           ( aetherStreamBufferDone == 0U ) ||
           ( aetherMessageBufferDone == 0U ) )
    {
        vTaskDelay( 1 );
    }

    configASSERT( aetherEventGroupDone == 1U );
    configASSERT( aetherSoftwareTimerDone == 1U );
    configASSERT( aetherStreamBufferDone == 1U );
    configASSERT( aetherMessageBufferDone == 1U );

    /* Emit one serialized evidence block so independent tasks cannot interleave
     * UART bytes and corrupt the line-oriented gate contract. */
    aether_uart_write( "FREERTOS EVENT GROUP PASS all=3 clear=1\n" );
    aether_uart_write( "FREERTOS SOFTWARE TIMER PASS one-shot=1 daemon=1\n" );
    aether_uart_write( "FREERTOS STREAM BUFFER PASS bytes=8 handoff=1\n" );
    aether_uart_write( "FREERTOS MESSAGE BUFFER PASS bytes=7 handoff=1\n" );
    vTaskDelete( NULL );
}

void vApplicationDaemonTaskStartupHook( void )
{
    qualificationEventGroup = xEventGroupCreate();
    configASSERT( qualificationEventGroup != NULL );

    qualificationSoftwareTimer =
        xTimerCreate( "one-shot",
                      SOFTWARE_TIMER_PERIOD_TICKS,
                      pdFALSE,
                      NULL,
                      software_timer_callback );
    configASSERT( qualificationSoftwareTimer != NULL );
    configASSERT( xTimerStart( qualificationSoftwareTimer, 0U ) == pdPASS );

    configASSERT(
        xTaskCreate( event_group_waiter_task,
                     "event-wait",
                     256,
                     NULL,
                     EVENT_WAITER_PRIORITY,
                     NULL ) == pdPASS );
    configASSERT(
        xTaskCreate( event_group_producer_task,
                     "event-set",
                     256,
                     NULL,
                     EVENT_TASK_PRIORITY,
                     NULL ) == pdPASS );
    configASSERT(
        xTaskCreate( kernel_object_report_task,
                     "object-report",
                     256,
                     NULL,
                     KERNEL_OBJECT_REPORT_PRIORITY,
                     NULL ) == pdPASS );

    aether_start_buffer_qualification();
}

#endif
