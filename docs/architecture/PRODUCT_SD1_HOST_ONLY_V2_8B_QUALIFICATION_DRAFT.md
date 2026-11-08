# SD1 Host-only v2 8B boundary — isolated qualification draft

Status: **NOT QUALIFIED; NO GUEST RUN AUTHORIZED**. This draft does not change
the production 8,000M guard or authorize a long run. In particular, official
run `42490269438` and its artifacts are immutable inputs, not test targets.

## Why another migration is necessary

The current migrated-v1 executable SHA is
`b9a651a3ba3aa50c7e0b15405d24a3d3ab56dd25fa2df7fb20f5654cdda80461`.
Both `product_sd1_migrated_autorelay_20m.sh` and
`product-sd1-migrated-autorelay-five-reusable.yml` bind to that model and
`START+100 <= 8000` (with an aligned batch start). Their Host executable also
caps its migrated relay at 8,000,000,000 cycles. Neither shell override nor a
changed batch input permits the 7992M to 8012M transition. This is a Host
policy ceiling, **not** evidence of an RTL 32-bit wrap; a different boundary
at 2^32 cycles was previously exercised.

The permitted candidate modifies only the frozen **Host** checkpoint runner:
an explicit `migrate-v2-8b` zero-clock mode rooted exactly at 7892M, plus an
explicit `SD1_FULL_DEBIAN_SIGNED_RELAY_V3_MIGRATED_8P1B` 8,100M profile.
The old migration/relay tokens and old guards remain intact. The generated
Verilator savable RTL (`.cpp`, `.h`, `.mk`, generated `.o`/`.a`) MUST remain
byte-identical. New Host executable has a new SHA-256 and cannot pretend to be
the old model: full Host snapshots bind an exact 64-byte model SHA field.

## Independent source provenance

These GitHub run/artifact identities must be verified by the eventual isolated
v2 workflow **against GitHub REST metadata and the downloaded ZIP**, not merely
provided as user inputs or inferred from a green job:

| Source | Exact immutable pins |
| --- | --- |
| Signed source run | `42490269438`; head `e7bbd704dea0dd924355599efc35958947ac8062`; PR #379; workflow `.github/workflows/product-sd1-migrated-7812-to-7912m-autorelay.yml` |
| 7892M milestone | Artifact ID `13620623868`, ZIP SHA-256 `2e1fd3dc9462c097007039fd9d0cbdcbb0ca610baeca9d8b537d3b5fa4ee5b5f`; Host `e56979117362737e4803e008acd9a47389596e7a70d82775dc5e024b060d57e3`; RTL `4fab764965598b0da80dda02cc8661b5cde7f874c5117c8893ba0f02df207bab`; card evidence `c02d2a3a354ac729b505aa71577c1d7ae9b9cbe6c6f7b33b2bff4596f4171936` |
| 7912M terminal | Artifact ID `13621890399`, ZIP SHA-256 `5b37fe21a1cd82b462efbf034da7152d7dc9203773deb7831f2a06253a05897a` |
| 7912M Host | `d12da91d3bdeff9f803eb14220a07e9b5e0078bdd02ced4d1f45112885a45b60` |
| 7912M RTL | `8097af2264d1b15c31589b28d1d534547d0b8edb8c754ab78eb2a7dc752a1041` |
| 7912M card | `56235070cf2090a56cf4b94ddf900455abb154027153cc0dbeb2f3958eb2c0ff` |

Both stages must have `.migration-ready` schema `AETSDCP1`, same old model
`b9a651...`, original polling ancestor `5a39da...`, the exact unchanged signed
media SHAs, actual `boot_cycles` of 7,892M / 7,912M, and matching complete
Host/RTL/card-progress hashes. The 7912M seal must bind `source_cycles=7892M`
and contain the exact source Host and RTL hashes of the authenticated 7892M.

## Qualification is a mandatory 20M A/B differential

1. In an **isolated local output path**, copy the qualified migrated-v1
   `candidate-savable-model` and regenerate only the Host `.o` and executable;
   compare all generated RTL source/object aggregate hashes with the old
   qualification manifest. Do not touch the official running pipeline.
2. Run `migrate-v2-8b` on authenticated 7892M **with zero guest cycles**.
   Compare all RTL bytes and all Host bytes except `[24,88)` of the model SHA
   field. Host file length and snapshot schema must match exactly.
3. With the new Host only, advance this 7892M state by **20,000,000 genuine
   Product RTL cycles** to 7912M. Compare full RTL and full Host except the
   64-byte SHA field against the **independently attested old 7912M** artifact.
   Also check original signed media, unchanged generated model and the real
   `PRODUCT_SD1_PERSISTENT_STAGE_ADVANCED` marker.
4. Only then may a review consider promoting the new Host SHA and the v2 7912M
   sealed snapshot. Record old/new model hashes, all four source/target Host and
   RTL hashes, ZIP/run provenance, exact 20M diff result, source media pins,
   and `cross_8b_executed=0` in a durable qualification certificate.

The draft script `tools/ci/product_sd1_model_migration_v2_qualify.sh --plan`
is zero-write. `--execute-qualified-diff` is intentionally gated by *two*
independent exact confirmation tokens and requires explicit existing canonical
7892M/7912M source paths. **It has not been executed.** It cannot validate
GitHub REST provenance offline: an independently label-gated PR#379 workflow
must do that before any future authorized execution.

## Isolated five-stage continuation (future, NOT enabled)

Only after independent review and proof of step 3 may a *new* driver/workflow
continue exact consecutive stages:

`tools/ci/product_sd1_migrated_v2_relay_20m.sh` is an isolated **review
draft**, defaulting to read-only `--plan`, rejecting missing reviewer tokens,
and requiring a future separately reviewed v2 executable SHA. The independent
**A-only** qualification workflow template is
`.github/workflows/product-sd1-migrated-v2-8b-qualification-DRAFT.yml`.
Its execution mechanism is a **new uniquely labeled workflow on PR#379**
(`product-sd1-host-v2-7892-to-7912-qualify`), while the code checked out is
an **immutable exact commit SHA** from the separate
`experiment/sd1-host-v2-8b-20261108` branch. The shim's
`EXPERIMENT_SOURCE_SHA` remains `UNREVIEWED_FAIL_CLOSED` until the candidate
commit is independently reviewed. Only this new workflow file would be added
to PR#379; no official Host, runner, existing workflow/driver guard, or
running-job state needs to change. A caps its real RTL replay at **7912M**,
never invokes the v2 relay driver, and cannot cross 8B. B requires its own
separate approval and workflow.

This avoids a GitHub Actions cache-scope trap: caches created under the
PR#379 merge ref may not be visible to a sibling experiment-branch dispatch.
The new isolated A job executes on the same PR#379 labeled event (and can
therefore consume its own previously authenticated caches) while explicitly
checking out only the pinned experiment source commit. Cache hits remain hard
gates; any miss aborts before guest execution.

### A: CI artifact/cache restore and execution evidence

The A-only workflow reuses the **exact frozen public cache keys**, never
silently rebuilding a missing RTL model or regenerating seed media:

- Official Debian seed: `product-sd1-official-seed-v2` content-hash key, with
  only the original explicitly pinned v1 fallback key allowed.
- Diagnostic GPIO-CS Linux Image: original `sd1-gpio-cs-diagnostic-image-v1`
  content-hash key, requiring an exact cache hit.
- Verilator 5.054: `aethercore-Linux-X64-verilator-product-gsim-g3-v1-0d5cab64c9f95f1a19e38d7220450b5555d6a853d6ac53283fab6b1ca779c1d8`.
- Complete frozen savable Verilator RTL model/object cache:
  `aethercore-Linux-X64-product-sd1-savable-portable-v1-x86_64-89e4c31da3dafc5a664e-b1ea68eed0a37532d6ec1feb08d5e5061e99336b8b8e17accbdbb000997895b9`.
- Original polling firmware/DTB: official run `42338584047`, artifact
  `product-sd1-spi-polling-build-42338584047`.
- Two old real RTL checkpoints: run `42490269438`, artifact names
  `product-sd1-migrated-7892m-42490269438` and
  `product-sd1-migrated-7912m-42490269438`, with the ZIP and full states
  pinned above.
- Existing qualified v1 binary/manifest: run `42384463475`, artifact
  `product-sd1-model-migrate-v1-qualified-42384463475`, ZIP SHA
  `e511af2854a110bc0998a0c8a6de7e36378e8a1a68d90568e811577c931e7ee9`.

**Non-obvious recovery pitfall:** the v1 artifact holds the `b9a651...`
executable and qualification manifest, **not** all generated Verilator model
files. A must restore the exact original polling savable-model cache, copy it
only into a new A-only output tree, overlay the SHA-verified old v1 executable
there, then compare the saved generated-RTL source and object hashes against
the v1 qualification manifest. The stale original Host `.o` in that copied
cache is removed **only in the second new isolated candidate copy**, forcing
Host-only recompilation; the generator outputs cannot change. Failure to
restore either cache aborts before a guest clock.

The workflow publishes `product-sd1-model-migrate-v2-qual-7892-7912-<run>`
only after the entire A/B differential passes, plus a distinct diagnostic
artifact on any attempted run. The new Host executable SHA cannot be known
until that exact compiler/link has completed; it is sealed into the A
manifest and must be explicitly approved and hard-pinned before designing or
enabling B. Expected hazards are expired GitHub artifacts, GitHub step-index
eventual consistency, cache misses, compiler/Verilator ABI drift, model bytes
changing during rebuild, and any 20M Host/RTL/SD differential mismatch. All
are fail-closed and **never authorize >8B**.

| Stage | Cycles | Crosses 8B? |
| --- | ---: | --- |
| 1 | 7912M → 7932M | No |
| 2 | 7932M → 7952M | No |
| 3 | 7952M → 7972M | No |
| 4 | 7972M → 7992M | No |
| 5 | 7992M → 8012M | **Yes** |

It must pin the *new* executable/qualification SHA and its 7912M v2 stage,
require exact previous 20M Host/RTL/card manifests at every hop, keep 8,100M
hard maximum, perform the full original SD1 CPU observer and SD-card checks,
and publish immutable per-stage artifacts with an authenticated final 8012M
certificate. Do **not** reuse or edit the currently running old workflow/driver;
they intentionally reject these inputs. A cycle count, green CI, or QEMU
success is **not** a signed Debian RTL `service/login` success claim.

Gate failure is fail-closed. No 8012M run may begin before the above
qualification, review, and separate new driver/workflow are complete.
