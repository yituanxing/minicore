# S0.1 architecture

## Pipeline

`IF -> ID -> EX -> MEM -> WB/commit`

- Branches and jumps resolve in EX.
- EX/MEM and MEM/WB forwarding cover ordinary ALU dependencies.
- A load-use dependency inserts one bubble.
- A blocked data request freezes younger stages while allowing an older WB instruction to retire once.
- Architectural integer state changes only in WB; stores become visible on a completed memory transaction.

## Commit contract

Every retired instruction emits one `CommitTrace` record containing PC, instruction, destination result, memory side effects and exception status. Future DiffTest will consume only this architectural boundary.

## Temporary traps

S0.1 marks illegal instructions, bus faults, ECALL and EBREAK as commit exceptions and halts. Precise trap entry and CSR updates are deferred to the privileged-architecture stage.

## Future microarchitecture notes

The current v1 pipeline remains the correctness/reference architecture.

- [`AETHERCORE_V2_KICKOFF_CONSTITUTION.md`](AETHERCORE_V2_KICKOFF_CONSTITUTION.md) records the implementation-start rules: architectural parameters, microarchitectural seams, identity/Commit ownership, the preferred hybrid selective-OoO target and staged bring-up sequence.
- [`AETHERCORE_V2_DESIGN_NOTES.md`](AETHERCORE_V2_DESIGN_NOTES.md) preserves broader alternatives, reference-core lessons and open performance questions rather than acting as a frozen specification.
- [`AETHERCORE_V2_REUSE_AUDIT.md`](AETHERCORE_V2_REUSE_AUDIT.md) records the current KEEP / WRAP / EARLY-ONLY / REWRITE boundary against the qualified v1 implementation.
