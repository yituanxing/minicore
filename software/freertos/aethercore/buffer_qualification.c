#include "FreeRTOS.h"
#include "message_buffer.h"
#include "stream_buffer.h"
#include "task.h"
#include "platform.h"

#include <stddef.h>
#include <stdint.h>

#if AETHERCORE_FREERTOS_EXTERNAL_IRQ

#define BUFFER_RECEIVER_PRIORITY 4U
#define BUFFER_SENDER_PRIORITY   3U
#define BUFFER_TASK_STACK_WORDS  256U
#define STREAM_TRIGGER_BYTES     8U
#define MESSAGE_STORAGE_BYTES    64U

static const uint8_t streamPayload[ STREAM_TRIGGER_BYTES ] =
{
    0x11U, 0x22U, 0x33U, 0x44U, 0x55U, 0x66U, 0x77U, 0x88U
};
static const uint8_t messagePayload[] =
{
    0xa1U, 0xb2U, 0xc3U, 0xd4U, 0xe5U, 0xf6U, 0x07U
};

static StreamBufferHandle_t qualificationStreamBuffer;
static MessageBufferHandle_t qualificationMessageBuffer;
static volatile uint32_t streamReceiverDone;
static volatile uint32_t messageReceiverDone;

volatile uint32_t aetherStreamBufferDone;
volatile uint32_t aetherMessageBufferDone;

static void stream_receiver_task( void * context )
{
    uint8_t received[ STREAM_TRIGGER_BYTES ] = { 0U };
    size_t count;

    ( void ) context;
    count = xStreamBufferReceive( qualificationStreamBuffer,
                                  received,
                                  sizeof( received ),
                                  portMAX_DELAY );
    configASSERT( count == sizeof( streamPayload ) );

    for( size_t index = 0U; index < sizeof( streamPayload ); index++ )
    {
        configASSERT( received[ index ] == streamPayload[ index ] );
    }

    streamReceiverDone = 1U;
    vTaskDelete( NULL );
}

static void stream_sender_task( void * context )
{
    size_t sent;

    ( void ) context;
    sent = xStreamBufferSend( qualificationStreamBuffer,
                              streamPayload,
                              sizeof( streamPayload ),
                              portMAX_DELAY );
    configASSERT( sent == sizeof( streamPayload ) );

    /* The higher-priority receiver must run before this sender resumes. */
    configASSERT( streamReceiverDone == 1U );
    aetherStreamBufferDone = 1U;
    aether_uart_write( "FREERTOS STREAM BUFFER PASS bytes=8 handoff=1\n" );
    vTaskDelete( NULL );
}

static void message_receiver_task( void * context )
{
    uint8_t received[ 16 ] = { 0U };
    size_t count;

    ( void ) context;
    count = xMessageBufferReceive( qualificationMessageBuffer,
                                   received,
                                   sizeof( received ),
                                   portMAX_DELAY );
    configASSERT( count == sizeof( messagePayload ) );

    for( size_t index = 0U; index < sizeof( messagePayload ); index++ )
    {
        configASSERT( received[ index ] == messagePayload[ index ] );
    }

    messageReceiverDone = 1U;
    vTaskDelete( NULL );
}

static void message_sender_task( void * context )
{
    size_t sent;

    ( void ) context;
    sent = xMessageBufferSend( qualificationMessageBuffer,
                               messagePayload,
                               sizeof( messagePayload ),
                               portMAX_DELAY );
    configASSERT( sent == sizeof( messagePayload ) );

    /* Message framing must unblock and run the higher-priority receiver. */
    configASSERT( messageReceiverDone == 1U );
    aetherMessageBufferDone = 1U;
    aether_uart_write( "FREERTOS MESSAGE BUFFER PASS bytes=7 handoff=1\n" );
    vTaskDelete( NULL );
}

void aether_start_buffer_qualification( void )
{
    qualificationStreamBuffer =
        xStreamBufferCreate( 32U, STREAM_TRIGGER_BYTES );
    qualificationMessageBuffer = xMessageBufferCreate( MESSAGE_STORAGE_BYTES );
    configASSERT( qualificationStreamBuffer != NULL );
    configASSERT( qualificationMessageBuffer != NULL );

    configASSERT(
        xTaskCreate( stream_receiver_task,
                     "stream-rx",
                     BUFFER_TASK_STACK_WORDS,
                     NULL,
                     BUFFER_RECEIVER_PRIORITY,
                     NULL ) == pdPASS );
    configASSERT(
        xTaskCreate( message_receiver_task,
                     "message-rx",
                     BUFFER_TASK_STACK_WORDS,
                     NULL,
                     BUFFER_RECEIVER_PRIORITY,
                     NULL ) == pdPASS );
    configASSERT(
        xTaskCreate( stream_sender_task,
                     "stream-tx",
                     BUFFER_TASK_STACK_WORDS,
                     NULL,
                     BUFFER_SENDER_PRIORITY,
                     NULL ) == pdPASS );
    configASSERT(
        xTaskCreate( message_sender_task,
                     "message-tx",
                     BUFFER_TASK_STACK_WORDS,
                     NULL,
                     BUFFER_SENDER_PRIORITY,
                     NULL ) == pdPASS );
}

#endif
