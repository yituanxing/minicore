#include <stddef.h>
#include <stdint.h>

void * memcpy( void * destination, const void * source, size_t bytes )
{
    uint8_t * out = ( uint8_t * ) destination;
    const uint8_t * in = ( const uint8_t * ) source;

    for( size_t index = 0; index < bytes; ++index )
    {
        out[ index ] = in[ index ];
    }
    return destination;
}

void * memmove( void * destination, const void * source, size_t bytes )
{
    uint8_t * out = ( uint8_t * ) destination;
    const uint8_t * in = ( const uint8_t * ) source;

    if( out <= in )
    {
        for( size_t index = 0; index < bytes; ++index )
        {
            out[ index ] = in[ index ];
        }
    }
    else
    {
        while( bytes != 0 )
        {
            --bytes;
            out[ bytes ] = in[ bytes ];
        }
    }
    return destination;
}

void * memset( void * destination, int value, size_t bytes )
{
    uint8_t * out = ( uint8_t * ) destination;

    for( size_t index = 0; index < bytes; ++index )
    {
        out[ index ] = ( uint8_t ) value;
    }
    return destination;
}

int memcmp( const void * left, const void * right, size_t bytes )
{
    const uint8_t * first = ( const uint8_t * ) left;
    const uint8_t * second = ( const uint8_t * ) right;

    for( size_t index = 0; index < bytes; ++index )
    {
        if( first[ index ] != second[ index ] )
        {
            return ( int ) first[ index ] - ( int ) second[ index ];
        }
    }
    return 0;
}

size_t strlen( const char * text )
{
    size_t length = 0;
    while( text[ length ] != '\0' )
    {
        ++length;
    }
    return length;
}
