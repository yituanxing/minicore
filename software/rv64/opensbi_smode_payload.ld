OUTPUT_ARCH(riscv)
ENTRY(_start)

SECTIONS
{
  . = 0x80200000;
  __payload_start = .;

  .text : ALIGN(16) {
    KEEP(*(.text.start))
    *(.text .text.*)
  }

  .rodata : ALIGN(16) {
    *(.rodata .rodata.*)
  }

  .data : ALIGN(16) {
    *(.data .data.*)
  }

  .bss (NOLOAD) : ALIGN(16) {
    *(.bss .bss.* COMMON)
  }

  __payload_end = .;
  ASSERT(__payload_start == 0x80200000, "RV64 OpenSBI payload entry drifted")
  ASSERT(__payload_end < 0x80300000, "RV64 OpenSBI payload exceeded bounded 1 MiB window")
}
