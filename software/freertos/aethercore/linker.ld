OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY
{
    ram (rwx) : ORIGIN = 0x80000000, LENGTH = 1M
}

SECTIONS
{
    . = ORIGIN(ram);

    .text : ALIGN(16)
    {
        KEEP(*(.init))
        *(.text .text.*)
    } > ram

    .rodata : ALIGN(16)
    {
        *(.srodata .srodata.*)
        *(.rodata .rodata.*)
    } > ram

    .data : ALIGN(16)
    {
        __data_start = .;
        __global_pointer$ = . + 0x800;
        *(.sdata .sdata.*)
        *(.data .data.*)
        __data_end = .;
    } > ram

    .bss (NOLOAD) : ALIGN(16)
    {
        __bss_start = .;
        *(.sbss .sbss.*)
        *(.bss .bss.*)
        *(COMMON)
        __bss_end = ALIGN(16);
    } > ram

    . = ALIGN(16);
    __image_end = .;

    __stack_bottom = ORIGIN(ram) + LENGTH(ram) - 0x4000;
    __stack_top = ORIGIN(ram) + LENGTH(ram);

    ASSERT(__image_end <= __stack_bottom, "FreeRTOS image overlaps the bootstrap stack")

    /DISCARD/ :
    {
        *(.comment)
        *(.note .note.*)
    }
}
