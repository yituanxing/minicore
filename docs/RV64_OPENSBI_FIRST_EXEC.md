# RV64 OpenSBI first execution

This exploratory layer stacks directly on frozen RV64 OpenSBI platform head
`be05c45c8acb2a6b85b971f3ae13c6d411a9bfad`.

Acceptance is intentionally narrower than Linux:

1. build pinned unchanged OpenSBI 1.6 for `rv64ima_zicsr_zifencei/lp64`;
2. execute that firmware on the real shared `AetherCoreOpenSbiRV64SimTop`;
3. observe the upstream `OpenSBI v1.6` banner;
4. hand off to the bounded S-mode payload at `0x80200000` with hart 0 and the
   frozen RV64/Sv39 FDT address;
5. complete an SBI Base `GET_SPEC_VERSION` S-to-M round trip;
6. print `RV64 OpenSBI S-mode payload PASS` through the existing ns16550 path.

No Linux image, OpenSBI source patch, new CPU feature, or second host runtime is
owned by this slice.
