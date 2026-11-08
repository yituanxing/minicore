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

The original P0 fixture then looped on `wfi`; the P0.1 extension instead
deliberately traps on a machine-only CSR access, with all interrupts disabled.
There is no MMIO or block-device activity in the fixture. The single active PMP NAPOT entry is
`pmpaddr0=0x21ffffff`, `pmpcfg0=0x1f`, allowing RWX for the exact DDR aperture
`[0x80000000, 0x90000000)`; all other PMP entries remain zero. This is
deliberately broader than an OS security policy and is **P0-only**.

## P0.1: prove the returned privilege mode

The independently preserved P0 proof is commit `e7c83574d3c107a3eb41dc7645e4ef6a52616411`,
run `42482915330`, artifact `13618930889`. That first genuine G4 RTL run
retired MRET at cycle 701 and five verified source-RAM instructions by cycle
745. It established a minimal executable restore, **not** the S-mode privilege
level, QEMU source equivalence or Linux operation.

The follow-up synthetic fixture adds a sixth instruction immediately after
those five stores: `csrr x13,mstatus` (raw instruction `0x300026f3`).
`mstatus` is a machine-only CSR.  Real S-mode execution must retire a precise
illegal-instruction exception at `0x80100014`, cause 2, trap value equal to the
raw instruction. M-mode execution would be legal and **must fail this gate**.
The host checks all four original marker words first, rejects premature or
unrelated exceptions, and only then accepts the exact S-mode privilege trap.
It intentionally stops on exception retirement; no trap vector needs to run.
The new marker is
`PRODUCT_C1_P01_RTL_S_MODE_ILLEGAL_CSR_TRAP_PASS_NOT_QEMU`.

**Verified real Product RTL P0.1 evidence:** commit
`14b5774a45db89f9c2df4c46cff65ea583ba32a1`, Actions run
`42484108729`, artifact `13618478367` (ZIP SHA-256
`65bd1ff616df7139378f0050e0b92f00a244db07284f733ac4e7268088a838d4`).
The actual RTL retired `mret` at cycle 701, matched all five normal guest
retirements and four source-RAM markers, then retired an illegal instruction
trap at cycle **749**: `pc=0x80100014`, raw `0x300026f3`, cause `2`,
trap value `0x300026f3`.  This is a privilege-sensitive proof of S-mode
execution, not a QEMU/Linux state handoff.

This verifies the privilege transition in one isolated synthetic guest. It
does not make QEMU/Linux state export compatible with the restricted P0 ABI.

The first genuine acceptance needs the compiled code executing in Product
RTL, a successful S-mode return, all four DDR marker values, and a bounded
retirement trace aligned against expected instructions. Merely compiling,
packing the state, passing Python tests, or seeing a QEMU result is not a
Product RTL handoff PASS.
