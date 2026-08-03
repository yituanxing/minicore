#ifndef AETHERCORE_FREESTANDING_STRING_H
#define AETHERCORE_FREESTANDING_STRING_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

void * memcpy( void * destination, const void * source, size_t bytes );
void * memmove( void * destination, const void * source, size_t bytes );
void * memset( void * destination, int value, size_t bytes );
int memcmp( const void * left, const void * right, size_t bytes );
size_t strlen( const char * text );

#ifdef __cplusplus
}
#endif

#endif /* AETHERCORE_FREESTANDING_STRING_H */
