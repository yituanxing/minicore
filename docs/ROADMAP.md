# Roadmap

- S0.2: real Chisel/CIRCT/Verilator build passes.
- S0.3: directed RV64I matrix, pipeline hazards and precise fault-boundary regression.
- S0.4: pinned NEMU commit-level DiffTest for all directed normal retirements.
- S0.5: checker mismatch probe plus deterministic seeded RV64I generated DiffTest.
- S1: complete RV64M semantics, directed/generated differential testing and memory-stall forwarding fix.
- S1.1: GCC-produced freestanding RV64IM programs, ELF/link/disassembly evidence and 3621 additional NEMU comparisons.
- S1.2: real-program expansion with sort, CRC/hash, mixed integer kernels, `-O0/-O2/-Os`, and Minic/GCC same-source comparison.
- S1.3: multi-cycle M-unit request/busy/response handshake, driven by the frozen compiled binary corpus.
- S2: M/S/U privilege, CSR, exceptions and interrupts.
- S3: Sv39, TLB and blocking I/D caches.
- S4: OpenSBI, Linux and BusyBox.
- S5: FPGA synthesis, timing closure and board bring-up.
- S6+: dual issue, ROB, register renaming and partial out-of-order execution.
