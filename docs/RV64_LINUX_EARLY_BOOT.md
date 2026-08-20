# RV64 unchanged Linux early boot

This slice stacks directly on frozen RV64 OpenSBI first-execution head
`814c524dcab6a26905c4aff9d9b2c617acdc808a`.

The acceptance boundary is intentionally narrow:

1. build the pinned unchanged Linux 6.6.143 archive with a configuration-only
   RV64 platform contract (`rv64`, MMU, no C/F/V, ns16550 console);
2. place the raw RISC-V `Image` at the native RV64 2 MiB handoff address
   `0x80200000` inside unchanged OpenSBI 1.6;
3. reuse the frozen RV64/Sv39 DTB and real `AetherCoreOpenSbiRV64SimTop`;
4. reuse the historical OpenSBI/Linux host runtime through the existing RV64
   compile-time type shim;
5. observe upstream `OpenSBI v1.6` and then the real kernel UART banner
   `Linux version 6.6.143`.

There is no initramfs, BusyBox, shell, source patch, new CPU feature, RV64C,
Sstc, or performance optimization in this layer. Once the banner is frozen,
the next frontier is deeper unchanged Linux initialization on the same exact
hardware/software lineage.
