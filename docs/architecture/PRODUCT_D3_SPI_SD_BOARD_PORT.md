# Product D3 SPI/SD board port and Linux contract

## Scope and non-goals

The Product RV64GC *distro* target is an independent CPU ISA/geometry selection.
Its physical board devices are selected separately by `ProductSoCBoardConfig`:

| Profile | Selection | Physical SD capability |
| --- | --- | --- |
| Existing D1/engineering | `ProductSoCBoardProfiles.existingRamOnly` | No SPI/MMC port; existing UART, PLIC, timer and AXI seam unchanged |
| D3 persistent-storage candidate | `ProductSoCBoardProfiles.persistentSpiSd` | OpenCores tiny-SPI-compatible MMIO controller, Altera PIO-compatible 1-bit chip-select GPIO, SPI physical pins and PLIC source 11 |

The D3 pin-generating entrypoint is
`aethercore.soc.ElaborateProductFpgaColdBootSpiSdPinsRv64gc` with a
`small` or `expanded` geometry argument. The old
`ElaborateProductFpgaColdBootPinsRv64gc` is intentionally RAM-only. Do not
select the SPI-SD device tree with the old RAM-only RTL, or the kernel will
probe MMIO devices that are absent.

The SD interface is *PIO only*. It is not a fake RAM-backed block device,
and does not become another AXI DDR master. The product CPU's sole memory
bridge retains the established LR/SC/AMO coherence assumption. The card's
electrical interface and read/write correctness **have not been verified on
a physical board or under a simulated SD-card model**.

## Software-visible apertures

All addresses are physical and must agree between `AetherSoCAddressMap`,
`AetherSoCPlatformFabric` and `AetherSoCDts`.

| Device | Base | Aperture | Compatible | PLIC source |
| --- | --- | --- | --- | --- |
| tiny SPI master | `0x10001000` | `0x20` | `opencores,tiny-spi-rtlsvn2` | 11 |
| SPI CS GPIO | `0x10002000` | `0x10` | `altr,pio-1.0` | none |
| Existing UART | `AetherSoCBoardSpec.addressMap.uartBase` | 8 | `ns16550a` | 10 |

SPI registers match the upstream Linux 6.6 `spi-oc-tiny.c` driver's
**byte-width** accesses; values are carried in the low bits of semantic
`AetherMemRequest.wdata`, independent of their absolute MMIO byte offset:

| Offset | Name | Semantics |
| --- | --- | --- |
| `+0x00` | RXDATA | Shift-register receive byte, read-only |
| `+0x04` | TXDATA | Enqueue one byte in TX/RX double buffer |
| `+0x08` | STATUS | bit 1 `TXR` (TX buffer ready), bit 0 `TXE` (shifter empty); writes control corresponding interrupt enables |
| `+0x0c` | CONTROL | bit 1 CPOL, bit 0 CPHA |
| `+0x10` | BAUD | 8-bit half-period divider; `SCK = module_clock / (2 * (baud + 1))` |

The separate Linux `gpio-altera.c` device drives GPIO DATA at `+0` and
output-direction at `+4`. GPIO index 0 is a physical **active-low** SD CS.
Its inactive value is 1 and is forced inactive during the cold-boot DDR
loader handoff. Physical pins are `spiSclk` (output), `spiMosi` (output),
`spiMiso` (input), and `spiCsN` (output). Board integration must provide
correct pad voltage, electrical drive, signal constraints, and CS pull-up.

PLIC source 11 carries the tiny-SPI combined TXR/TXE interrupt. Linux may
also use controller polling; the interrupt number in the DTS is only
advertised when the hardware profile actually instantiates the controller.
Neither GPIO interrupts nor DMA capabilities are advertised.

## Device-tree and Linux requirements

The D3 device tree is generated independently, with the **matching** board
profile selected explicitly:

```text
aethercore.EmitAetherSoCDts --output <board.dts> \
  --isa rv64imafdc_zicsr_zifencei --mmu sv39 --spi-sd \
  --spi-clock-hz 50000000 --spi-max-hz 12500000
```

The emitted SPI node uses `#address-cells=<1>`, `#size-cells=<0>`,
`cs-gpios=<&sd_cs_gpio 0 1>` and `mmc@0` with
`compatible="mmc-spi-slot"`, `broken-cd`, `voltage-ranges=<3300 3300>` and
`spi-max-frequency`. The controller's `clock-frequency` **must be the
actual supplied Product RTL clock**, not merely the value from this example.
The 50 MHz profile is a proposed board contract, not a proven FPGA Fmax.

An image intended to mount a real SD-card root filesystem must include the
relevant Linux 6.6 drivers (`CONFIG_SPI`, `CONFIG_SPI_OC_TINY`,
`CONFIG_GPIOLIB`, `CONFIG_GPIO_ALTERA`, `CONFIG_MMC`, `CONFIG_MMC_SPI`,
`CONFIG_MMC_BLOCK`, plus required block/filesystem drivers). Those drivers
must be **built in** if the card carries the root filesystem before modules
can be loaded. Root selection (partition/UUID, initramfs vs direct root) is
software deployment policy; it is not fixed by the hardware renderer.

## Cold-start dependency and remaining physical work

The cold-boot top exposes `clockLocked`, `ddrCalibrated`, `imageLoaded`,
`loaderIdle` and `imageLoadFailed`, plus `loaderOwnsDdr`/`cpuOwnsDdr`. It
retains the existing isolated handoff and holds SPI and UART idle while the
CPU is reset. **It does not instantiate** a DDR PHY, board PLL, AXI master
arbitration/mux, flash programmer, or physical power/reset controller.

Before claiming *FPGA boots a full Linux distribution*, a specific board
must supply and document:

1. A DDR controller/PHY and exact clock/reset/pin constraints, with an AXI
   loader/CPU ownership mux obeying cold-boot isolation.
2. A loader able to place firmware, kernel, initramfs (when needed) and DTB
   at their mutually agreed physical addresses, and acknowledge outstanding
   writes only after completion. The CPU reset entry and OpenSBI handoff
   must agree with the selected image.
3. A real SPI-compatible SD socket (3.3 V I/O, SCLK, MOSI, MISO, CS, card
   power and pull-ups), attached to the four exported pins. A card detect
   GPIO is intentionally absent; DTS uses `broken-cd` polling.
4. A kernel with built-in upstream MMC-over-SPI/PIO drivers, suitable root
   image and correct root mount arguments.
5. Functional hardware/simulation validation **in a later separately
   authorized round**, beginning with GPIO/SPI register/transfer semantics
   before any expensive complete-Linux run.

No validation, CI invocation, new Linux boot or performance measurement was
performed for this D3 hardware patch. Its value is a concrete Linux-driver
compatible MMIO-to-pad path; the unimplemented board-side DDR and image
loader are explicit prerequisites rather than silently assumed hardware.

