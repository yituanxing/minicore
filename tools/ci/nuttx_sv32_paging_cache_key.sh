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

cache_base="${AETHERCORE_N5_SOFTWARE_CACHE_ROOT:-${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/n5-software}"
cache_dir="${cache_base}/${key}"
cache_elf="${cache_dir}/nuttx.elf"
cache_result="${cache_dir}/result.txt"
cache_sha="${cache_dir}/nuttx.elf.sha256"
cache_key="${cache_dir}/input-key.txt"

require_target() {
  [[ -n "$TARGET_DIR" ]] || {
    echo "usage: $0 ${MODE} TARGET_DIR" >&2
    exit 2
  }
}

validate_cache_entry() {
  [[ -s "$cache_elf" ]] || {
    echo "N5 software cache miss: $cache_elf is missing" >&2
    return 1
  }
  [[ -f "$cache_result" && -f "$cache_sha" && -f "$cache_key" ]] || {
    echo "N5 software cache miss: metadata is incomplete under $cache_dir" >&2
    return 1
  }
  grep -Fqx 'status=PASS' "$cache_result" || {
    echo "N5 software cache miss: cached result is not PASS" >&2
    return 1
  }
  [[ "$(cat "$cache_key")" == "$key" ]] || {
    echo "N5 software cache miss: cached content key does not match" >&2
    return 1
  }
  local expected actual
  expected="$(awk '{print $1; exit}' "$cache_sha")"
  actual="$(sha256sum "$cache_elf" | awk '{print $1}')"
  [[ -n "$expected" && "$actual" == "$expected" ]] || {
    echo "N5 software cache miss: cached ELF digest mismatch" >&2
    return 1
  }
}

case "$MODE" in
  key)
    printf '%s\n' "$key"
    ;;
  check)
    require_target
    validate_cache_entry || exit 1

    mkdir -p "${TARGET_DIR}/evidence"
    cp -f "$cache_elf" "${TARGET_DIR}/nuttx.elf"
    cp -f "$cache_result" "${TARGET_DIR}/evidence/result.txt"
    cp -f "$cache_key" "${TARGET_DIR}/evidence/input-key.txt"
    cp -f "$cache_sha" "${TARGET_DIR}/evidence/nuttx.elf.sha256"

    restored="$(sha256sum "${TARGET_DIR}/nuttx.elf" | awk '{print $1}')"
    expected="$(awk '{print $1; exit}' "$cache_sha")"
    [[ "$restored" == "$expected" ]] || {
      echo "ERROR: restored N5 software image digest mismatch" >&2
      exit 1
    }
    printf 'N5 software cache hit: key=%s cache=%s restored=%s\n' \
      "$key" "$cache_dir" "${TARGET_DIR}/nuttx.elf"
    ;;
  mark)
    require_target
    source_elf="${TARGET_DIR}/nuttx.elf"
    source_result="${TARGET_DIR}/evidence/result.txt"
    [[ -s "$source_elf" ]] || {
      echo "ERROR: missing $source_elf" >&2
      exit 1
    }
    [[ -f "$source_result" ]] || {
      echo "ERROR: missing N5-B result" >&2
      exit 1
    }
    grep -Fqx 'status=PASS' "$source_result" || {
      echo "ERROR: N5-B result is not PASS" >&2
      exit 1
    }

    mkdir -p "$cache_base"
    temp_dir="${cache_base}/.${key}.tmp.$$"
    rm -rf "$temp_dir"
    mkdir -p "$temp_dir"
    cp -f "$source_elf" "${temp_dir}/nuttx.elf"
    cp -f "$source_result" "${temp_dir}/result.txt"
    printf '%s\n' "$key" > "${temp_dir}/input-key.txt"
    sha256sum "${temp_dir}/nuttx.elf" | awk '{print $1 "  nuttx.elf"}' > "${temp_dir}/nuttx.elf.sha256"

    rm -rf "$cache_dir"
    mv "$temp_dir" "$cache_dir"

    mkdir -p "${TARGET_DIR}/evidence"
    cp -f "$cache_key" "${TARGET_DIR}/evidence/input-key.txt"
    cp -f "$cache_sha" "${TARGET_DIR}/evidence/nuttx.elf.sha256"
    validate_cache_entry
    printf 'N5 software cache marked: key=%s cache=%s\n' "$key" "$cache_dir"
    ;;
  *)
    echo "usage: $0 {key|check|mark} [TARGET_DIR]" >&2
    exit 2
    ;;
esac
