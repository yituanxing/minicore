#ifndef AETHERCORE_FREERTOS_PLATFORM_H
#define AETHERCORE_FREERTOS_PLATFORM_H

#include <stddef.h>
#include <stdint.h>

#ifndef AETHERCORE_FREERTOS_EXTERNAL_IRQ
    #define AETHERCORE_FREERTOS_EXTERNAL_IRQ 0
#endif

#define AETHERCORE_UART_ADDRESS              0x10000000UL
#define AETHERCORE_EXIT_ADDRESS              0x10000008UL
#define AETHERCORE_UART_RX_BASE              0x10000100UL
#define AETHERCORE_UART_RX_DATA              ( AETHERCORE_UART_RX_BASE + 0x0UL )
#define AETHERCORE_UART_RX_STATUS            ( AETHERCORE_UART_RX_BASE + 0x4UL )
#define AETHERCORE_UART_RX_CONTROL           ( AETHERCORE_UART_RX_BASE + 0x8UL )
#define AETHERCORE_PLIC_BASE                 0x0c000000UL
#define AETHERCORE_PLIC_SOURCE1_PRIORITY     ( AETHERCORE_PLIC_BASE + 0x000004UL )
#define AETHERCORE_PLIC_ENABLE               ( AETHERCORE_PLIC_BASE + 0x002000UL )
#define AETHERCORE_PLIC_THRESHOLD            ( AETHERCORE_PLIC_BASE + 0x200000UL )
#define AETHERCORE_PLIC_CLAIM_COMPLETE       ( AETHERCORE_PLIC_BASE + 0x200004UL )
#define AETHERCORE_UART_RX_SOURCE_ID         1UL
#define AETHERCORE_MIE_MEIE                  0x00000800UL
#define AETHERCORE_MTIMECMP                  0x02004000UL
#define AETHERCORE_MTIME                     0x0200bff8UL

extern volatile uint32_t aetherTicklessEntries;
extern volatile uint32_t aetherTicklessWakeups;
extern volatile uint32_t aetherTicklessSuppressedTicks;
extern volatile uint32_t aetherTicklessEarlyWakeups;
extern volatile uint32_t aetherTicklessAborts;
extern volatile uint32_t aetherUartRxInterrupts;
extern volatile uint32_t aetherUartRxBytes;
extern volatile uint32_t aetherUartRxYields;
extern volatile uint32_t aetherUartRxSemaphoreSignals;
extern volatile uint32_t aetherUartRxNotifications;

void aether_uart_putc( char value );
void aether_uart_write( const char * text );
void aether_uart_rx_start( void * queue,
                           void * semaphore,
                           void * notificationTask );
void aether_exit( uint32_t code ) __attribute__( ( noreturn ) );
void aether_assert_fail( const char * file, int line ) __attribute__( ( noreturn ) );

#endif /* AETHERCORE_FREERTOS_PLATFORM_H */
