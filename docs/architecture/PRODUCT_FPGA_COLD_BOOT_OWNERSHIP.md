# Product RV64GC FPGA cold-start ownership contract

Status: **board-neutral, synthesizable handoff and AXI isolation implemented**.
This is neither an FPGA board design nor a Debian-boot acceptance result.

## Why this gate exists

`AetherSoCBootRom` jumps straight to `0x80000000`, the OpenSBI payload base.
The RTL simulation preloads RAM, but physical DDR powers up without firmware
bytes and requires clock/PHY initialization. A real CPU must stay in reset
until DDR calibration and a complete external image load are proven.

`AetherCoreProductFpgaColdBootPins` wraps the **unchanged** Product physical
CPU/SoC with `AetherProductColdBootControl` and the five-channel AXI barrier
`AetherProductAxiColdBootIsolation`. The existing
`AetherCoreProductFpgaPins` simulation and PPA top remain independent.

## Board inputs and owners

All five readiness signals are **level inputs synchronous to the Product
board-clock domain**. A real board is responsible for synchronizers and reset
release, rather than treating asynchronous PLL/DDR status as a raw CPU clock.

| Input | Board responsibility |
| --- | --- |
| `clockLocked` | PLL/clock tree actually stable |
| `ddrCalibrated` | DDR PHY fully calibrated and usable |
| `imageLoaded` | OpenSBI + firmware image completely copied into DDR; held high until handoff |
| `loaderIdle` | All loader AW/W/AR/B/R handshakes and responses completed; remains high through handoff |
| `imageLoadFailed` | Fatal loader/checksum/DDR error; high immediately blocks both masters |

`loaderOwnsDdr` and `cpuOwnsDdr` are mutually exclusive grants to an **external
AXI ownership mux**. This top exports only the CPU AXI master. Actual loader
AXI wiring, DDR controller/PHY, PLL, reset synchronizers, image integrity
checking, and physical constraints must be supplied by the selected board.

## Clock-by-clock handoff

1. System reset starts in **WaitClock**; CPU reset is held, CPU AXI requests
   and response handshakes are fully isolated, serial TX idles high.
2. Stable `clockLocked` then `ddrCalibrated` enter **WaitImage** and grant DDR
   solely to the external loader. `imageLoaded` must be observed **low after
   that grant**; an old stuck-high loaded flag does not release the CPU.
3. A fresh asserted `imageLoaded` **and** `loaderIdle` enter **Handoff**. Both
   DDR grants are low for a full isolation cycle. The loader must not issue
   new AXI traffic after its grant falls.
4. Only after this isolated cycle does **CpuActive** assert `cpuReleased` and
   `cpuOwnsDdr` simultaneously. The original CPU BootROM then fetches at its
   reset vector and jumps to the validated DDR payload.
5. PLL/DDR readiness loss immediately deasserts CPU release/AXI grant. The
   next clock latches **Fault**; an image load error also enters Fault. Neither
   master is granted after fault until a **full system reset**. No silent retry
   is allowed because interrupted CPU AXI transactions cannot be transferred
   safely to a loader without a board-level reset and drain.

The handoff controller does **not** guarantee that external physical DDR
works, that the loader supplies a correct OpenSBI/Linux image, or that a
specific FPGA meets Fmax/area. Those are separate board-specific validations.

## Verification boundary

- `AetherProductColdBootControlSpec` cycle-tests readiness order, stale loaded
  flag, loader outstanding drain, isolated turnaround, CPU release, fault latch
  and reset/reload.
- `AetherProductAxiColdBootIsolationSpec` drives all five AXI channels before,
  during and after release and checks data/ID integrity on release.
- `AetherProductFpgaColdBootElaborationSpec` elaborates the real RV64GC Product
  CPU with both blocks; `ElaborateProductFpgaColdBootPinsRv64gc` emits a
  board-neutral SV top using `small` or `expanded` physical capacity.

The G4/G5 real RTL Linux and Debian boot gates remain independent and must
actually pass before any software qualification claim.
