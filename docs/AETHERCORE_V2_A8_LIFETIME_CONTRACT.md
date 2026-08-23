# AetherCore v2 A8 — backend lifetime contract

Status: A8 architecture-closure contract after frozen F7 software parity.

Frozen architectural reference: `418a4b798410df45c0218f1a0122cdf0199211f6` (F7).

This contract closes one prerequisite for selective out-of-order execution: a backend identity must distinguish a live lifetime from sufficiently recent killed/retired lifetimes even when a circular ROB slot is reused.

It does **not** claim that a finite generation field is a globally unique instruction sequence number.

## 1. Identity roles remain separate

AetherCore v2 keeps three backend identities conceptually distinct:

```text
RobToken
  program-order / lifetime identity

ProducerTag
  dependency / wakeup identity

ValueRef
  speculative value-storage identity
```

The current tiny implementation allocates all three from the same ROB slot and generation. That physical collapse is an implementation convenience, not an architectural equation between the concepts.

Completion acceptance continues to require the complete current identity tuple to match the live ROB entry:

```text
RobToken(index, generation)
ProducerTag(id, generation)
ValueRef(id, generation)
```

No completion may be accepted merely because the circular ROB index matches.

## 2. Generation is a bounded lifetime discriminator

The A8 tiny ROB remains four entries but changes its current implementation geometry from:

```text
GenerationBits = 2
```

to:

```text
GenerationBits = 8
GenerationReuseBudget = 256 lifetimes per slot
```

A generation changes whenever a slot's lifetime ends through retirement, and a squashed younger slot receives a new generation immediately.

The purpose is to make a recently stale response fail identity validation after slot reuse.

The important interpretation is:

> generation is a finite anti-alias budget, not a monotonic globally unique instruction ID.

After 256 lifetime endings of the same slot, an 8-bit generation can numerically repeat. Correctness therefore also requires a response-lifetime rule.

## 3. Bounded-response rule for backend functional units

Any internal producer that retains a backend identity after its uOp may be squashed must satisfy at least one of these conditions:

1. it produces or discards a terminal response within a bounded time that is safely shorter than the same-slot generation reuse budget;
2. it is explicitly killed/cancelled when the lifetime is invalidated;
3. it retains/reserves an identity so that the same identity cannot be reallocated until the producer reaches a terminal state;
4. it uses an additional/wider epoch mechanism whose lifetime contract is independently defined.

The current iterative divider is an example of case 1: it stores the request identity, executes a bounded restoring division, and eventually presents one terminal response. The future completion network must preserve bounded/fair response progress; an arbiter must not be allowed to starve such a response indefinitely while the ROB slot cycles through the full generation space.

Integer, branch and multiply responses are already much shorter-lived than the divider.

If a future FPU/vector/accelerator unit can retain work for substantially longer or has unbounded backpressure, its lifetime policy must be reviewed explicitly rather than assuming that eight generation bits solve the problem automatically.

## 4. External memory lifetime is a different identity domain

Potentially long or externally delayed memory transactions must not use `RobToken` as their sole transaction identity.

The internal memory contract already has an independent transaction identity:

```text
AetherMemRequest.txnId
AetherMemResponse.txnId
```

The mature non-blocking memory design will bind these transaction identities to MSHR / LSU transaction lifetime. A memory response may then be:

- routed to the still-live transaction;
- recognized as belonging to a killed/retired transaction;
- drained/discarded according to memory-system policy;

without requiring a ROB lifetime to remain globally unique for arbitrary external latency.

This distinction is deliberate:

```text
RobToken lifetime
    !=
AetherMem transaction lifetime
```

The memory subsystem may carry a reference back to a RobToken for eventual completion, but the transport response itself is owned and validated by the transaction domain first.

## 5. Why F7 exposed the issue

F7 used a four-entry ROB and two-bit generation. A given slot therefore repeated its numeric generation after four lifetimes, and a complete `RobToken` could repeat after sixteen sequential retired instructions.

A real OpenSBI `fdt_size_cells()` frontier exposed a related bug in once-only issue state: an issue latch remembered a numeric RobToken beyond the lifetime in which it was observed and could suppress a genuinely new instruction after numeric reuse.

F7 fixed the semantic ownership rule:

> once-only issue state belongs to the currently observed head lifetime and is cleared when that observed lifetime changes.

A8 keeps that regression but removes dependence on the historical two-bit wrap distance. Numeric token reuse is now tested directly at the issue unit, while the full-core OpenSBI-shaped regression remains as a software-level ownership check.

## 6. Why A8 strengthens the budget before selective issue

Oldest-only F7 sharply limits how many independently executing killed operations can survive a redirect.

Selective issue changes that assumption. A future state may look like:

```text
ROB0  old blocking work
ROB1  DIV issued -----------------------> late response
ROB2  branch resolves / redirect
ROB3  younger work
```

After recovery, the killed DIV may still finish while its original ROB slot has already begun new lifetimes.

The generation field therefore becomes an active stale-response safety mechanism rather than mostly a defensive invariant.

A8 widens the current budget **before** arbitrary-age issue is enabled so the scheduler experiment does not silently inherit the narrow F7 alias window.

## 7. What the current tests prove

The focused ROB tests prove:

- a slot generation changes on ordinary circular reuse;
- a stale completion from the previous generation is rejected;
- ProducerTag and ValueRef must also match;
- a stale completion remains distinct even after crossing the former two-bit alias window;
- the current matching completion still retires normally.

The issue-lifetime tests prove:

- a head lifetime issues once while it remains the same observed lifetime;
- observing a different head lifetime clears the once-only ownership state even if the replacement request is backpressured;
- a later lifetime may reuse the exact same numeric RobToken and still issue, because numeric history is not permanent issue ownership.

These tests do **not** prove that an eight-bit generation can never wrap. They prove the intended bounded-lifetime contract and protect the old failure mode while increasing the implementation safety budget.

## 8. This is not a new public microarchitecture knob

`GenerationBits = 8` is a current implementation constant of the tiny A8 backend.

It is not added to `IsaConfig`, is not software-visible, and does not create a promise that arbitrary generation widths are supported configurations.

This follows the project rule:

> Parameterize architectural choices; seam microarchitectural choices.

If later ROB geometry, execution latency or FPGA measurements justify another lifetime representation, that representation may change behind the existing typed identity seams.

## 9. Gate before P8.1 selective issue

A8 lifetime closure is considered ready only after the focused v2 ROB/issue regressions pass on the candidate exact head and the normal v2 correctness gates remain green.

Only then should P8.1 allow younger Integer/MUL/DIV work to issue ahead of the ROB head.

The next independent A8 prerequisite is the completion contract: overlapping execution sources must have explicit buffering/backpressure/fair arbitration rather than relying on F7's oldest-only assumption that at most one top-level completion source can become valid in a cycle.
