/* Fixed-address, synthetic C1 P0 RAM-only image for the actual Product RTL.
 * Keep the recovery path and state near each other for AUIPC PC-relative ld.
 * These are diagnostic addresses, not a generic Linux placement contract.
 */
OUTPUT_ARCH(riscv)
ENTRY(aether_c1_arch_restore)

SECTIONS
{
  . = 0x80000000;
  .text.c1_arch_restore : { KEEP(*(.text.c1_arch_restore)) }

  . = 0x80018000;
  .data.c1_arch_state : { KEEP(*(.data.c1_arch_state)) }

  . = 0x80100000;
  .text.c1_p0_target : { KEEP(*(.text.c1_p0_target)) }

  . = 0x80101000;
  .data.c1_p0_markers : { KEEP(*(.data.c1_p0_markers)) }

  /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) }
}

ASSERT(ADDR(.text.c1_arch_restore) == 0x80000000, "P0 restore entry moved")
ASSERT(ADDR(.text.c1_p0_target) == 0x80100000, "P0 S-mode target moved")
ASSERT(ADDR(.data.c1_arch_state) == 0x80018000, "P0 state address moved")
ASSERT(SIZEOF(.data.c1_arch_state) == 424, "P0 state ABI size changed")
ASSERT(ADDR(.data.c1_p0_markers) == 0x80101000, "P0 markers moved")
ASSERT(SIZEOF(.data.c1_p0_markers) == 32, "P0 markers size changed")
