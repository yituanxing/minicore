# Product PMP live-CSR serial predecode: isolated XC7 result (2026-11-09)

## Decision and scope

The FPGA-oriented reference here is VexRiscv's serialized PMP configuration
decode, in the same spirit as NaxRiscv's FPGA-first resource discipline.
This is **not** an ASIC-style Rocket/BOOM configuration transplant.

This change adds a **standalone, opt-in probe** (`PmpSerialRangePredecoder`)
and an explicit, manually invoked area workflow. It does not connect the
candidate to Product and makes no changes to the ongoing SD1 Debian/RTL path.
The existing precise-CSR and data-transaction PMP state owners remain intact.

The architecture replaces 16 parallel NAPOT/TOR geometry computations with
one shared 2-entry reference decoder lane. During a 16-cycle refresh it
selects each current/previous encoded `pmpaddr` pair, writes the exact decoded
range into one of 16 cached slots, and exposes `ready` **only after the entire
table has been updated**. It starts with a reset refresh. A changed CSR must
hold inputs stable for the full refresh; `busy` consumers must ignore all
range outputs (possibly partially updated).

## Measured isolated area

Both top modules were elaborated from Product commit
`5166bfcc144d7419e1c4eeb354237a457b13680c` using the same Chisel lowering
options and mapped with the same `yowasp-yosys==0.70.0.0.post1259` command:

```text
synth_xilinx -family xc7 -noiopad -top <respective-top>
stat -tech xilinx
```

The serial design maps **both** generated modules: its reference geometry
lane and the serial owner. Resource values below are the final **design
hierarchy**, not just the parent's local cells.

| Standalone PA56 / PMP16 geometry | Parallel original | Serial + cache | Delta |
| --- | ---: | ---: | ---: |
| LUT1–LUT6 | 4,011 | **862** | **−3,149 (−78.5%)** |
| FF | 0 | **1,910** | +1,910 |
| Distributed RAM primitives | 0 | 0 | 0 |
| DSP48E1 | 0 | 0 | 0 |
| Update cycles | combinational | 16 | +16-cycle refresh |

Local evidence is produced by
`tools/ci/product_pmp_serial_map_report.py`. The opt-in
`.github/workflows/product-pmp-serial-xc7.yml` preserves exact baseline and
candidate SV, Yosys logs and `mapping-summary.json` as an artifact when
manually dispatched. These numbers are **unplaced, isolated XC7 mapping**;
they are not a full-core 3,149-LUT reduction, nor Vivado Fmax or a board fit.

## Security and integration gate

The module reuses the existing `PmpRangeDecoder(64, 2, 56)` logic. The chosen
lane 1 has the true `pmpaddr[i]` and a lane 0 whose encoded address is
`0` for PMP0 or the actual `pmpaddr[i-1]` for PMP i. This preserves TOR,
NA4, NAPOT including the all-ones encoding, OFF, permissions and lock bits.

The current Product path intentionally has **two different PMP lifetimes**:

1. **Front-end / fetch PTW:** live CSR from `MachineCsrFile`, with a single
   combinational decoder in `ProductSmallPagedCore`.
2. **Data / data PTW:** per-in-flight-operation raw CSR snapshot in
   `ProductDataResolver`, with its own combinational decoder and `servePost`
   arbitration. It must remain independent; replacing slots with decoded
   interval snapshots roughly doubles their 992-bit PMP payloads at PA56.

The experimental module can be considered for **front-end only**, and only
after all of these gates are implemented and demonstrated:

- CSR retirement initiates an *atomic* refresh from stable canonical CSR
  values, including packed pmpcfg writes and TOR predecessor dependencies.
- New instruction fetches **and existing fetch PTW PTE handshakes** are
  blocked through the refresh; SYSTEM serialization must not release early.
- No partial cache is consumed, and repeated PMP writes cannot interleave
  refreshes or expose inconsistent permission windows.
- Dedicated simulation confirms all 16 cached ranges against the existing
  combinational decoder, including reset, repeated refreshes, upper address,
  TOR, OFF, NA4, NAPOT and unchanged cache without a refresh.
- A complete Product XC7 hierarchy mapping, followed by actual device
  placement/routing and timing, shows acceptable LUT/FF/Fmax and exact Linux
  behavior. No serial result can be multiplied by two because the data decoder
  cannot be replaced without a separate snapshot-ownership design.

As of this document, Scala source and test compilation and local XC7 mapping
have completed. **The dynamic ChiselSim regression is still unrun locally**
because Verilator is unavailable. The optional GitHub workflow can run that
focused test. No full SoC integration, physical timing signoff, or Debian
qualification has been asserted.
