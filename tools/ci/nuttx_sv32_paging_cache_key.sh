#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE="${1:-key}"
TARGET_DIR="${2:-}"
PROFILE="${AETHERCORE_NUTTX_N5_PROFILE:-rv32ima}"

case "$PROFILE" in
  rv32ima|rv32imac) ;;
  *)
    echo "ERROR: unsupported N5 software profile: $PROFILE" >&2
    exit 2
    ;;
esac

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
  printf 'profile=%s\n' "$PROFILE"
  for path in "${inputs[@]}"; do
    printf '%s  ' "$path"
    sha256sum "${ROOT_DIR}/${path}" | awk '{print $1}'
  done
} | sha256sum | awk '{print $1}')"

cache_base="${AETHERCORE_N5_SOFTWARE_CACHE_ROOT:-${AETHERCORE_CACHE_ROOT:-${HOME}/.cache/aethercore}/n5-software}"
cache_dir="${cache_base}/${key}"
cache_elf="${cache_dir}/nuttx.elf"
cache_user_elf="${cache_dir}/user-init.elf"
cache_result="${cache_dir}/result.txt"
cache_isa_audit="${cache_dir}/isa-audit.txt"
cache_sha="${cache_dir}/nuttx.elf.sha256"
cache_user_sha="${cache_dir}/user-init.elf.sha256"
cache_key="${cache_dir}/input-key.txt"
cache_profile="${cache_dir}/profile.txt"

require_target() {
  [[ -n "$TARGET_DIR" ]] || {
    echo "usage: $0 ${MODE} TARGET_DIR" >&2
    exit 2
  }
}

validate_cache_entry() {
  [[ -s "$cache_elf" && -s "$cache_user_elf" ]] || {
    echo "N5 software cache miss: cached kernel/userspace ELF is missing" >&2
    return 1
  }
  [[ -f "$cache_result" && -f "$cache_isa_audit" && -f "$cache_sha" && \
     -f "$cache_user_sha" && -f "$cache_key" && -f "$cache_profile" ]] || {
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
  [[ "$(cat "$cache_profile")" == "$PROFILE" ]] || {
    echo "N5 software cache miss: cached profile does not match $PROFILE" >&2
    return 1
  }
  grep -Fqx "software_profile=$PROFILE" "$cache_result" || {
    echo "N5 software cache miss: cached result does not freeze profile $PROFILE" >&2
    return 1
  }
  if [[ "$PROFILE" == rv32imac ]]; then
    grep -Eq '^kernel_compressed_instructions=[1-9][0-9]*$' "$cache_isa_audit" || {
      echo "N5 software cache miss: RV32IMAC kernel C evidence is missing" >&2
      return 1
    }
    grep -Eq '^user_compressed_instructions=[1-9][0-9]*$' "$cache_isa_audit" || {
      echo "N5 software cache miss: RV32IMAC userspace C evidence is missing" >&2
      return 1
    }
  else
    grep -Fqx 'kernel_compressed_instructions=0' "$cache_isa_audit" || return 1
    grep -Fqx 'user_compressed_instructions=0' "$cache_isa_audit" || return 1
  fi

  local kernel_expected kernel_actual user_expected user_actual
  kernel_expected="$(awk '{print $1; exit}' "$cache_sha")"
  kernel_actual="$(sha256sum "$cache_elf" | awk '{print $1}')"
  [[ -n "$kernel_expected" && "$kernel_actual" == "$kernel_expected" ]] || {
    echo "N5 software cache miss: cached kernel ELF digest mismatch" >&2
    return 1
  }
  user_expected="$(awk '{print $1; exit}' "$cache_user_sha")"
  user_actual="$(sha256sum "$cache_user_elf" | awk '{print $1}')"
  [[ -n "$user_expected" && "$user_actual" == "$user_expected" ]] || {
    echo "N5 software cache miss: cached userspace ELF digest mismatch" >&2
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
    cp -f "$cache_user_elf" "${TARGET_DIR}/user-init.elf"
    cp -f "$cache_result" "${TARGET_DIR}/evidence/result.txt"
    cp -f "$cache_isa_audit" "${TARGET_DIR}/evidence/isa-audit.txt"
    cp -f "$cache_key" "${TARGET_DIR}/evidence/input-key.txt"
    cp -f "$cache_sha" "${TARGET_DIR}/evidence/nuttx.elf.sha256"
    cp -f "$cache_user_sha" "${TARGET_DIR}/evidence/user-init.elf.sha256"
    cp -f "$cache_profile" "${TARGET_DIR}/evidence/profile.txt"

    kernel_restored="$(sha256sum "${TARGET_DIR}/nuttx.elf" | awk '{print $1}')"
    kernel_expected="$(awk '{print $1; exit}' "$cache_sha")"
    user_restored="$(sha256sum "${TARGET_DIR}/user-init.elf" | awk '{print $1}')"
    user_expected="$(awk '{print $1; exit}' "$cache_user_sha")"
    [[ "$kernel_restored" == "$kernel_expected" && "$user_restored" == "$user_expected" ]] || {
      echo "ERROR: restored N5 software image digest mismatch" >&2
      exit 1
    }
    printf 'N5 software cache hit: profile=%s key=%s cache=%s restored=%s,%s\n' \
      "$PROFILE" "$key" "$cache_dir" "${TARGET_DIR}/nuttx.elf" "${TARGET_DIR}/user-init.elf"
    ;;
  mark)
    require_target
    source_elf="${TARGET_DIR}/nuttx.elf"
    source_user_elf="${TARGET_DIR}/user-init.elf"
    source_result="${TARGET_DIR}/evidence/result.txt"
    source_isa_audit="${TARGET_DIR}/evidence/isa-audit.txt"
    [[ -s "$source_elf" && -s "$source_user_elf" ]] || {
      echo "ERROR: missing N5-B kernel/userspace ELF" >&2
      exit 1
    }
    [[ -f "$source_result" && -f "$source_isa_audit" ]] || {
      echo "ERROR: missing N5-B result/ISA audit" >&2
      exit 1
    }
    grep -Fqx 'status=PASS' "$source_result" || {
      echo "ERROR: N5-B result is not PASS" >&2
      exit 1
    }
    grep -Fqx "software_profile=$PROFILE" "$source_result" || {
      echo "ERROR: N5-B result does not match profile $PROFILE" >&2
      exit 1
    }

    mkdir -p "$cache_base"
    temp_dir="${cache_base}/.${key}.tmp.$$"
    rm -rf "$temp_dir"
    mkdir -p "$temp_dir"
    cp -f "$source_elf" "${temp_dir}/nuttx.elf"
    cp -f "$source_user_elf" "${temp_dir}/user-init.elf"
    cp -f "$source_result" "${temp_dir}/result.txt"
    cp -f "$source_isa_audit" "${temp_dir}/isa-audit.txt"
    printf '%s\n' "$key" > "${temp_dir}/input-key.txt"
    printf '%s\n' "$PROFILE" > "${temp_dir}/profile.txt"
    sha256sum "${temp_dir}/nuttx.elf" | awk '{print $1 "  nuttx.elf"}' > "${temp_dir}/nuttx.elf.sha256"
    sha256sum "${temp_dir}/user-init.elf" | awk '{print $1 "  user-init.elf"}' > "${temp_dir}/user-init.elf.sha256"

    rm -rf "$cache_dir"
    mv "$temp_dir" "$cache_dir"

    mkdir -p "${TARGET_DIR}/evidence"
    cp -f "$cache_key" "${TARGET_DIR}/evidence/input-key.txt"
    cp -f "$cache_sha" "${TARGET_DIR}/evidence/nuttx.elf.sha256"
    cp -f "$cache_user_sha" "${TARGET_DIR}/evidence/user-init.elf.sha256"
    cp -f "$cache_profile" "${TARGET_DIR}/evidence/profile.txt"
    validate_cache_entry
    printf 'N5 software cache marked: profile=%s key=%s cache=%s\n' "$PROFILE" "$key" "$cache_dir"
    ;;
  *)
    echo "usage: $0 {key|check|mark} [TARGET_DIR]" >&2
    exit 2
    ;;
esac
