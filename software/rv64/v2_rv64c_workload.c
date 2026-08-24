__attribute__((noinline))
long rv64c_workload(long value)
{
    value += 7;
    __asm__ volatile ("" : "+r"(value));

    value ^= 3;
    __asm__ volatile ("" : "+r"(value));

    value *= 5;
    __asm__ volatile ("" : "+r"(value));

    value += 9;
    __asm__ volatile ("" : "+r"(value));

    return value;
}
