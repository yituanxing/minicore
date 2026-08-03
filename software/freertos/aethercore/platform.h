#ifndef AETHERCORE_FREERTOS_PLATFORM_H
#define AETHERCORE_FREERTOS_PLATFORM_H

#include <stddef.h>
#include <stdint.h>

#define AETHERCORE_UART_ADDRESS  0x10000000UL
#define AETHERCORE_EXIT_ADDRESS  0x10000008UL
#define AETHERCORE_MTIMECMP      0x02004000UL
#define AETHERCORE_MTIME         0x0200bff8UL

void aether_uart_putc( char value );
void aether_uart_write( const char * text );
void aether_exit( uint32_t code ) __attribute__( ( noreturn ) );
void aether_assert_fail( const char * file, int line ) __attribute__( ( noreturn ) );

#endif /* AETHERCORE_FREERTOS_PLATFORM_H */
