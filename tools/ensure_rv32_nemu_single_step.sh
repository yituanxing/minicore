#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REVISION="8601834e4889e6bf3b6113eb5f824ba7689126f5"
EXPECTED_SHA256="e1e18bec22a1e6a19dbb300b43063ed5d3216a8d9f6ccf6400355d4fb897de9e"
CACHE_ROOT="${AETHERCORE_REFERENCE_CACHE:-$HOME/.cache/aethercore/references}"
CACHE_DIR="$CACHE_ROOT/rv32-nemu-single-step-$REVISION"
REFERENCE_SO="$CACHE_DIR/riscv32-nemu-interpreter-so"
EVIDENCE_DIR="$CACHE_DIR/evidence"
CANONICAL_WORK_DIR="${AETHERCORE_RV32_NEMU_WORK_DIR:-$ROOT/build/rv32-nemu-probe}"

validate_reference() {
  local path="$1"
  [[ -f "$path" ]] || return 1
  [[ "$(sha256sum "$path" | awk '{print $1}')" == "$EXPECTED_SHA256" ]]
}

install_candidate() {
  local candidate="$1"
  validate_reference "$candidate" || return 1

  local staging
  staging="$(mktemp -d "$CACHE_ROOT/.rv32-nemu-single-step.XXXXXX")"
  mkdir -p "$staging/evidence"
  install -m 0755 "$candidate" "$staging/riscv32-nemu-interpreter-so"
  cat > "$staging/evidence/result.txt" <<EOF
status=PASS
revision=$REVISION
reference_sha256=$EXPECTED_SHA256
single_step=1
cache_format=rv32-nemu-single-step-v1
canonical_work_dir=$CANONICAL_WORK_DIR
EOF
  rm -rf "$CACHE_DIR"
  mv "$staging" "$CACHE_DIR"
}

mkdir -p "$CACHE_ROOT"

if validate_reference "$REFERENCE_SO"; then
  printf 'aethercore_rv32_nemu_cache=hit\n' >&2
  printf 'aethercore_rv32_nemu_revision=%s\n' "$REVISION" >&2
  printf 'aethercore_rv32_nemu_sha256=%s\n' "$EXPECTED_SHA256" >&2
  printf '%s\n' "$REFERENCE_SO"
  exit 0
fi

candidates=()
if [[ -n "${AETHERCORE_RV32_NEMU_CANDIDATE:-}" ]]; then
  candidates+=("$AETHERCORE_RV32_NEMU_CANDIDATE")
fi
if [[ -s "$ROOT/build/ci/rv32-nemu-so.txt" ]]; then
  candidates+=("$(cat "$ROOT/build/ci/rv32-nemu-so.txt")")
fi
while IFS= read -r candidate; do
  candidates+=("$candidate")
done < <(
  find "$ROOT/build" -type f -name 'riscv32-nemu-interpreter-so*' -print 2>/dev/null || true
)

for candidate in "${candidates[@]}"; do
  if install_candidate "$candidate"; then
    printf 'aethercore_rv32_nemu_cache=seeded\n' >&2
    printf 'aethercore_rv32_nemu_source=%s\n' "$candidate" >&2
    printf 'aethercore_rv32_nemu_revision=%s\n' "$REVISION" >&2
    printf 'aethercore_rv32_nemu_sha256=%s\n' "$EXPECTED_SHA256" >&2
    printf '%s\n' "$REFERENCE_SO"
    exit 0
  fi
done

# The historical NEMU binary contains its absolute build path. The accepted
# SHA256 is therefore reproducible only at the same repository-relative path
# used by the Full Gate. Build there once, then copy the verified .so into the
# path-independent persistent cache for all later Fast Gate runs.
printf 'aethercore_rv32_nemu_cache=build\n' >&2
printf 'aethercore_rv32_nemu_canonical_work_dir=%s\n' "$CANONICAL_WORK_DIR" >&2
NEMU_SINGLE_STEP=1 bash "$ROOT/tools/probe_rv32_nemu_deterministic.sh" \
  "$CANONICAL_WORK_DIR" >&2
candidate="$(find "$CANONICAL_WORK_DIR/nemu/build" -maxdepth 1 -type f \
  -name 'riscv32-nemu-interpreter-so*' -print -quit)"
[[ -n "$candidate" ]] || {
  printf 'ERROR: exact RV32 NEMU build did not produce a shared object\n' >&2
  exit 1
}
validate_reference "$candidate" || {
  actual="$(sha256sum "$candidate" | awk '{print $1}')"
  printf 'ERROR: canonical exact RV32 NEMU SHA256 changed: expected=%s actual=%s\n' \
    "$EXPECTED_SHA256" "$actual" >&2
  exit 1
}

install_candidate "$candidate"
cp -a "$CANONICAL_WORK_DIR/evidence/." "$EVIDENCE_DIR/"
printf 'aethercore_rv32_nemu_revision=%s\n' "$REVISION" >&2
printf 'aethercore_rv32_nemu_sha256=%s\n' "$EXPECTED_SHA256" >&2
printf '%s\n' "$REFERENCE_SO"
