# Product Small RV64GC — Artix-7 P0 feasibility gate

## Exact scope

This gate evaluates the **one-wide Product Small RV64GC/LP64D** RTL with
HardFloat F/D, Sv39, a UART firmware uploader, DDR-local AXI window and
SPI-SD. It uses the existing Chisel entrypoint
`aethercore.soc.ElaborateProductFpgaUartDdrSpiSd1Rv64gc` and the top
`AetherCoreProductFpgaUartDdrBoard` unchanged. It does not widen the CPU,
replace the SoC, or use the old V0 core as the Product qualification target.

The first candidate parts are `xc7a100tfgg676-2` and
`xc7a200tfbg676-2`. These are **device experiments, not selected boards**.
The full DDR controller/PHY, PLL, CDC, board XDC and timing/pin rules must
still be provided for the exact eventual board. In particular, the top's
`localDdrAxi` is an **internal memory-controller interface**, not a set of
off-chip DDR signals. Do not constrain its hundreds of signals as package
pins or claim the OOC synthesis clock as a physical FPGA Fmax.
The Product/DDR UI port contract is **one 50 MHz clock (20 ns)**. The OOC
reference XDC is read **before synthesis** so this is a real mapping target,
not a late timing-report-only constraint. It still omits board PLL/CDC,
generated clocks, DDR PHY and physical pin timing.
The board must eventually provide a usable **256 MiB** DDR aperture for the
Product physical range `0x80000000..0x8fffffff`, translated onto its
28-bit zero-based local AXI address port.

## Run once, reuse the exact RTL for both parts

On the Product branch at a pinned commit:

```sh
bash tools/ci/product_fpga_artix7_p0.sh prepare build/product-artix7-p0
```

The export saves `rtl/*.sv`, `rtl.sha256` and `manifest.json` with the
checked-out Git SHA, exact geometry/ISA/ABI/top, and a bundle digest.
Repeating `prepare` with the same sources reuses and verifies that artifact;
a changed Git SHA, extra/unlisted synthesis file, deleted file or byte mismatch
**fails closed** instead of
silently regenerating an inconsistent model. Use a new output directory for
any changed commit. Only the root `rtl/*.sv` set is read and hashed as
synthesis input; nested `rtl/verification/` files are auxiliary verification
collateral and are **not** read by the Vivado P0 flow.

On a Vivado host supporting the selected Artix-7 parts, with the **same source SHA** and the
exported directory:

```sh
bash tools/ci/product_fpga_artix7_p0.sh synth build/product-artix7-p0 xc7a100tfgg676-2
bash tools/ci/product_fpga_artix7_p0.sh synth build/product-artix7-p0 xc7a200tfbg676-2
```

Each separate run retains `vivado.log`, hierarchical `utilization.rpt`,
`timing_synth.rpt`, `synth.dcp` and `result.json`. Both parts consume the
**same SV bytes** and only the device target changes. Missing Vivado yields
`ARTIX7_SYNTH_NOT_RUN` and a nonzero exit; no fake resource/Fmax numbers
are emitted. Synthesis failures preserve partial logs for diagnosis.

The manual GitHub workflow `.github/workflows/product-fpga-artix7-p0.yml`
exports and caches the exact-SHA RTL **across separate Actions runs** using
`actions/cache/restore` and `actions/cache/save`, followed by mandatory
SHA and complete input-set verification. Mill/Coursier dependency caching is
separate and does not itself imply RTL reuse. The workflow does **not** install or
pretend to run Vivado on the normal 16-GiB hosted runner. Trigger it via
`workflow_dispatch` when available on the default branch or by adding the
`product-fpga-artix7-p0` label to PR #379. It is never a per-commit longrun.
The cache key is intentionally tied to the **entire commit SHA**: it reuses
exact source revisions and does not yet reuse a previous export across
rootfs-only or documentation-only commits. That broader reuse needs a
separately verified RTL-source fingerprint; never relabel a different
commit's output as current source evidence.

## Result labels are strictly separate

| Evidence | What it actually proves | What it does NOT prove |
| --- | --- | --- |
| `RTL_EXPORT_PASS` | Exact RV64GC Small Chisel design generated and hashed | Device fit or any timing |
| `ARTIX7_LOGIC_SYNTH_ONLY` | Vivado OOC logic mapping with target-device utilization and synthesis timing reports | Placed/routed timing, physical DDR, board Fmax |
| `ARTIX7_ROUTED_TIMING_PASS` (future) | A separately built complete board wrapper, MIG/PLL/XDC and routed WNS >= 0 for the board clock | DDR calibration or Linux run |
| `FPGA_BOARD_PASS` (future) | Actual board DDR calibrated, OpenSBI/Linux, real SD rootfs and serial credentials | Automatic qualification of every CPU geometry |

The first two labels cannot be promoted to the last two by automation or
document wording. Even a positive synthesis WNS is not a routed timing result.
Use the candidates' LUT/FF/BRAM/DSP reports together with an explicit
DDR-IP/debug reserve before choosing a board; an OOC result alone is not a
buy recommendation. If the 100T capacity is tight, evaluate a larger device
before redesigning Product SmallOoO.

Keep Verilator/Spike directed and retirement-differential tests for CPU
correctness; FPGA is intended to accelerate long Linux/distribution runs.
