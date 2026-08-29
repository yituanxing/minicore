# AetherCore v2 A8 completion ownership contract

A8.2 closes the completion boundary before selective issue changes execution policy.

## Why this exists

F3-F7 were intentionally oldest-only. Under that policy System, LSU and ordinary execution could not legitimately complete independent live uOps in the same cycle, so the F5/F6 compositions used mutual-exclusion assertions and priority muxes. That assumption stops being safe as soon as younger exception-free work may execute around a blocked head.

A selective backend therefore needs a completion contract before it needs a scheduler.

## Contract

1. Every completion-producing unit owns its response until an explicit `Decoupled` handshake.
2. `valid && !ready` means the complete `ExecutionResponse` is stable. A producer may not discard, recompute, or replace it while stalled.
3. The first mature seam still accepts at most one completion into the ROB per cycle. Multi-broadcast is not part of A8.2.
4. Simultaneously pending producers are legal. They are arbitrated explicitly rather than asserted impossible.
5. Arbitration must provide bounded progress. Fixed priority is not sufficient once independent variable-latency producers can remain pending.
6. Completion arbitration owns transport only. It does not own ROB age, exception precision, branch recovery, memory ordering, architectural side effects, or scheduling policy.
7. The ROB remains the final lifetime validator. A transported response may still be stale and may be rejected by full `RobToken + ProducerTag + ValueRef` matching.
8. A producer cannot free the state needed to reproduce its completion before the completion handshake. For the blocking LSU, terminal transaction state remains owned until `completion.fire`.
9. System completion is side-effect free. CSR/trap/xRET mutation remains retirement-owned even if the response waits in the completion seam.
10. Physical memory transaction identity remains independent from ROB completion identity.

## Initial topology

```text
Integer / Branch / Mul / Div
            |
      execution merge
            |
System -----+---\
LSU --------+----> round-robin completion merge --> one ROB completion / cycle
Exec -------+---/
```

The execution-cluster merge must also become fair before selective issue can continuously feed several function units. A held DIV result must not be starved by an unbounded stream of one-cycle integer results.

## Explicit non-goals

A8.2 does not add:

- oldest-ready scheduling;
- an issue queue or reservation station;
- branch execution out of order;
- multiple ROB completion writes per cycle;
- multiple retire per cycle;
- a PRF/free list;
- nonblocking cache/MSHR behavior;
- speculative memory disambiguation.

Those mechanisms are admitted only after this transport/lifetime seam is qualified.

## Qualification requirements

Focused regressions must prove at least:

- LSU completion remains valid and bit-stable across downstream backpressure;
- the LSU remains busy and cannot accept a replacement memory uOp until its held completion fires;
- System completion remains stable across backpressure and emits a given observed head lifetime only once;
- simultaneous completion sources are delivered without loss;
- a continuously pending source receives bounded service under round-robin arbitration;
- inherited F1-F7 exact behavior remains green;
- unchanged OpenSBI/Linux qualification remains green on the exact A8 candidate head.
