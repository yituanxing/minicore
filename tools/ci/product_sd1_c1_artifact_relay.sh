#!/usr/bin/env bash
# Explicit, first-party-only C1 pre-rootfs snapshot artifact transfer.
# This opt-in path deliberately differs from the strict SD1 short checkpoint:
# unmounted Linux may have read SD sectors, hence the v2 read-ledger must be
# restored byte-for-byte with the complete Verilator/DDR/SPI host state.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
fail() { printf 'PRODUCT_SD1_C1_ARTIFACT_REJECTED: %s\n' "$*" >&2; exit 3; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
field() {
  local values
  [[ -s "$2" && -f "$2" && ! -L "$2" ]] || fail "missing manifest $2"
  values="$(sed -n "s/^$1=//p" "$2")"
  [[ -n "$values" && "$values" != *$'\n'* ]] || fail "missing or duplicate field $1"
  printf '%s' "$values"
}
require_file() { [[ -f "$1" && ! -L "$1" && -s "$1" ]] || fail "missing, empty or symlinked $1"; }
in_build() {
  local resolved
  resolved="$(realpath -e "$1")" || fail "missing build input $1"
  [[ "$resolved" == "$ROOT/build/"* && "$resolved" != *$'\n'* ]] || fail 'artifact member outside build'
  [[ -f "$resolved" && ! -L "$1" ]] || fail 'non-regular artifact member'
  printf '%s' "${resolved#"$ROOT/"}"
}
# Reusing a saved *binary* requires the full snapshot ABI and the boot-prefix
# semantics to remain unchanged, not an identical Git commit. Deliberately
# conservative: all Scala RTL, simulator C++/headers, original SD1 media seed
# lock, C1 guest source and every simulator/kernel/firmware/build script that
# can affect the already-executed prefix. Rootfs build and replacement media
# files are intentionally excluded: that is the feature this cache provides.
compatibility_sha() {
  local -a input=()
  local file
  input+=(
    build.mill mill
    software/rv64/linux_early.env
    software/rv64/opensbi_first_exec.env
    software/rv64/product_sd1_official_seed.lock
    software/rv64_userspace/product_sd1_c1_init.c
    tools/ci/patches/linux-6.6.143-mmc-spi-gpio-cs-off.patch
    tools/ci/product_checkpoint_sd1_short.sh
    tools/ci/product_sim_cache.sh
    tools/ci/product_sim_sd1_manual.sh
    tools/ci/product_sd1_official_seed.py
    tools/ci/rv64_minimal_init_payload_build.sh
    tools/ci/rv64_linux_early_build.sh
    tools/ensure_riscv64_linux_gcc_13_3.sh
    tools/ensure_verilator_5_024.sh
    tools/ensure_verilator_5_054.sh
  )
  while IFS= read -r -d '' file; do
    input+=("${file#"$ROOT/"}")
  done < <(find "$ROOT/src/main/scala" "$ROOT/sim" -type f \
    \( -name '*.scala' -o -name '*.cpp' -o -name '*.h' \) -print0)
  while IFS= read -r -d '' file; do
    input+=("${file#"$ROOT/"}")
  done < <(find "$ROOT/tools/ci" -maxdepth 1 -type f \
    \( -name 'product_sd1_c1_*.sh' -o -name 'product_sd1_c1_*.py' \) -print0)
  (( ${#input[@]} > 20 )) || fail 'too few C1 compatibility inputs'
  (
    cd "$ROOT"
    printf '%s\n' "${input[@]}" | LC_ALL=C sort -u | while IFS= read -r file; do
      [[ -s "$file" && ! -L "$file" ]] || fail "missing C1 compatibility input $file"
      sha256sum -- "$file"
    done | sha256sum | cut -d' ' -f1
  )
}
gate() {
  [[ "${AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT:-}" == 1 &&
     "${AETHERCORE_SD1_C1_CONFIRM:-}" == GENERIC_LINUX_ROOTFS_BOUNDARY &&
     "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY &&
     "${AETHERCORE_VERILATOR_VERSION:-}" == 5.054 &&
     "${SIM_SETTLE_MODE:-}" == reference &&
     "${VERILATOR_THREADS:-}" == 1 &&
     "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-}" == 0 ]] || fail 'explicit C1 portable first-party profile required'
  [[ -z "${RV64_SD1_LOGIN_USER:-}" && -z "${RV64_SD1_LOGIN_PASSWORD_HASH_FILE:-}" ]] ||
    fail 'credentials must not enter a public C1 snapshot'
  python3 tools/ci/product_sd1_official_seed.py verify
  local console="$ROOT/build/product-sd1-official-seed/console-provisioning.txt"
  [[ "$(field console_login_credentials "$console")" == NOT_PROVISIONED &&
     "$(field console_login_user "$console")" == NONE ]] ||
    fail 'only credential-free sealed official Debian SD media is allowed'
}

[[ "$ACTION" == package || "$ACTION" == restore || "$ACTION" == fingerprint ]] || {
  echo 'usage: product_sd1_c1_artifact_relay.sh fingerprint | package CHECKPOINT_DIR EMPTY_ARTIFACT_DIR | restore ARTIFACT_DIR' >&2
  exit 2
}
if [[ "$ACTION" == fingerprint ]]; then
  [[ $# == 1 ]] || fail 'fingerprint takes no other arguments'
  printf 'PRODUCT_SD1_C1_COMPATIBILITY_SHA256=%s\n' "$(compatibility_sha)"
  exit 0
fi
if [[ "$ACTION" == package ]]; then
  [[ $# == 3 ]] || fail 'package requires checkpoint and destination'
  gate
  source="$(realpath -e "$2")" || fail 'checkpoint source absent'
  [[ "$source" == "$ROOT/build/product-sd1-c1-checkpoints/"* ]] ||
    fail 'checkpoint must originate from dedicated C1 runner'
  bash tools/ci/product_sd1_c1_checkpoint.sh verify "$source" >/dev/null
  [[ "$(field phase "$source/.c1-ready")" == armed ]] || fail 'only unmounted C1 prefix is reusable'
  [[ "$(field active_sd "$source/.c1-ready")" == "$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img" ]] ||
    fail 'C1 prefix must still use the original sealed medium'
  require_file "$source/save.log"
  [[ "$(grep -Ec '^PRODUCT_SD1_C1_PREFIX_(SAVED|ADVANCED) .*ready=[01] ' "$source/save.log")" == 1 ]] ||
    fail 'real C1 save/advance READY state absent'
  ready="$(sed -nE 's/^PRODUCT_SD1_C1_PREFIX_(SAVED|ADVANCED) .*ready=([01]) .*/\2/p' "$source/save.log")"
  if [[ "$ready" == 1 ]]; then
    grep -Fq 'AETHERCORE_C1_READY rootfs_unmounted=1' "$source/save.log" ||
      fail 'C1 READY claimed without observed unmounted UART marker'
    grep -Fq 'spi_quiescent=1' "$source/save.log" || fail 'C1 READY must be quiescent'
  fi
  [[ -z "$(grep -E 'AETHERCORE_C1_FAIL|Kernel panic - not syncing|AETHERCORE_C1_MOUNT_(BEGIN|OK)|AETHERCORE_C1_NATIVE_EXEC' "$source/save.log" || true)" ]] ||
    fail 'rootfs-dependent guest execution/failure entered C1 prefix'

  model='build/product-sd1-persistent-checkpoint/savable-model/obj/VAetherCoreProductDebianSdRV64SimTop'
  model_key='build/product-sd1-persistent-checkpoint/savable-model/.savable-source-key'
  kernel='build/product-sd1-c1-kernel/obj/arch/riscv/boot/Image'
  firmware_result='build/product-sd1-c1-boot/result.txt'
  firmware="$(in_build "$(field firmware_bin "$firmware_result")")"
  dtb="$(in_build "$(field fdt "$firmware_result")")"
  proof_rel="${source#"$ROOT/"}"
  members=("$model" "$model_key" "$kernel" \
    'build/product-sd1-c1-kernel/result.txt' "$firmware_result" "$firmware" "$dtb" \
    "$proof_rel/.c1-ready" "$proof_rel/persistent.pre.rtl" \
    "$proof_rel/persistent.pre.host" "$proof_rel/save.log")
  for member in "${members[@]}"; do
    require_file "$ROOT/$member"
  done
  dest="$3"
  [[ ! -e "$dest" && ! -L "$dest" ]] || fail 'artifact destination must not exist'
  mkdir -p "$dest"
  dest="$(realpath "$dest")"
  [[ "$dest" != "$ROOT/"* ]] || fail 'artifact directory must live outside repository'
  for member in "${members[@]}"; do
    mkdir -p "$dest/$(dirname "$member")"
    cp -p -- "$ROOT/$member" "$dest/$member"
  done
  (
    cd "$dest"
    sha256sum "${members[@]}" > SHA256SUMS
  )
  {
    echo 'schema=PRODUCT_SD1_C1_PUBLIC_ARTIFACT_V1'
    echo "head_sha=$(git rev-parse HEAD)"
    echo "compatibility_sha256=$(compatibility_sha)"
    echo "seed_sha256=$(sha build/product-sd1-official-seed/seed-manifest.txt)"
    echo "source_proof=$proof_rel"
    echo "cycle=$(field cycle "$source/.c1-ready")"
    echo "ready=$ready"
    echo 'phase=armed'
    echo 'read_ledger=SDHC_V2_IN_HOST_PRESTATE'
    echo 'rootfs_binding=NONE'
    echo 'console_login_credentials=NOT_PROVISIONED'
    echo "members=$((${#members[@]}))"
    echo "sha256sums_sha256=$(sha "$dest/SHA256SUMS")"
  } > "$dest/RELAY-MANIFEST"
  echo "PRODUCT_SD1_C1_PUBLIC_ARTIFACT_PACKAGED cycle=$(field cycle "$source/.c1-ready") ready=$ready files=${#members[@]} full_rtl_ddr_spi=1 no_cold_replay=1"
  exit 0
fi

[[ $# == 2 ]] || fail 'restore requires downloaded artifact directory'
gate
artifact="$(realpath -e "$2")" || fail 'missing artifact'
manifest="$artifact/RELAY-MANIFEST"
require_file "$manifest"
[[ "$(field schema "$manifest")" == PRODUCT_SD1_C1_PUBLIC_ARTIFACT_V1 &&
   "$(field head_sha "$manifest")" =~ ^[a-f0-9]{40}$ &&
   "$(field compatibility_sha256 "$manifest")" == "$(compatibility_sha)" &&
   "$(field seed_sha256 "$manifest")" == "$(sha build/product-sd1-official-seed/seed-manifest.txt)" &&
   "$(field phase "$manifest")" == armed &&
   "$(field rootfs_binding "$manifest")" == NONE &&
   "$(field read_ledger "$manifest")" == SDHC_V2_IN_HOST_PRESTATE &&
   "$(field console_login_credentials "$manifest")" == NOT_PROVISIONED &&
   "$(field members "$manifest")" == 11 ]] ||
  fail 'manifest version/source/media/security contract changed'
  require_file "$artifact/SHA256SUMS"
  [[ "$(field sha256sums_sha256 "$manifest")" == "$(sha "$artifact/SHA256SUMS")" ]] ||
    fail 'member hash list corrupted'
  source_proof="$(field source_proof "$manifest")"
  [[ "$source_proof" =~ ^build/product-sd1-c1-checkpoints/c1-(cold|stage)\.[a-zA-Z0-9]{8}$ ]] ||
    fail 'untrusted proof relative path'
  model='build/product-sd1-persistent-checkpoint/savable-model/obj/VAetherCoreProductDebianSdRV64SimTop'
  model_key='build/product-sd1-persistent-checkpoint/savable-model/.savable-source-key'
  kernel='build/product-sd1-c1-kernel/obj/arch/riscv/boot/Image'
  firmware_result='build/product-sd1-c1-boot/result.txt'
  require_file "$artifact/$firmware_result"
  extract_field="$(field firmware_bin "$artifact/$firmware_result")"
  firmware_rel="${extract_field#"$ROOT/"}"
  [[ "$extract_field" == "$ROOT/build/product-sd1-c1-boot/"* ]] || fail 'firmware destination escaped root'
  extract_field="$(field fdt "$artifact/$firmware_result")"
  dtb_rel="${extract_field#"$ROOT/"}"
  [[ "$extract_field" == "$ROOT/build/product-sd1-c1-boot/"* ]] || fail 'DTB destination escaped root'
  members=("$model" "$model_key" "$kernel" \
    'build/product-sd1-c1-kernel/result.txt' "$firmware_result" "$firmware_rel" "$dtb_rel" \
    "$source_proof/.c1-ready" "$source_proof/persistent.pre.rtl" \
    "$source_proof/persistent.pre.host" "$source_proof/save.log")
  [[ "$(wc -l < "$artifact/SHA256SUMS")" == 11 ]] || fail 'unexpected artifact member count'
  for member in "${members[@]}"; do
    [[ "$member" == build/* && "$member" != *'..'* ]] || fail 'invalid member path'
    require_file "$artifact/$member"
    [[ "$(grep -Fxc "$(sha "$artifact/$member")  $member" "$artifact/SHA256SUMS")" == 1 ]] ||
      fail "member digest mismatch: $member"
    [[ ! -e "$ROOT/$member" && ! -L "$ROOT/$member" ]] || fail "refusing overwrite: $member"
  done
  [[ "$(field cycle "$artifact/$source_proof/.c1-ready")" == "$(field cycle "$manifest")" ]] ||
    fail 'relayed cycle inconsistent'
  for member in "${members[@]}"; do
    mkdir -p "$ROOT/$(dirname "$member")"
    cp -p -- "$artifact/$member" "$ROOT/$member"
  done
  # GitHub download-artifact@v4 intentionally normalizes ALL file modes to
  # 0644. The byte-for-byte verified ELF is SHA-identical but not executable
  # until this explicit post-verification chmod. No other artifact member is
  # granted execute permissions; manifest and snapshot hashes are unchanged.
  chmod 0755 "$ROOT/$model"
  bash tools/ci/product_sd1_c1_checkpoint.sh verify "$ROOT/$source_proof" >/dev/null
  echo "PRODUCT_SD1_C1_PUBLIC_ARTIFACT_RESTORED directory=$ROOT/$source_proof cycle=$(field cycle "$manifest") ready=$(field ready "$manifest") cold_cycles=0 full_rtl_ddr_spi=1"
