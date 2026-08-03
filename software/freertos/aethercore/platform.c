#include "FreeRTOS.h"
#include "task.h"
#include "platform.h"

#include <stddef.h>
#include <stdint.h>

extern uint64_t ullNextTime;
extern volatile uint64_t * pullMachineTimerCompareRegister;
extern const size_t uxTimerIncrementsForOneTick;
extern UBaseType_t const ullMachineTimerCompareRegisterBase;

volatile uint32_t aetherTicklessEntries;
volatile uint32_t aetherTicklessWakeups;
volatile uint32_t aetherTicklessSuppressedTicks;
volatile uint32_t aetherTicklessEarlyWakeups;
volatile uint32_t aetherTicklessAborts;

static uint64_t aether_read_mtime( void )
{
    volatile uint32_t * const time = ( volatile uint32_t * ) AETHERCORE_MTIME;
    uint32_t highBefore;
    uint32_t low;
    uint32_t highAfter;

    do
    {
        highBefore = time[ 1 ];
        low = time[ 0 ];
        highAfter = time[ 1 ];
    } while( highBefore != highAfter );

    return ( ( uint64_t ) highAfter << 32 ) | low;
}

static volatile uint32_t * aether_mtimecmp_for_current_hart( void )
{
    uint32_t hartId;

    __asm volatile ( "csrr %0, mhartid" : "=r" ( hartId ) );

    return ( volatile uint32_t * )
           ( ( uintptr_t ) ullMachineTimerCompareRegisterBase +
             ( ( uintptr_t ) hartId * sizeof( uint64_t ) ) );
}

static void aether_write_mtimecmp( volatile uint32_t * compare,
                                   uint64_t deadline )
{
    /* RV32 requires an ordered three-store sequence to avoid a transient
     * compare value lower than both the old and new deadlines. */
    compare[ 0 ] = UINT32_MAX;
    compare[ 1 ] = ( uint32_t ) ( deadline >> 32 );
    compare[ 0 ] = ( uint32_t ) deadline;
}

static uint32_t aether_disable_machine_interrupts( void )
{
    const uint32_t mieMask = 0x8U;
    uint32_t previousMstatus;

    __asm volatile ( "csrrc %0, mstatus, %1"
                     : "=r" ( previousMstatus )
                     : "r" ( mieMask )
                     : "memory" );
    return previousMstatus;
}

static void aether_restore_machine_interrupts( uint32_t previousMstatus )
{
    if( ( previousMstatus & 0x8U ) != 0U )
    {
        __asm volatile ( "csrsi mstatus, 8" ::: "memory" );
    }
}

static void write_unsigned( uint32_t value )
{
    char digits[ 10 ];
    size_t count = 0;

    do
    {
        digits[ count++ ] = ( char ) ( '0' + ( value % 10U ) );
        value /= 10U;
    } while( value != 0U );

    while( count != 0U )
    {
        aether_uart_putc( digits[ --count ] );
    }
}

void aether_uart_putc( char value )
{
    *( ( volatile uint8_t * ) AETHERCORE_UART_ADDRESS ) = ( uint8_t ) value;
}

void aether_uart_write( const char * text )
{
    while( *text != '\0' )
    {
        aether_uart_putc( *text++ );
    }
}

void aether_exit( uint32_t code )
{
    *( ( volatile uint32_t * ) AETHERCORE_EXIT_ADDRESS ) = code;
    for( ; ; )
    {
        __asm volatile ( "ebreak" );
    }
}

void aether_assert_fail( const char * file, int line )
{
    aether_uart_write( "FREERTOS_ASSERT " );
    aether_uart_write( file );
    aether_uart_putc( ':' );
    write_unsigned( ( uint32_t ) line );
    aether_uart_putc( '\n' );
    aether_exit( 0xa1U );
}

void vApplicationMallocFailedHook( void )
{
    aether_uart_write( "FREERTOS_MALLOC_FAILED\n" );
    aether_exit( 0xa2U );
}

void vApplicationStackOverflowHook( TaskHandle_t task, char * taskName )
{
    ( void ) task;
    aether_uart_write( "FREERTOS_STACK_OVERFLOW " );
    if( taskName != NULL )
    {
        aether_uart_write( taskName );
    }
    aether_uart_putc( '\n' );
    aether_exit( 0xa3U );
}

void vPortSetupTimerInterrupt( void )
{
    const uint64_t now = aether_read_mtime();
    const uint64_t firstDeadline = now + ( uint64_t ) uxTimerIncrementsForOneTick;
    volatile uint32_t * const compare = aether_mtimecmp_for_current_hart();

    aether_write_mtimecmp( compare, firstDeadline );

    pullMachineTimerCompareRegister = ( volatile uint64_t * ) compare;
    ullNextTime = firstDeadline + ( uint64_t ) uxTimerIncrementsForOneTick;
}

void vPortSuppressTicksAndSleep( TickType_t expectedIdleTicks )
{
    const uint64_t tickCounts = ( uint64_t ) uxTimerIncrementsForOneTick;
    volatile uint32_t * const compare = aether_mtimecmp_for_current_hart();
    uint32_t previousMstatus;
    uint64_t nextPeriodicDeadline;
    uint64_t sleepDeadline;
    uint64_t now;

    if( expectedIdleTicks < ( TickType_t ) 2U )
    {
        return;
    }

    previousMstatus = aether_disable_machine_interrupts();

    if( eTaskConfirmSleepModeStatus() == eAbortSleep )
    {
        aetherTicklessAborts++;
        aether_restore_machine_interrupts( previousMstatus );
        return;
    }

    configASSERT( tickCounts != 0U );
    configASSERT( ullNextTime >= tickCounts );

    /* ullNextTime is always one periodic interval beyond the currently armed
     * mtimecmp value. Derive the next unsuppressed tick boundary from it. */
    nextPeriodicDeadline = ullNextTime - tickCounts;
    now = aether_read_mtime();

    /* A tick became pending before the suppression window was installed. Do
     * not erase it by moving mtimecmp; restore MIE and let the normal handler
     * account for that tick. */
    if( now >= nextPeriodicDeadline )
    {
        aetherTicklessAborts++;
        aether_restore_machine_interrupts( previousMstatus );
        return;
    }

    sleepDeadline = nextPeriodicDeadline +
                    ( ( uint64_t ) ( expectedIdleTicks - 1U ) * tickCounts );
    aether_write_mtimecmp( compare, sleepDeadline );
    ullNextTime = sleepDeadline + tickCounts;

    aetherTicklessEntries++;
    __asm volatile ( "fence iorw, iorw\n\twfi" ::: "memory" );
    aetherTicklessWakeups++;

    now = aether_read_mtime();

    if( now >= sleepDeadline )
    {
        /* The pending Machine timer interrupt performs the final
         * xTaskIncrementTick() after MIE is restored. Step only the fully
         * suppressed periods here so the task at the wake deadline is released
         * exactly once by the normal interrupt path. */
        const TickType_t suppressedTicks = expectedIdleTicks - 1U;

        if( suppressedTicks != 0U )
        {
            vTaskStepTick( suppressedTicks );
            aetherTicklessSuppressedTicks += ( uint32_t ) suppressedTicks;
        }
    }
    else
    {
        /* Future external-interrupt support can wake WFI before the timer
         * deadline. Restore the first periodic boundary after 'now' and account
         * only for complete tick periods that elapsed before that early wake. */
        uint64_t elapsedTicks = 0U;
        uint64_t restoredDeadline;

        if( now >= nextPeriodicDeadline )
        {
            elapsedTicks = ( ( now - nextPeriodicDeadline ) / tickCounts ) + 1U;
        }

        configASSERT( elapsedTicks < ( uint64_t ) expectedIdleTicks );
        restoredDeadline = nextPeriodicDeadline + ( elapsedTicks * tickCounts );
        aether_write_mtimecmp( compare, restoredDeadline );
        ullNextTime = restoredDeadline + tickCounts;

        if( elapsedTicks != 0U )
        {
            vTaskStepTick( ( TickType_t ) elapsedTicks );
            aetherTicklessSuppressedTicks += ( uint32_t ) elapsedTicks;
        }

        aetherTicklessEarlyWakeups++;
    }

    aether_restore_machine_interrupts( previousMstatus );
}
