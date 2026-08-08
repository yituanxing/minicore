#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE="${1:-key}"
TARGET_DIR="${2:-}"

inputs=(
  software/nuttx/manifest.env
  tools/ci/nuttx_sv32_paging_build_audit.sh
  tools/ci/nuttx_sv32_paging_aether_profile_build.sh
  tools/ci/audit_riscv_elf_profile.py
  tools/ci/nuttx_prepare_host_tools.sh
  tools/ci/ensure_genromfs.sh
  tools/ci/kconfig-tweak
)

key="$({
  for path in "${inputs[@]}"; do
    printf '%s  ' "$path"
    sha256sum "${ROOT_DIR}/${path}" | awk '{print $1}'
  done
} | sha256sum | awk '{print $1}')"

case "$MODE" in
  key)
    printf '%s\n' "$key"
    ;;
  check)
    [[ -n "$TARGET_DIR" ]] || { echo "usage: $0 check TARGET_DIR" >&2; exit 2; }
    stamp="${TARGET_DIR}/evidence/input-key.txt"
    result="${TARGET_DIR}/evidence/result.txt"
    [[ -s "${TARGET_DIR}/nuttx.elf" ]] || exit 1
    [[ -f "$stamp" && -f "$result" ]] || exit 1
    grep -Fqx 'status=PASS' "$result" || exit 1
    [[ "$(cat "$stamp")" == "$key" ]] || exit 1
    printf 'N5 software cache hit: %s\n' "$key"
    ;;
  mark)
    [[ -n "$TARGET_DIR" ]] || { echo "usage: $0 mark TARGET_DIR" >&2; exit 2; }
    [[ -s "${TARGET_DIR}/nuttx.elf" ]] || { echo "ERROR: missing ${TARGET_DIR}/nuttx.elf" >&2; exit 1; }
    [[ -f "${TARGET_DIR}/evidence/result.txt" ]] || { echo "ERROR: missing N5-B result" >&2; exit 1; }
    grep -Fqx 'status=PASS' "${TARGET_DIR}/evidence/result.txt" || { echo "ERROR: N5-B result is not PASS" >&2; exit 1; }
    mkdir -p "${TARGET_DIR}/evidence"
    printf '%s\n' "$key" > "${TARGET_DIR}/evidence/input-key.txt"
    printf 'N5 software cache marked: %s\n' "$key"
    ;;
  *)
    echo "usage: $0 {key|check|mark} [TARGET_DIR]" >&2
    exit 2
    ;;
esac
