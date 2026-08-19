OUTPUT_ARCH(riscv)
ENTRY(_start)

MEMORY {
  RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 64M
}

SECTIONS {
  .text.machine 0x80000000 : {
    KEEP(*(.text.init))
    *(.text.machine .text.machine.*)
  } > RAM

  .text.supervisor 0x80001000 : {
    *(.text.supervisor .text.supervisor.*)
  } > RAM

  .text.user 0x80002000 : {
    *(.text.user .text.user.*)
  } > RAM

  .data.user 0x80003000 : {
    . = ALIGN(8);
    *(.data.user .data.user.*)
  } > RAM

  .sv39.root 0x80004000 (NOLOAD) : {
    __sv39_root = .;
    . += 0x1000;
  } > RAM

  .sv39.level1 0x80005000 (NOLOAD) : {
    __sv39_level1 = .;
    . += 0x1000;
  } > RAM

  .sv39.level0 0x80006000 (NOLOAD) : {
    __sv39_level0 = .;
    . += 0x1000;
  } > RAM

  /DISCARD/ : {
    *(.comment)
    *(.note*)
    *(.eh_frame*)
    *(.riscv.attributes)
  }
}

ASSERT(SIZEOF(.text.machine) <= 0x1000, "Sv39 V1 machine text exceeds its page")
ASSERT(SIZEOF(.text.supervisor) <= 0x1000, "Sv39 V1 supervisor text exceeds its page")
ASSERT(SIZEOF(.text.user) <= 0x1000, "Sv39 V1 user text exceeds its page")
ASSERT(SIZEOF(.data.user) <= 0x1000, "Sv39 V1 user data exceeds its page")
