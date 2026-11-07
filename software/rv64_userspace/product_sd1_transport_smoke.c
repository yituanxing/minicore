/* A bounded, freestanding Product SD1 *transport* exerciser. Not Linux/PID1.
 *
 * The same RV64 core, semantic-memory fabric, SPI GPIO CS and pin-level SDHC
 * model used by G5 execute these ordinary MMIO instructions. In particular,
 * the SDHC response is obtained through MOSI/SCLK/MISO, not a host-side block
 * device shortcut.  PLIC source 11 is observed through its real pending word.
 */
#include <stdint.h>

#define UART_BASE ((uintptr_t)0x10000000u)
#define SPI_BASE ((uintptr_t)0x10001000u)
#define GPIO_BASE ((uintptr_t)0x10002000u)
#define PLIC_PENDING ((uintptr_t)0x0c001000u)

static volatile uint8_t *const uart = (volatile uint8_t *)UART_BASE;
static volatile uint8_t *const spi = (volatile uint8_t *)SPI_BASE;
static volatile uint32_t *const gpio = (volatile uint32_t *)GPIO_BASE;

static void send_text(const char *message) {
  while (*message) {
    unsigned budget = 100000;
    while ((uart[5] & 0x20u) == 0u && --budget) {}
    if (!budget) break;
    uart[0] = (uint8_t)*message++;
  }
}

__attribute__((noreturn)) static void stop(void) {
  for (;;) __asm__ volatile("wfi");
}

static uint8_t exchange(uint8_t byte) {
  unsigned budget = 100000;
  while ((spi[8] & 2u) == 0u && --budget) {}
  if (!budget) { send_text("SD1 TRANSPORT SPI TIMEOUT\n"); stop(); }
  spi[4] = byte;
  budget = 100000;
  /* TXEMPTY can still be high for a cycle before the newly queued byte
   * starts shifting. First observe busy, then wait for the final RX byte. */
  while ((spi[8] & 1u) != 0u && --budget) {}
  if (!budget) { send_text("SD1 TRANSPORT SPI BUSY TIMEOUT\n"); stop(); }
  budget = 100000;
  while ((spi[8] & 1u) == 0u && --budget) {}
  if (!budget) { send_text("SD1 TRANSPORT SPI TIMEOUT\n"); stop(); }
  return spi[0];
}

static void deselect(void) {
  gpio[0] = 1;
  (void)exchange(0xff);
}

static uint8_t command(uint8_t opcode, uint32_t argument, uint8_t crc) {
  gpio[0] = 0;
  (void)exchange((uint8_t)(0x40u | opcode));
  (void)exchange((uint8_t)(argument >> 24));
  (void)exchange((uint8_t)(argument >> 16));
  (void)exchange((uint8_t)(argument >> 8));
  (void)exchange((uint8_t)argument);
  (void)exchange(crc);
  for (unsigned attempt = 0; attempt < 20; ++attempt) {
    const uint8_t response = exchange(0xff);
    if ((response & 0x80u) == 0u) return response;
  }
  send_text("SD1 TRANSPORT R1 TIMEOUT\n");
  stop();
}

__attribute__((noreturn)) void sd1_main(void) {
  send_text("SD1 TRANSPORT START\n");
  /* The simulator independently streams a real DTB into DDR, rather than
   * embedding one in firmware. Verify an actual CPU read from that address:
   * FDT magic d00dfeed is edfe0dd0 when loaded as a LE RV64 MMIO word. */
  if (*(volatile uint32_t *)(uintptr_t)0x87f00000u != 0xedfe0dd0u) goto fail;
  send_text("SD1 TRANSPORT INDEPENDENT DDR FDT OK\n");
  gpio[0] = 1;
  gpio[1] = 1; /* Altera PIO direction: drive chip-select output. */
  spi[12] = 0; /* Mode 0. */
  spi[16] = 1; /* Two source cycles per half-period. */

  /* SD SPI initialization requires >=74 actual SCLK edges while deselected. */
  for (unsigned i = 0; i < 12; ++i) (void)exchange(0xff);

  if (command(0, 0, 0x95) != 1) goto fail;
  deselect();
  if (command(8, 0x1aa, 0x87) != 1) goto fail;
  for (unsigned i = 0; i < 4; ++i) (void)exchange(0xff);
  deselect();
  if (command(55, 0, 1) != 1) goto fail;
  deselect();
  if (command(41, 0x40000000u, 1) != 0) goto fail;
  deselect();
  if (command(58, 0, 1) != 0) goto fail;
  for (unsigned i = 0; i < 4; ++i) (void)exchange(0xff);
  deselect();

  /* CMD17 LBA0: read the on-media MBR and verify its 0x55aa signature. */
  if (command(17, 0, 1) != 0) goto fail;
  unsigned tries = 64;
  while (exchange(0xff) != 0xfe && --tries) {}
  if (!tries) goto fail;
  uint8_t mbr510 = 0, mbr511 = 0;
  for (unsigned i = 0; i < 512; ++i) {
    uint8_t next = exchange(0xff);
    if (i == 510) mbr510 = next;
    if (i == 511) mbr511 = next;
  }
  (void)exchange(0xff); /* CRC16 high/low. */
  (void)exchange(0xff);
  deselect();
  if (mbr510 != 0x55 || mbr511 != 0xaa) goto fail;

  /* Real SPI ready interrupt feeds Product's PLIC source 11. */
  spi[8] = 2;
  tries = 1000;
  while ((*(volatile uint32_t *)PLIC_PENDING & (1u << 11)) == 0u && --tries) {}
  spi[8] = 0;
  if (!tries) goto fail;
  send_text("SD1 TRANSPORT SPI PLIC11 CMD17 OK\n");
  stop();

fail:
  send_text("SD1 TRANSPORT FUNCTIONAL FAIL\n");
  stop();
}

__asm__(".section .text.start,\"ax\"\n"
        ".globl _start\n"
        "_start:\n"
        "li sp, 0x87ef0000\n"
        "tail sd1_main\n");
