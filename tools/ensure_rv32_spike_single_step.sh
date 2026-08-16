#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REVISION="530af85d83781a3dae31a4ace84a573ec255fefa"
EXPECTED_SHA256="85b02befce3e98383080c28f33f18bb4d08282cf97e2e6b7f2f7e334a223c85f"
CACHE_ROOT="${AETHERCORE_REFERENCE_CACHE:-$HOME/.cache/aethercore/references}"
CACHE_DIR="$CACHE_ROOT/rv32imc-spike-single-step-$REVISION"
REFERENCE_SO="$CACHE_DIR/rv32imc-spike-reference.so"
EVIDENCE_DIR="$CACHE_DIR/evidence"
CANONICAL_WORK_DIR="${AETHERCORE_RV32_SPIKE_WORK_DIR:-$ROOT/build/rv32-spike-reference}"

validate_reference() {
  local path="$1"
  [[ -f "$path" ]] || return 1
  [[ "$(sha256sum "$path" | awk '{print $1}')" == "$EXPECTED_SHA256" ]]
}

mkdir -p "$CACHE_ROOT"

if validate_reference "$REFERENCE_SO"; then
  printf 'aethercore_rv32_spike_cache=hit\n' >&2
  printf 'aethercore_rv32_spike_revision=%s\n' "$REVISION" >&2
  printf 'aethercore_rv32_spike_sha256=%s\n' "$EXPECTED_SHA256" >&2
  printf '%s\n' "$REFERENCE_SO"
  exit 0
fi

printf 'aethercore_rv32_spike_cache=build\n' >&2
printf 'aethercore_rv32_spike_canonical_work_dir=%s\n' "$CANONICAL_WORK_DIR" >&2
candidate="$(bash "$ROOT/tools/probe_rv32_spike_deterministic.sh" "$CANONICAL_WORK_DIR")"
[[ -f "$candidate" ]] || {
  printf 'ERROR: deterministic RV32IMC Spike build did not produce a shared object\n' >&2
  exit 1
}

if ! validate_reference "$candidate"; then
  actual="$(sha256sum "$candidate" | awk '{print $1}')"
  printf 'ERROR: exact RV32IMC Spike SHA256 changed: expected=%s actual=%s\n' \
    "$EXPECTED_SHA256" "$actual" >&2
  exit 1
fi

staging="$(mktemp -d "$CACHE_ROOT/.rv32imc-spike-single-step.XXXXXX")"
mkdir -p "$staging/evidence"
install -m 0755 "$candidate" "$staging/rv32imc-spike-reference.so"
if [[ -d "$CANONICAL_WORK_DIR/evidence" ]]; then
  cp -a "$CANONICAL_WORK_DIR/evidence/." "$staging/evidence/"
fi
cat > "$staging/evidence/cache-result.txt" <<EOF
status=PASS
revision=$REVISION
reference_sha256=$EXPECTED_SHA256
isa=RV32IMC_Zicsr
priv=M
single_step=1
cache_format=rv32imc-spike-single-step-v1
canonical_work_dir=$CANONICAL_WORK_DIR
EOF

rm -rf "$CACHE_DIR"
mv "$staging" "$CACHE_DIR"
validate_reference "$REFERENCE_SO" || {
  printf 'ERROR: installed RV32IMC Spike cache failed exact SHA validation\n' >&2
  exit 1
}

printf 'aethercore_rv32_spike_revision=%s\n' "$REVISION" >&2
printf 'aethercore_rv32_spike_sha256=%s\n' "$EXPECTED_SHA256" >&2
printf '%s\n' "$REFERENCE_SO"
