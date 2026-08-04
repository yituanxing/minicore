#include "FreeRTOS.h"
#include "event_groups.h"
#include "task.h"
#include "platform.h"

#include <stdint.h>

#if AETHERCORE_FREERTOS_EXTERNAL_IRQ

#define EVENT_BIT_FIRST   ( 1UL << 0 )
#define EVENT_BIT_SECOND  ( 1UL << 1 )
#define EVENT_BITS_ALL    ( EVENT_BIT_FIRST | EVENT_BIT_SECOND )
#define EVENT_TASK_PRIORITY 3U
#define EVENT_WAITER_PRIORITY 4U

static EventGroupHandle_t qualificationEventGroup;
static volatile uint32_t eventGroupProducerDone;
static volatile uint32_t eventGroupWaiterDone;

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
    aether_uart_write( "FREERTOS EVENT GROUP PASS all=3 clear=1\n" );
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

void vApplicationDaemonTaskStartupHook( void )
{
    qualificationEventGroup = xEventGroupCreate();
    configASSERT( qualificationEventGroup != NULL );

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
}

#endif
