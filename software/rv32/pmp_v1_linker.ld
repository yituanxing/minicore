OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY {
  RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 64M
}

SECTIONS {
  .text.machine ORIGIN(RAM) : ALIGN(4) {
    KEEP(*(.text.init))
    *(.text.machine)
    *(.text)
  } > RAM
  ASSERT(. <= 0x80001000, "PMP V1 machine text exceeds denied machine region")

  /*
   * Give each PMP boundary as the output section's VMA. A bare assignment to
   * `.` does not advance ld's per-MEMORY-region allocation cursor, so a later
   * `> RAM` section can otherwise be packed immediately after the previous
   * region instead of beginning at the intended protection boundary.
   */
  .text.user 0x80001000 : ALIGN(4) {
    *(.text.user)
  } > RAM
  ASSERT(. <= 0x80002000, "PMP V1 user text exceeds RX region")

  .data.user 0x80002000 : ALIGN(8) {
    *(.data.user)
    *(.rodata .rodata.*)
    *(.data .data.*)
    *(.sdata .sdata.*)
  } > RAM
  ASSERT(. <= 0x80003000, "PMP V1 user data exceeds RW region")

  .bss 0x80003000 (NOLOAD) : ALIGN(8) {
    __bss_start = .;
    *(.sbss .sbss.*)
    *(.bss .bss.*)
    *(COMMON)
    . = ALIGN(8);
    __bss_end = .;
  } > RAM

  . = ALIGN(16);
  __image_end = .;
  __stack_top = 0x80004000;

  /DISCARD/ : {
    *(.comment)
    *(.note*)
    *(.eh_frame*)
    *(.riscv.attributes)
  }
}

ASSERT(__image_end <= __stack_top, "PMP V1 image overlaps machine bootstrap stack")
