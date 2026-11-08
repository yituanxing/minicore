# Product C1 P0 synthetic restore fixture

This fixture is deliberately **not** a Linux checkpoint, QEMU state export,
SD1 image, or proof of a working QEMU-to-RTL handoff. It is the first
deterministic *instruction-based* Product RTL restore test.

## Reproduce

The existing CI can provision the frozen RV64 GCC 13.3 cross toolchain:

```bash
bash tools/ensure_riscv64_linux_gcc_13_3.sh
bash tools/ci/product_c1_arch_relay/build_p0_fixture.sh
python3 -m unittest -v \
  tests_py.test_product_c1_arch_p0_fixture \
  tests_py.test_product_c1_arch_restore_abi
```

The bootstrap script exports the cross compiler prefix via
`AETHERCORE_RV64_LINUX_CROSS_COMPILE` on GitHub Actions. Outside CI, set that
variable to the installed compiler's prefix before running the build.

Generated artifacts under `build/product-c1-arch-p0/`:

| File | Meaning |
| --- | --- |
| `linked.elf` | Unstripped RV64 fixture ELF, fixed-address linker layout |
| `restore-flat.bin` | **Only** the restorer and 424-byte state, within first 1 MiB |
| `state-424.bin` | Synthetic, ABI-validated PC/GPR/CSR/PMP source state |
| `guest-flat.bin` | **Only** the S-mode test instructions and marker storage |
| `ram-256m.bin` | Sparse 256 MiB original DDR, with guest at +0x100000 |
| `expected.json` | Synthetic inputs, layout, and expected memory effects; RTL PASS false |
| `symbols.txt`, `elf-sections.txt`, `sha256sums.txt` | Link and provenance diagnostics |

## Address and overlay contract

| Address | Ownership |
| --- | --- |
| `0x80000000` | M-mode restore entry, overlay-only |
| `0x80018000` | Restore state table, overlay-only (`+0x18000`) |
| `0x80100000` | Original DDR S-mode target; *outside* reserved overlay |
| `0x80101000` | Original DDR marker storage, 32 bytes |

The Host overlay must disappear at the return to the original S-mode program
so that future DDR fetches and loads use the unmodified source RAM. Its flat
binary must never be treated as the original 256 MiB source RAM or extended
beyond the reserved first MiB. A single combined objcopy image of all ELF
sections would violate that boundary and is **not** produced.

The test target stores four values to its DDR marker region:

1. restored register `x5`, expected `0x5555555555555555`;
2. restored register `x6`, expected `0x6666666666666666`;
3. restored register `x11`, expected `0xcafebabedeadbeef`;
4. incremented `x12`, expected `42`.

It then loops on `wfi`, with all interrupts disabled. There is no MMIO or
block-device activity in the fixture. The single active PMP NAPOT entry is
`pmpaddr0=0x21ffffff`, `pmpcfg0=0x1f`, allowing RWX for the exact DDR aperture
`[0x80000000, 0x90000000)`; all other PMP entries remain zero. This is
deliberately broader than an OS security policy and is **P0-only**.

The first genuine acceptance needs the compiled code executing in Product
RTL, a successful S-mode return, all four DDR marker values, and a bounded
retirement trace aligned against expected instructions. Merely compiling,
packing the state, passing Python tests, or seeing a QEMU result is not a
Product RTL handoff PASS.
