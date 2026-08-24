OUTPUT_ARCH(riscv)
ENTRY(_start)

SECTIONS
{
  . = 0x80000000;

  .text : ALIGN(2)
  {
    KEEP(*(.text.start))
    *(.text .text.*)
  }

  .rodata : ALIGN(8)
  {
    *(.rodata .rodata.*)
  }

  .data : ALIGN(8)
  {
    *(.data .data.*)
    *(.sdata .sdata.*)
  }

  .bss : ALIGN(8)
  {
    *(.bss .bss.*)
    *(.sbss .sbss.*)
    *(COMMON)
  }

  /DISCARD/ :
  {
    *(.comment)
    *(.note*)
    *(.eh_frame*)
  }
}
