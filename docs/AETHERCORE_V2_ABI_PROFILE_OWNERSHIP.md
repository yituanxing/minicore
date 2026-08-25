# AetherCore v2 ISA / ABI Profile Ownership

## Scope

This closure is stacked on P8.3 completion-bandwidth frozen exact head `7428de98cce038b2ebe838fc7c96aed9f64686ed`.

It closes one remaining configuration-model inconsistency discovered after the RV32/RV64 architecture audit: `IsaConfig` correctly owned XLEN and ISA/compiler `-march` identity, but also derived `-mabi` solely from XLEN (`RV32 -> ilp32`, `RV64 -> lp64`). That was sufficient for the current integer-only workload corpus but assigned software ABI ownership to the wrong layer.

No CPU datapath, scheduling, completion, memory, VM, PMP, privilege, platform or bus behavior belongs to this change.

## Ownership model

The stable model is now:

```text
IsaConfig
  - XLEN
  - instruction extensions
  - privilege modes
  - VM modes / architectural geometry selection
  - compiler ISA identity (`march`)

AbiConfig
  - ABI identity (`ilp32`, `lp64`, `ilp32f`, ...)
  - ABI-required ISA extensions
  - ISA/ABI compatibility validation

SoftwareTarget
  - explicit IsaConfig + AbiConfig pair
  - compiler-facing `march` + `mabi`

CoreConfig / AetherCoreCapabilities
  - whether the current production AetherCore RTL implements the selected ISA

PlatformConfig
  - physical integration geometry and qualified platform map
```

The important rule is that these are different questions:

1. **Can the architecture be described?** `IsaConfig`.
2. **Is this software ABI compatible with that ISA?** `AbiConfig` / `SoftwareTarget`.
3. **Can today's AetherCore RTL implement that ISA?** `CoreConfig` / `AetherCoreCapabilities`.

A positive answer to one does not imply a positive answer to the others.

## Concrete examples

Current qualified integer software remains explicit:

```text
RV32IM + Zicsr + ILP32
RV64IM + Zicsr/Zifencei + LP64
```

But those are explicit software-target pairs, not identities implied by XLEN.

The model can also describe a future architectural/software pair such as:

```text
RV64IFD + LP64D
```

`SoftwareTarget` accepts that pair because LP64D requires F/D and the ISA supplies them. The current `CoreConfig` still rejects it because production AetherCore does not implement F/D. That separation is deliberate and executable in `AbiConfigSpec`.

Likewise, `RV32 + LP64` and `RV64 + ILP32` fail the current standard ABI compatibility model instead of being inferred or silently accepted.

## Why historical build scripts are not mass-rewritten

The repository's real software builders already pass explicit `-mabi=ilp32` or `-mabi=lp64` flags and therefore do not consume `IsaConfig.mabi`. Rewriting every frozen historical workload script in this closure would churn qualified software evidence without improving ownership.

This change instead fixes the typed configuration source of truth and gives future compiler/workload tooling one canonical `SoftwareTarget` seam. Existing frozen workload identities remain unchanged.

## Mechanical guards

`tests_py/test_v2_abi_profile_ownership.py` freezes that:

- `IsaConfig` owns `march` but contains no `mabi` state;
- `AbiConfig` owns ABI identity and required ISA capabilities;
- `SoftwareTarget` explicitly combines ISA + ABI and emits compiler-facing `march/mabi`;
- production Scala cannot read ABI through `isa.mabi`;
- `CoreConfig` remains a separate implementation-capability gate.

`AbiConfigSpec` additionally executes positive and negative compatibility cases, including the intentionally important case where an ISA/ABI pair is architecturally valid but production AetherCore rejects the ISA.

## Non-goals

This closure does not:

- implement F or D;
- add RV64ILP32 or non-standard ABI variants;
- change Linux/OpenSBI/musl/FreeRTOS/NuttX build flags;
- change syscall ABIs or operating-system execution environments;
- make `busDataBits` independent from XLEN;
- change any v2 RTL.

## Consequence

After this closure, the major configuration dimensions are explicitly separated:

```text
XLEN / ISA / privilege / VM / PA geometry / ABI / production capability /
platform / microarchitecture
```

The remaining known integration coupling `busDataBits == XLEN` stays documented as a bounded bus-integration constraint, not an ISA or ABI identity. It should be relaxed only when a real cache/interconnect/FPGA width-adapter need appears.

After qualification, configuration/profile structural work stops again and the project returns directly to P8.4 memory concurrency.
