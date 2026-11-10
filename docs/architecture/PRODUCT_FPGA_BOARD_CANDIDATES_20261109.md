# Product Small RV64GC: exact-device FPGA candidate gate (2026-11-09)

## Goal and scope

The target is **the real Product SmallOoO RV64GC/LP64D RTL**, including
Sv39/F-D, UART firmware upload, DDR-local AXI and SPI-SD; it is not an older
minimal RISC-V core or an Artix-only substitute. We seek an affordable board
to speed real Linux and Debian runs, while the SD1 true-RTL longrun stays
completely separate. This document and gate do **not** authorize purchase.

## Vendor candidate ledger (verify revision, inventory and current price)

| Candidate | Exact FPGA target for Vivado probe | Vendor facts | Board collateral status |
| --- | --- | --- | --- |
| Bochenjingxin Kintex7-BaseC | `xc7k325tffg676-2` | K325T-2FFG676I; 1 GiB 32-bit DDR3; 50 MHz oscillator; TF/SPI, JTAG, UART; vendor-listed ~CNY 1999 | Full revision-specific XDC, schematics and MIG projects not publicly available; request before paying |
| Puzhi PA200T-StarLite | `xc7a200tfbg484-2` | A200T-2FBG484I; 1 GiB DDR3; 200 MHz differential oscillator; SD, JTAG, UART | Full revision-specific XDC, schematics and MIG projects need vendor delivery; price not verified |
| Digilent Nexys Video | `xc7a200tsbg484-1` | A200T-1SBG484C; 512 MiB DDR3, microSD, USB-UART/JTAG; documented RV64 Linux/Debian designs | Public reference manual / Master XDC / DDR preset are available; real Product DDR PHY and board wrapper still missing |
| Digilent Genesys 2 / AMD KC705 | `xc7k325tffg900-2` | K325T-2FFG900C; 1 GiB DDR3; distinct board-level DDR topology and XDC | Public board manuals; Genesys 2 has CVA6/Fedora FPGA reference; each needs its own actual physical wrapper |

- Official K325T page: https://bochenjingxin.com/enterprise/products/92fbf1de-fae9-4707-abc7-4e628b02ae84
- Official A200T page: https://www.puzhi.com/puzhi-XILINX-Artix-7-200T-StarLite
- Digilent Nexys Video board documentation: https://digilent.com/shop/nexys-video-amd-artix-7-fpga-trainer-board-for-multimedia-applications/
- Digilent Genesys 2 board documentation: https://digilent.com/shop/genesys-2-amd-kintex-7-fpga-development-board/
- Full Debian Nexys Video Rocket reference: https://github.com/eugene-tarassov/vivado-risc-v
- Same-board RV64 out-of-order Debian reference: https://spinalhdl.github.io/NaxRiscv-Rtd/main/NaxRiscv/hardware/index.html
- Genesys 2 FPGA Fedora reference: https://github.com/openhwfoundation/cva6-platform
- **Historical same-vendor/same-chip DDR reference**:
  https://github.com/lauchinyuan/FPGA_DDR3_Ctrl includes a user-tested
  Bochenjingxin Kintex-7 basic board with XC7K325T-2FFG676, published
  `constrs/*.xdc`, `xci/` including DDR3 MIG settings, and AXI DDR3 tests.
  It is an older project and **does not identify the current BaseC PCB
  revision**. Treat it as implementation guidance only; never import its
  XDC/MIG to BaseC without matching pinout, DDR3 chips and PCB revision.
- **Documented fallback, not yet a priced candidate**: QMTECH's distinct
  XC7K325T **Development Board** (`xc7k325tffg676-1`, *speed -1*) has a public schematic
  and vendor DDR3 MIG example:
  https://github.com/ChinaQMTECH/QMTECH_Kintex-7_Development_Board .
  LiteX maintains a board-specific 50 MHz PLL, 7-series DDR3 PHY, UART and
  SoC target at
  https://github.com/litex-hub/litex-boards/blob/master/litex_boards/targets/qmtech_kintex7_devboard.py .
  This board has one x16 DDR3 device (~256 MiB), but no native FPGA USB-UART
  (JP5 external UART) and no `spisdcard` pins defined in its LiteX platform.
  Its 50 MHz *input oscillator* feeds a 100 MHz DDR-controller system clock,
  not a direct 50 MHz DDR UI. Use it chiefly as an **open DDR3 reference**.
  The QMTECH development board and its different **core board** are not
  interchangeable.
- **Public pinout fallback, price/availability not verified**: LAB5604
  `E4FPM7K325 MMB` (`XC7K325T-FFG676-2I`) publishes both board schematics,
  core/base board pinouts including `mmb_pinout.xdc`, SD and USB-UART wiring:
  https://github.com/LAB5604/fpga_board . Its 2 GiB x64 DDR3 arrangement is
  more complex than our 256 MiB/64-bit-AXI target, and the existence of an
  XDC does **not** demonstrate a calibrated MIG or working Linux bitstream.
- Do not reuse a different A200T **package** XDC: FBG676, FBG484 and SBG484 are different.
- The same K325T die on FFG676 versus FFG900 is **not** a shared board
  implementation. Genesys 2 and KC705 also require distinct DDR, I/O and
  clock constraints despite their identical `xc7k325tffg900-2` part name.
- The Bochenjingxin vendor lists a RISC-V Debian example among its tutorials,
  but without a publicly reproducible signed bitstream, RTL and login trace
  it is a **vendor claim**, not our physical-boot qualification.
- AMD Vivado 2026.1 Basic lists All Kintex-7 and All Artix-7 as supported,
  but an effective license must be installed/renewed; use `get_parts` on an
  actual installation to verify each exact device before drawing conclusions.

## Separate, fail-closed gates

On a clean checkout of the **exact intended CPU commit**:

```sh
# Generate once; this is the existing SHA256-verified Product P0 export.
bash tools/ci/product_fpga_board_candidates.sh prepare build/product-board-candidates

# A real licensed Vivado installation is required for the following commands.
bash tools/ci/product_fpga_board_candidates.sh probe build/product-board-candidates xc7k325tffg676-2
bash tools/ci/product_fpga_board_candidates.sh probe build/product-board-candidates xc7a200tfbg484-2
bash tools/ci/product_fpga_board_candidates.sh probe build/product-board-candidates xc7a200tsbg484-1
bash tools/ci/product_fpga_board_candidates.sh probe build/product-board-candidates xc7k325tffg900-2
bash tools/ci/product_fpga_board_candidates.sh synth build/product-board-candidates xc7k325tffg676-2
bash tools/ci/product_fpga_board_candidates.sh synth build/product-board-candidates xc7a200tfbg484-2
bash tools/ci/product_fpga_board_candidates.sh synth build/product-board-candidates xc7a200tsbg484-1
bash tools/ci/product_fpga_board_candidates.sh synth build/product-board-candidates xc7k325tffg900-2

# Intentionally BLOCKED until complete vendor wrapper and physical flow exist.
bash tools/ci/product_fpga_board_candidates.sh board-gate build/product-board-candidates xc7k325tffg676-2
```

`prepare` delegates to the established `product_fpga_artix7_p0.sh` so the
manifest Git SHA, full root-level SV input set, per-file SHA and bundle digest
are all checked. The candidate wrapper additionally rejects uncommitted
Product Scala/build/tool changes, preventing reuse of a stale RTL export
under an unchanged HEAD. A changed SHA, missing source, changed bytes or extra SV
file **must fail closed**. `probe` and `synth` never generate a missing export
implicitly. Each device has independent output; completed result files cannot
be overwritten. `probe` does a real Vivado `get_parts`; `synth` uses the exact
same SV bytes with the **20ns Product UI reference clock** read before OOC
synthesis. Outputs include `vivado.log`, `device.txt`, and, for `synth`,
`utilization.rpt`, `timing_synth.rpt`, `synth.dcp`, `result.json`.

OOC synthesis excludes DDR3 MIG, board-level oscillator clocking/PLL, CDC,
actual I/O pin XDC, placement, routing and bitstream generation. A synthesis
timing summary is **not** an FPGA Fmax or routed WNS result. A user-supplied
file named `board.xdc` cannot alone prove correct board revision or integration.

## Vendor collateral required before board-specific implementation

1. Exact PCB revision, original schematic/PDF and board BOM/oscillator/DDR3 device layout.
2. Full matching XDC with clock, UART, JTAG, reset, SD SPI, DDR3 pins,
   I/O standards, bank voltages and timing constraints.
3. Original DDR3 MIG `.prj` and Vivado project/IP, with DDR3 calibration,
   clocks and AXI write/read self-test logs.
4. Wrapper connecting 50 MHz Product UI to board oscillator and real MIG UI
   through appropriate MMCM/PLL and AXI CDC. The Product local DDR AXI is
   **64-bit data, 28-bit offset address**, with a 256 MiB window starting at
   physical `0x80000000`; the physical DDR3 pins are not these AXI signals.
   External 1 GiB/2 GiB DDR does **not** enlarge Linux usable RAM without a
   deliberate change to the Product software address-map contract.
5. SD 3.3 V power/reset/control and SPI pin routing, plus UART pin/baud and
   firmware-upload verification.

After those are audited against the actual board revision, implement the
real physical top, run DRC, `place_design` and `route_design`, require
post-route WNS >= 0 and archive DCP/clock/resource evidence. Only *then*
run on hardware: DDR calibration, OpenSBI payload upload, SD rootfs mount
and actual serial Linux Debian service/login.

**Evidence labels are disjoint:** `RTL_EXPORT_PASS`,
`SEVEN_SERIES_DEVICE_PROBE_ONLY`, `SEVEN_SERIES_LOGIC_SYNTH_ONLY` and
`BOARD_PHYSICAL_INPUTS_MISSING` are not a routed board pass and not SD1
Debian login. Current generic XC7 Yosys counts (~73,106 LUT, 22,062 FF,
95 DSP) are unplaced and cannot establish physical device fit.

Keep the existing SD1 run and CPU PMP experiments on their independent
branches. If K325T is affordable and collateral available, it has more
resource headroom than A200T; the physical gate and seller documentation,
not this OOC pass, determine whether it is actually the better purchase.

## Latest mapped capacity evidence, not a hardware qualification

The same-SHA Product cache-capacity A/B run
[`#42589111295`](https://github.com/yituanxing/minicore/actions/runs/42589111295)
(`402b83b87ebb06ca89c9e0007b2e2096217dfd53`) produced the following
XC7 unplaced mapping for `AetherCoreProductFpgaUartDdrBoard`:

| Cache entries (I/D each) | LUT | FF | DSP48E1 | BRAM18/36 | XC7A200T LUT fraction | XC7K325T LUT fraction |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 64 (512 B each) | 72,900 | 22,062 | 95 | 0 | 54.2% | 35.8% |
| 256 (2 KiB each) | 74,771 | 22,446 | 95 | 0 | 55.6% | 36.7% |

The 256-entry design adds 1,871 LUT, 384 FF and 401 RAM64M, with **zero
extra BRAM**. These percentages compare vendor LUT counts to Yosys XC7 cell
mapping, not a fitted physical design. They exclude an integrated DDR3
MIG/PHY and board-specific PLL/CDC, XDC and timing constraints. An A100T
with 63,400 LUT cannot fit the 256-entry mapped logic unchanged. The
experimental 5M-cycle real Debian prefix A/B in
[`#42590354314`](https://github.com/yituanxing/minicore/actions/runs/42590354314)
has since completed at SHA `10d7e8e632fdee2418fba4435d24a40f4da62753`.
The published same-firmware, same-runner, fixed-5M-RTL-cycle snapshots show
64-entry/256-entry retired commits `1,297,993`/`1,386,745` (+6.84%),
I-cache misses `545,327`/`189,323` (-65.3%) and D-cache misses
`97,165`/`63,021` (-35.1%). **Both runs timed out in the OpenSBI-compressed
prefix, before Linux-compressed progress**; this is a qualified **short-prefix
performance benefit**, not a measured Debian boot-speedup or FPGA Fmax.

## Open DDR PHY integration route (candidate; not a board pass)

Where exact MIG artifacts are unavailable, LiteDRAM's official standalone
generator can provide a 7-series DDR PHY/controller with an AXI user port:
https://github.com/enjoy-digital/litedram/blob/master/doc/gen.md .
For the **specific** QMTECH x16 Micron DDR3 board, the LiteX example uses
50 MHz oscillator input, 100 MHz `sys_clk`, 400 MHz `sys4x`, and 200 MHz
IODELAY clock. A candidate standalone configuration would select
`sdram_phy: A7DDRPHY`, `sdram_module: MT41J128M16`,
`sdram_module_nb: 2` (**two x8 byte groups for x16, not two chips**),
`memtype: DDR3`, `sys_clk_freq: 100e6`, and an AXI user port with
`data_width: 64`, `id_width: 4`, `with_wrap: false` and
`block_until_ready: true`. This is a **non-tested integration sketch**, not
the current Product board implementation. Match every PHY/module parameter,
pad, oscillator, rank, DDR width and speed grade to the *specific* schematic.

The LiteDRAM AXI user port does not expose every upstream AXI sideband:
the Product single-beat, INCR, unlocked request and 4-bit ID rules must be
adapted explicitly; `lock/cache/prot/qos` need a documented wrapper policy.
Likewise, the public Digilent **Nexys-Video-DMA** example's generated MIG
is *not* an exact plug-in for Product: its documented AXI configuration is
**32-bit data, 2-bit ID, 29-bit address**, with narrow bursts disabled,
whereas Product is **64-bit data, 4-bit ID, 28-bit offset address** and
generates byte/halfword transactions. The actual board's DDR pins, clocking
and calibration are useful references, but the MIG AXI interface must be
regenerated with compatible widths/narrow accesses, or connected through
an explicitly tested data-width/ID/narrow-access conversion path. The
source for that *specific example* is
https://github.com/Digilent/Nexys-Video-DMA ; do not generalize its
configured parameters to every Nexys Video DDR build.
The stronger **Nexys Video Debian** physical source is
https://github.com/eugene-tarassov/vivado-risc-v/blob/master/board/nexys-video/riscv-2026.1.tcl .
Unlike a standalone MIG template, it integrates a 128-bit-data/4-bit-ID/29-bit-address
MIG AXI behind **Vivado SmartConnect with two clocks**, feeding a 50 MHz
CPU-side domain and a DDR UI domain. The MIG in that reference also disables
narrow bursts. AMD PG247 explicitly states that SmartConnect **preserves
single-beat narrow SIZE** to the downstream interface, rather than widening
it to a full beat, so its width converter alone cannot cure this mismatch:
https://docs.amd.com/r/en-US/pg247-smartconnect/AXI-SmartConnect-Core-Limitations .
The first board experiment should copy the known-working MIG/clock/XDC
hierarchy, then regenerate the MIG with its AXI narrow-burst option
`C0_S_AXI_SUPPORTS_NARROW_BURST=1`
(where supported by the selected IP/device) or provide an independently
verified equivalent adapter. Prefer this working
Debian board-level hierarchy for first integration, retain the original
board DDR pin/clock/reset references, replace only the CPU-side AXI source,
and separately test narrow-size transfers, ID response ordering, independent
AW/W acceptance, CDC reset and calibration before declaring a physical fit.
The memory IP and Product clock domains must be the **same actual UI clock**
or have a correctly reset, constrained asynchronous AXI CDC. Do not silently
run a 100 MHz DDR UI at the Product's 50 MHz, or assume that changing
LiteDRAM to `sys_clk_freq: 50e6` is calibrated for DDR3. The native x16 DDR3
PHY may have a wider internal datapath; its standard AXI width converter is
preferable to custom data-width logic.

Standalone LiteDRAM with `cpu: None` does not supply its own DRAM
initialization firmware; its control CSR/DFII programming, training and
`init_done` sequence still need a real implementation. For a first physical
experiment its own known firmware-assisted configuration is safer to prove
calibration before CPU integration. The generated LiteDRAM XDC also does
**not** substitute for complete board-specific input clocks, DDR I/O LOC,
IOSTANDARD, VREF, termination, and timing constraints. FPGA gates stay
fail-closed until DDR calibration and full physical P&R are independently
verified.

## First real physical PR target: Nexys Video reference reuse

The fastest credible first board is **Nexys Video with the existing Debian
`vivado-risc-v` board integration as collateral**. Reuse exactly the board
hierarchy from `board/nexys-video/riscv-2026.1.tcl`, `top.xdc`, `sdc.xdc`,
`uart.xdc`, and `board/mem-reset-control.v` at a pinned reference commit.
The reference derives a **50 MHz CPU-side AXI** clock and a **200 MHz MIG
reference input**, with a distinct DDR UI clock and a 2-clock SmartConnect.
Its reset controller observes PLL lock, DDR calibration and the UI reset.

1. Freeze the exact source SHA and board PCB revision. Import the matching
   DDR3 pin/clock/power constraints and vendor IP, never a different package.
2. Replace the Rocket-side master with Product's 64-bit AXI/4-bit ID/28-bit
   zero-based aperture at `0x80000000..0x8fffffff`. Keep the vendor
   SmartConnect 50 MHz-to-DDR-UI CDC and 64-to-128 data-width conversion.
3. Make the MIG narrow-burst setting explicitly compatible with the
   Product's single-beat `SIZE=0..3`; generate the IP and test each size,
   lane, byte strobe, and near-end boundary in XSIM. Test 4-bit IDs and
   out-of-order R IDs, AW-before-W, W-before-AW, B response identity and
   reset during outstanding traffic. Mere IP generation is not a pass.
4. Wire Product firmware UART upload to the actual Nexys Video pins and
   retain persistent SD SPI: reference `sdc.xdc` uses SCLK=W19,
   MOSI/CMD=W20, MISO/DAT0=V19, CS/DAT3=U18; `uart.xdc` uses FPGA
   RX=V18 and TX=AA19. Confirm selected UART baud and auxiliary SD-card
   bus ownership on the real board before any hardware boot claim.
5. Require DDR calibration, board-ready reset sequence, 50 MHz Product
   routed WNS >= 0, DDR UI timing and all generated XDC/IP constraints.
6. Only after RTL/XSIM and real P&R, boot the full original SD1-style
   signed Debian payload and preserve the **serial service/login** trace.

This is an **implementation path**, not a completed physical PR; no Vivado,
XSIM, generated MIG, or hardware bitstream was run in the current workspace.

### Pinned DDR import entry (2026-11-09)

The first executable slice is now `tools/ci/product_fpga_nexys_video_ddr.sh`.
It accepts only a local checkout of `eugene-tarassov/vivado-risc-v` at
`1e6b86aebe5ba6675a1193ec12b417c1f7cb8bf0`, verifies the five specific
source Git blob hashes and the DDR, clock, reset and board-pin contract, and
refuses to emit positive evidence when the reference is missing or changed.
The external upstream repository retains its own MIT license; no full
upstream board design is copied into Product.

```
git clone https://github.com/eugene-tarassov/vivado-risc-v.git /tmp/vivado-risc-v
git -C /tmp/vivado-risc-v checkout 1e6b86aebe5ba6675a1193ec12b417c1f7cb8bf0
bash tools/ci/product_fpga_nexys_video_ddr.sh preflight /tmp/vivado-risc-v /tmp/nexys-reference-source.json
# Only on a machine with Vivado 2026.1 and the Digilent Nexys Video board files:
bash tools/ci/product_fpga_nexys_video_ddr.sh import-ddr /tmp/vivado-risc-v /tmp/aether-nexys-import
# After explicitly preparing a commit-/SHA-pinned smallGeometry Product export:
bash tools/ci/product_fpga_artix7_p0.sh prepare /path/to/product-export
bash tools/ci/product_fpga_nexys_video_ddr.sh integrate-product /tmp/vivado-risc-v /path/to/product-export /tmp/aether-nexys-product
# In an independent, unused output path, run actual synthesis and routing:
bash tools/ci/product_fpga_nexys_video_ddr.sh physical-product /tmp/vivado-risc-v /path/to/product-export /tmp/aether-nexys-routed
```

`preflight` proves **source provenance only**; the generated JSON explicitly
records `mig_generated=false`, `routed=false`, `ddr_calibrated=false` and
`debian_login=false`. `import-ddr` evaluates exactly the two upstream DDR
hierarchy/MIG-file procedures (not the Rocket/DMA/Ethernet root design),
changes the original MIG narrow-burst parameter from `0` to `1`, and exports
the isolated DDR AXI/DDR3 interfaces in a new, non-overwritten Vivado project.
This is **DDR hierarchy import only**. It intentionally has no Product
component, full board clock/reset/XADC/SPI wrapper, XSIM traffic proof,
placement, routed Fmax, DDR calibration or signed Debian UART login.

Before proceeding to physical integration, check the generated MIG parameter
in Vivado, connect its `device_temp_i` to real XADC temperature data, preserve
50MHz-to-MIG-UI AXI CDC, implement reset synchronizers in both clock domains,
and add SD reset/detect and SPI ownership. `Product` must remain held in reset
until clock lock **and** DDR calibration are established. Each narrow
`ARSIZE/AWSIZE` and `WSTRB` lane needs XSIM and hardware checks before any
PASS statement. The current workspace has no Vivado/XSIM or physical board;
offline unit tests alone cannot discharge those gates.

### Product-to-DDR logical Block Design implementation

The `integrate-product` entry is a distinct, stricter next step. It first
verifies the *actual generated SystemVerilog* port inventory for
`AetherCoreProductFpgaUartDdrBoard` against
`tools/fpga/aether_product_nexys_axi_bridge.sv`. This is the smallGeometry
contract: 28-bit local DDR address, 64-bit AXI data, 4-bit AXI ID, independent
AW/W/AR/B/R channels, Product high-active reset and 50 MHz CPU clock. It
requires every generated Product input to have an explicit bridge connection
and refuses extra/mismatched ports, so later Chisel changes cannot silently
break the board build. **No generated Product RTL is currently in the repo**;
this generated-RTL gate has not yet passed on the real Product module.

In Vivado 2026.1, the script `tools/fpga/nexys_video_product_bd.tcl` creates
the pinned DDR hierarchy and an independently named Product AXI4 master,
connects `Product/M_AXI` directly to `DDR/S00_AXI`, and maps the 28-bit
zero-based Product address into the lower 256 MiB of the 29-bit reference MIG
aperture by inserting a **zero** high bit. The SmartConnect remains responsible
for 64-to-128-bit conversion and 50 MHz-to-DDR-UI clock crossing. The new BD
also includes 100 MHz input clock, 50/200 MHz MMCM, separately synchronized
AXI reset (`proc_sys_reset`), real XADC `temp_out`, Product firmware UART,
SPI-mode SD pads, and the original upstream reset controller. SD reset is
released with the synchronized AXI reset; Product's cold boot independently
waits for its synchronized `DDR/mem_ok`. Product image failure from the
external board is tied low; its own CRC/upload failure remains live.

The **physical integration is not yet validated**: no Vivado is installed in
this environment, no generated RTL artifact was available for the port gate,
and there is no XSIM narrow-transfer/board I/O/P&R/FPGA DDR calibration trace.
The first usable result from `integrate-product` will be
`PRODUCT_NEXYS_LOGICAL_BD_ONLY`, and only if Vivado successfully recognizes
the `M_AXI` bundle, assigns the lower-256MiB address segment and validates
the BD. It does not claim a bitstream or boot. Nexys Video SD card-detect
and unused DAT1/DAT2 remain an explicit hardware integration check; they
must be reviewed with the board schematic before hardware sign-off.

### Separate physical implementation and timing evidence (2026-11-10)

`physical-product` extends the exact same `integrate-product` entry only when
the pinned upstream source, a **clean current-commit** Product RTL export,
Vivado **2026.1**, and the Digilent Nexys Video board part are all present.
The original `nexys_video_product_bd.tcl` remains a strictly logical-only
import and verification path. A separate Vivado process opens its saved
`.xpr`, generates the BD/IP outputs and top wrapper, then runs project-mode
`synth_1` followed by `impl_1` through `route_design`. The script writes
synthesis/route checkpoints, utilization, routed clock, DRC, route status,
setup/hold timing and `check_timing` reports. Negative constrained setup or
hold slack, missing timing paths/clocks, DRC violations, missing reports, an
incorrect part/version, and changed source provenance cause a hard failure.

Only after all those checks does the local validator publish
`physical_reports/result.json` with status `NEXYS_ROUTED_TIMING_ONLY`, a
source SHA, 2026.1 version, device, and measured setup/hold slacks. This
status **never** means a board PASS: incomplete external I/O timing, generated
bitstream, hardware DDR calibration, and a real signed Debian serial
service/login are separately required. The physical script does not create a
bitstream, program FPGA hardware, or certify a maximum Fmax from one 50 MHz
timing point.

The companion GitHub-hosted runner workflow
`product-fpga-nexys-physical-contract.yml` exercises **offline negative
contracts only**; it explicitly cannot claim Vivado physical execution.
The free hosted runner does not provide AMD Vivado, and the authenticated AMD
web installer cannot be treated as a public toolchain artifact. A licensed
and board-file-equipped Vivado 2026.1 environment must supply the first real
post-route proof. This contract is checked in to avoid losing the preparation
while hardware/tool access is arranged; there is **no verified routed run**
yet.

### Real generated RTL compatibility check

The preceding port gate was first checked with synthetic headers. A subsequent
read-only search located a genuinely emitted 64-entry Product RV64GC UART/DDR
FPGA SystemVerilog bundle in the sibling `cache_capacity_ab` experiment
(`git_sha=d475ce0d92c2e972fbd6953b0be1f617fb2e8dd5`, 122 `.sv` files,
SHA256 manifest verified). Real CIRCT grouped ANSI port declarations exposed
a parser bug in `product_nexys_axi_port_gate.py`; the gate now handles those
continuations and has a targeted regression test. The bridge passes this
**historical real RTL** contract with 50 mapped Product ports, ID4/DATA64,
ADDR28→29. This is more useful than fixture-only tests, but the source commit
and emitter profile differ from the present branch; it is **not** a replacement
for the exact current Product export required by `integrate-product`.

As a stronger source-only check, the 2026-11-09 offline audit used the real
pinned 803-line upstream Tcl: all five Git blob hashes passed, and the Tcl
extractor selected only the MIG PRJ writer and DDR hierarchy, safely changing
the original MIG narrow setting from `0` to `1`. This confirms the pinned
DDR source can be **parsed and selected**, not that Vivado imported its IP.
