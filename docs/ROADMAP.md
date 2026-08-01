# Roadmap

- S0.2: real Chisel/CIRCT/Verilator build passes.
- S0.3: directed RV64I matrix, pipeline hazards and precise fault-boundary regression.
- S0.4: pinned NEMU commit-level DiffTest for all directed normal retirements.
- S0.5: checker mismatch probe plus deterministic seeded RV64I generated DiffTest.
- S1: complete RV64M semantics, directed/generated differential testing and memory-stall forwarding fix.
- S1.1: GCC-produced freestanding RV64IM programs, ELF/link/disassembly evidence and 3621 additional NEMU comparisons.
- S1.2: frozen 12-binary real-program corpus with sort, CRC/hash, mixed integer kernels and `-O0/-O2/-Os`; 207337 total verified retirements.
- S1.3: Minic/GCC same-source corpus production using the compiler-neutral manifest.
- S1.4: multi-cycle M-unit request/busy/response handshake, driven by unchanged frozen binaries.
- S2: M/S/U privilege, CSR, exceptions and interrupts.
- S3: Sv39, TLB and blocking I/D caches.
- S4: OpenSBI, Linux and BusyBox.
- S5: FPGA synthesis, timing closure and board bring-up.
- S6+: dual issue, ROB, register renaming and partial out-of-order execution.
