#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REVISION="530af85d83781a3dae31a4ace84a573ec255fefa"
EXPECTED_SHA256="22dd74006570af19495c6b5449eec908d246c0c6d700f7eabda16001a0ca62df"
CACHE_ROOT="${AETHERCORE_REFERENCE_CACHE:-$HOME/.cache/aethercore/references}"
CACHE_DIR="$CACHE_ROOT/rv32imc-spike-$REVISION-$EXPECTED_SHA256"
REFERENCE_SO="$CACHE_DIR/rv32imc-spike-reference.so"
LOCK_FILE="$CACHE_ROOT/.rv32imc-spike-reference.lock"
BUILD_WORK_DIR="${AETHERCORE_RV32_SPIKE_RESOLVE_WORK_DIR:-$ROOT/build/rv32c-spike-reference-resolve}"

validate_reference() {
  local path="$1"
  [[ -f "$path" ]] || return 1
  [[ "$(sha256sum "$path" | awk '{print $1}')" == "$EXPECTED_SHA256" ]]
}

for tool in sha256sum flock mktemp install; do
  command -v "$tool" >/dev/null 2>&1 || {
    printf 'ERROR: RV32C Spike resolver requires host tool: %s\n' "$tool" >&2
    exit 1
  }
done

mkdir -p "$CACHE_ROOT"
if validate_reference "$REFERENCE_SO"; then
  printf 'aethercore_rv32c_spike_cache=hit\n' >&2
  printf 'aethercore_rv32c_spike_revision=%s\n' "$REVISION" >&2
  printf 'aethercore_rv32c_spike_sha256=%s\n' "$EXPECTED_SHA256" >&2
  printf '%s\n' "$REFERENCE_SO"
  exit 0
fi

exec 9>"$LOCK_FILE"
flock 9

# Another consumer may have populated the exact cache while we waited.
if validate_reference "$REFERENCE_SO"; then
  printf 'aethercore_rv32c_spike_cache=hit-after-lock\n' >&2
  printf 'aethercore_rv32c_spike_revision=%s\n' "$REVISION" >&2
  printf 'aethercore_rv32c_spike_sha256=%s\n' "$EXPECTED_SHA256" >&2
  printf '%s\n' "$REFERENCE_SO"
  exit 0
fi

printf 'aethercore_rv32c_spike_cache=build\n' >&2
candidate="$(
  AETHERCORE_RV32_SPIKE_WORK_DIR="$BUILD_WORK_DIR" \
    bash "$ROOT/tools/ensure_rv32c_spike_reference.sh"
)"
validate_reference "$candidate" || {
  actual="$(sha256sum "$candidate" 2>/dev/null | awk '{print $1}' || true)"
  printf 'ERROR: cold RV32C Spike builder did not produce the frozen reference: expected=%s actual=%s\n' \
    "$EXPECTED_SHA256" "$actual" >&2
  exit 1
}

staging="$(mktemp -d "$CACHE_ROOT/.rv32imc-spike.XXXXXX")"
cleanup() {
  [[ -z "${staging:-}" ]] || rm -rf "$staging"
}
trap cleanup EXIT
mkdir -p "$staging/evidence"
install -m 0755 "$candidate" "$staging/rv32imc-spike-reference.so"
if [[ -d "$BUILD_WORK_DIR/evidence" ]]; then
  cp -a "$BUILD_WORK_DIR/evidence/." "$staging/evidence/"
fi
cat > "$staging/evidence/cache-result.txt" <<EOF
status=PASS
revision=$REVISION
reference_sha256=$EXPECTED_SHA256
isa=RV32IMC_Zicsr
priv=M
cache_format=rv32imc-spike-reference-v2
cold_builder=tools/ensure_rv32c_spike_reference.sh
EOF

rm -rf "$CACHE_DIR"
mv "$staging" "$CACHE_DIR"
staging=""
validate_reference "$REFERENCE_SO" || {
  printf 'ERROR: installed RV32C Spike cache failed exact SHA validation\n' >&2
  exit 1
}

printf 'aethercore_rv32c_spike_revision=%s\n' "$REVISION" >&2
printf 'aethercore_rv32c_spike_sha256=%s\n' "$EXPECTED_SHA256" >&2
printf '%s\n' "$REFERENCE_SO"
