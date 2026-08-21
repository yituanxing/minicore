#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REVISION="8601834e4889e6bf3b6113eb5f824ba7689126f5"
SOFTFLOAT_REVISION="a0c6494cdc11865811dec815d5c0049fba9d82a8"
EXPECTED_DERIVED_DEFCONFIG_SHA256="9221c1979f056b978179d36404ab3801aa474b67560efcb8093d2da0fef4791a"
EXPECTED_GENERATED_CONFIG_SHA256="52ed03a1c6e9c57b6fac319d245c5e0af31589f7d305519cea6eabee0e68ca56"
EXPECTED_BUILD_COMPOSITION_SHA256="a218e0ee1b15a461ff27e1bda133d43bf21ccf14977463faf4b872f071c788fa"
CACHE_FORMAT="rv32-nemu-single-step-v2"
CACHE_ROOT="${AETHERCORE_REFERENCE_CACHE:-$HOME/.cache/aethercore/references}"
CACHE_DIR="$CACHE_ROOT/$CACHE_FORMAT-$REVISION"
REFERENCE_SO="$CACHE_DIR/riscv32-nemu-interpreter-so"
EVIDENCE_DIR="$CACHE_DIR/evidence"
CANONICAL_WORK_DIR="${AETHERCORE_RV32_NEMU_WORK_DIR:-$ROOT/build/rv32-nemu-probe}"

file_sha256() {
  sha256sum "$1" | awk '{print $1}'
}

validate_reference() {
  local path="$1"
  local evidence="$2"
  local actual_sha

  [[ -f "$path" ]] || return 1
  [[ -f "$evidence/result.txt" ]] || return 1
  [[ -f "$evidence/reproducibility.txt" ]] || return 1
  [[ -f "$evidence/derived.defconfig" ]] || return 1
  [[ -f "$evidence/generated.config" ]] || return 1
  [[ -f "$evidence/build-composition.patch" ]] || return 1
  [[ -f "$evidence/abi-probe.txt" ]] || return 1

  grep -qx 'status=PASS' "$evidence/result.txt" || return 1
  grep -qx "revision=$REVISION" "$evidence/result.txt" || return 1
  grep -qx "softfloat_revision=$SOFTFLOAT_REVISION" "$evidence/result.txt" || return 1
  grep -qx 'regcpy_bytes=132' "$evidence/result.txt" || return 1
  grep -qx 'reproducible=true' "$evidence/result.txt" || return 1
  grep -qx 'single_step=1' "$evidence/result.txt" || return 1
  grep -qx 'perf_opt=false' "$evidence/result.txt" || return 1
  grep -qx "derived_defconfig_sha256=$EXPECTED_DERIVED_DEFCONFIG_SHA256" "$evidence/result.txt" || return 1
  grep -qx "generated_config_sha256=$EXPECTED_GENERATED_CONFIG_SHA256" "$evidence/result.txt" || return 1
  grep -qx "build_composition_sha256=$EXPECTED_BUILD_COMPOSITION_SHA256" "$evidence/result.txt" || return 1

  [[ "$(file_sha256 "$evidence/derived.defconfig")" == "$EXPECTED_DERIVED_DEFCONFIG_SHA256" ]] || return 1
  [[ "$(file_sha256 "$evidence/generated.config")" == "$EXPECTED_GENERATED_CONFIG_SHA256" ]] || return 1
  [[ "$(file_sha256 "$evidence/build-composition.patch")" == "$EXPECTED_BUILD_COMPOSITION_SHA256" ]] || return 1

  grep -qx 'prefix_matches=true' "$evidence/abi-probe.txt" || return 1
  grep -qx 'guard_matches=true' "$evidence/abi-probe.txt" || return 1
  grep -qx 'memory_roundtrip_matches=true' "$evidence/abi-probe.txt" || return 1

  actual_sha="$(file_sha256 "$path")"
  grep -qx "reference_sha256=$actual_sha" "$evidence/result.txt" || return 1
  grep -qx "first_sha256=$actual_sha" "$evidence/reproducibility.txt" || return 1
  grep -qx "second_sha256=$actual_sha" "$evidence/reproducibility.txt" || return 1
}

install_candidate() {
  local candidate="$1"
  local candidate_evidence="$2"
  local staging

  validate_reference "$candidate" "$candidate_evidence" || return 1

  staging="$(mktemp -d "$CACHE_ROOT/.rv32-nemu-single-step.XXXXXX")"
  mkdir -p "$staging/evidence"
  install -m 0755 "$candidate" "$staging/riscv32-nemu-interpreter-so"
  cp -a "$candidate_evidence/." "$staging/evidence/"
  printf '%s\n' "$CACHE_FORMAT" > "$staging/evidence/cache-format.txt"
  rm -rf "$CACHE_DIR"
  mv "$staging" "$CACHE_DIR"
}

print_reference() {
  local actual_sha
  actual_sha="$(file_sha256 "$REFERENCE_SO")"
  printf 'aethercore_rv32_nemu_revision=%s\n' "$REVISION" >&2
  printf 'aethercore_rv32_nemu_sha256=%s\n' "$actual_sha" >&2
  printf 'aethercore_rv32_nemu_recipe=derived:%s generated:%s composition:%s\n' \
    "$EXPECTED_DERIVED_DEFCONFIG_SHA256" \
    "$EXPECTED_GENERATED_CONFIG_SHA256" \
    "$EXPECTED_BUILD_COMPOSITION_SHA256" >&2
  printf '%s\n' "$REFERENCE_SO"
}

mkdir -p "$CACHE_ROOT"

if validate_reference "$REFERENCE_SO" "$EVIDENCE_DIR"; then
  printf 'aethercore_rv32_nemu_cache=hit\n' >&2
  print_reference
  exit 0
fi

if [[ -n "${AETHERCORE_RV32_NEMU_CANDIDATE:-}" && -n "${AETHERCORE_RV32_NEMU_CANDIDATE_EVIDENCE:-}" ]]; then
  if install_candidate \
    "$AETHERCORE_RV32_NEMU_CANDIDATE" \
    "$AETHERCORE_RV32_NEMU_CANDIDATE_EVIDENCE"; then
    printf 'aethercore_rv32_nemu_cache=seeded\n' >&2
    printf 'aethercore_rv32_nemu_source=%s\n' "$AETHERCORE_RV32_NEMU_CANDIDATE" >&2
    print_reference
    exit 0
  fi
fi

# Binary bytes are not the authority for this historical unstripped reference:
# its host compiler and absolute source path are visible ELF inputs. The frozen
# authority is the pinned NEMU/SoftFloat source, exact derived/generated config,
# exact AetherCore build-composition patch, ABI probe and full runtime DiffTest.
# A cold build must reproduce byte-for-byte twice on the same host; the actual
# resulting SHA is recorded and then used to protect the cached file itself.
printf 'aethercore_rv32_nemu_cache=build\n' >&2
printf 'aethercore_rv32_nemu_work_dir=%s\n' "$CANONICAL_WORK_DIR" >&2
NEMU_SINGLE_STEP=1 bash "$ROOT/tools/probe_rv32_nemu_deterministic.sh" \
  "$CANONICAL_WORK_DIR" >&2
candidate="$(find "$CANONICAL_WORK_DIR/nemu/build" -maxdepth 1 -type f \
  -name 'riscv32-nemu-interpreter-so*' -print -quit)"
[[ -n "$candidate" ]] || {
  printf 'ERROR: exact RV32 NEMU build did not produce a shared object\n' >&2
  exit 1
}
validate_reference "$candidate" "$CANONICAL_WORK_DIR/evidence" || {
  printf 'ERROR: cold RV32 NEMU reference failed frozen recipe/ABI provenance validation\n' >&2
  exit 1
}

install_candidate "$candidate" "$CANONICAL_WORK_DIR/evidence"
print_reference