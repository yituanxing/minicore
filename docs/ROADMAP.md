# Roadmap

- S0.2: real Chisel/CIRCT/Verilator build passes.
- S0.3: directed RV64I matrix, pipeline hazards and precise fault-boundary regression.
- S0.4: pinned NEMU commit-level DiffTest for all directed normal retirements.
- S0.5: checker mismatch probe plus deterministic seeded RV64I generated DiffTest.
- S1: complete RV64M semantics, directed/generated differential testing and memory-stall forwarding fix.
- S1.1: GCC-produced freestanding RV64IM programs, ELF/link/disassembly evidence and 3621 additional NEMU comparisons.
- S1.2: frozen 12-binary real-program corpus with sort, CRC/hash, mixed integer kernels and `-O0/-O2/-Os`; 207337 total verified retirements.
- S1.3: pinned upstream bare-metal software, beginning with CoreMark and expanding to Embench-IoT.
- S1.4: storage and library workloads: littlefs plus a freestanding subset of musl routines.
- S2: FreeRTOS-driven machine CSRs, exceptions, timer/software interrupts and context switching.
- S3: xv6-riscv-driven A extension, M/S/U privilege, Sv39, TLBs and essential devices.
- S4: OpenSBI, Linux, musl, BusyBox and deterministic user-space programs including Lua and SQLite.
- S5: use the complete frozen software ladder to drive cache, multi-cycle execution, branch and bus performance work.
- S6: FPGA synthesis, timing closure and board bring-up.
- S7+: optional superscalar work such as dual issue, ROB, register renaming and partial out-of-order execution.

Correctness and software completeness precede performance optimization. Every
upstream failure must be reduced to a focused permanent regression before RTL
is changed, and every microarchitectural change must rerun the exact frozen
binary hashes without recompilation.
