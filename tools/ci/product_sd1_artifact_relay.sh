#!/usr/bin/env bash
set -euo pipefail

# Explicit PUBLIC, credential-free official SD1 test-only Actions artifact relay.
# This is deliberately separate from the private SD1 and C1 checkpoint stores.
# Each archive contains a verified full RTL/DDR/SD COW snapshot and the EXACT
# portable x86-64 savable simulator executable that created it. A fresh runner
# must NEVER recompile a substitute executable before restoring the snapshot.
#
# AETHERCORE_SD1_PUBLIC_ARTIFACT_CONFIRM=PUBLIC_CREDENTIAL_FREE_SD1_TEST \
# AETHERCORE_SD1_RELAY_CONFIRM=SD1_FULL_DEBIAN_SIGNED_RELAY \
# AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT=1 \
#   bash tools/ci/product_sd1_artifact_relay.sh bootstrap ARCHIVE_DIR
# ... bash tools/ci/product_sd1_artifact_relay.sh advance SOURCE_DIR DEST_DIR END_CYCLE
# ... bash tools/ci/product_sd1_artifact_relay.sh verify ARCHIVE_DIR
# ... bash tools/ci/product_sd1_artifact_relay.sh fingerprint
#
# A stage is <=20M cycles, not a Debian PASS claim. The workflow groups up to
# five stages/100M per explicitly labeled CI run; only a sealed final stage is
# eligible for public upload. Real users' credentials/data MUST NOT be used.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in bootstrap|advance|verify|fingerprint) ;; *)
  echo 'usage: product_sd1_artifact_relay.sh bootstrap DIR|advance SOURCE DEST NEXT_CYCLE|verify DIR|fingerprint' >&2
  exit 2;;
esac

reject() { echo "PRODUCT_SD1_PUBLIC_ARTIFACT_REJECTED: $*" >&2; exit 3; }
sha() { sha256sum -- "$1" | cut -d' ' -f1; }
field() {
  local value
  [[ -s "$1" ]] || reject "missing manifest $1"
  value="$(sed -n "s/^${2}=//p" "$1")"
  [[ -n "$value" && "$value" != *$'\n'* ]] || reject "missing/duplicate $2 in $1"
  printf '%s' "$value"
}
# An unrelated FPGA mapping report, workflow label, or rootfs *consumer*
# change must not invalidate an already executed Product SD1 prefix. A cached
# full snapshot is reusable only when its savable-model build/restore ABI,
# RISC-V RTL, simulator host/device model, fixed SD image builder, kernel and
# firmware inputs remain byte-identical. The manifest still records the
# original full commit SHA for audit; this stable digest is the compatibility
# authority. In particular, the official frozen media lock/build inputs are
# intentionally included: an SD/rootfs seed change is never a warm resume.
compatibility_sha() {
  [[ -d "$ROOT/src/main/scala" && -d "$ROOT/sim" ]] ||
    reject 'missing RTL/host simulator source directories'
  local -a inputs=(
    build.mill mill
    software/rv64/product_sd1_official_seed.lock
    software/rv64/linux_early.env
    software/rv64/opensbi_first_exec.env
    tools/ensure_riscv64_linux_gcc_13_3.sh
    tools/ensure_verilator_5_024.sh
    tools/ensure_verilator_5_054.sh
    tools/ci/product_sd1_artifact_relay.sh
    tools/ci/product_sd1_checkpoint_relay.sh
    tools/ci/product_checkpoint_sd1_short.sh
    tools/ci/product_sim_sd1_manual.sh
    tools/ci/product_sim_ab.sh
    tools/ci/product_model_identity.sh
    tools/ci/product_sim_sd1_card_probe.cpp
    tools/ci/product_sim_cache.sh
    tools/ci/product_sd1_relay_state.py
    tools/ci/product_sd1_official_seed.py
    tools/ci/rv64_debian_payload_build.sh
    tools/ci/rv64_debian_sd1_sd_image_build.sh
    tools/ci/rv64_debian_sd1_official_full_rootfs_prepare.sh
    tools/ci/rv64_debian_linux_build.sh
    tools/ci/rv64_debian_rootfs_build.sh
    tools/ci/rv64_debian_sd1_tree_digest.py
    tools/ci/rv64_linux_early_build.sh
    tools/ci/rv64_minimal_init_payload_build.sh
  )
  local path
  while IFS= read -r -d '' path; do
    inputs+=("${path#"$ROOT/"}")
  done < <(find "$ROOT/src/main/scala" "$ROOT/sim" -type f \
    \( -name '*.scala' -o -name '*.cpp' -o -name '*.h' \) -print0)
  # The boot/rootfs builders install or compile first-party user payloads;
  # their bytes also belong to the immutable full-media contract.
  while IFS= read -r -d '' path; do
    inputs+=("${path#"$ROOT/"}")
  done < <(find "$ROOT/software/rv64_userspace" -type f -print0)
  (( ${#inputs[@]} > 25 )) || reject 'incomplete SD1 ABI compatibility input set'
  (
    cd "$ROOT"
    printf '%s\n' "${inputs[@]}" | LC_ALL=C sort -u | while IFS= read -r path; do
      [[ -s "$path" && -f "$path" && ! -L "$path" ]] ||
        reject "missing or symlinked SD1 compatibility input: $path"
      sha256sum -- "$path"
    done | sha256sum | cut -d' ' -f1
  )
}
read_numeric() {
  python3 -c 'import json,sys; v=json.loads(sys.argv[1].partition(" ")[2])[sys.argv[2]]; assert type(v) is int and v>=0; print(v)' "$1" "$2"
}
read_flag() {
  python3 -c 'import json,sys; v=json.loads(sys.argv[1].partition(" ")[2])[sys.argv[2]]; assert type(v) is bool; print(int(v))' "$1" "$2"
}
inspect() {
  local line
  line="$(python3 tools/ci/product_sd1_relay_state.py --snapshot "$1" --minimum-commits "${2:--1}")" ||
    reject 'UART/retirement/full host checkpoint inspector failed'
  [[ "$line" == PRODUCT_SD1_RELAY_CHECKPOINT_OK\ \{* ]] || reject 'bad observer output'
  printf '%s' "$line"
}
verify() {
  local dir="$1" manifest state model_sha origin previous
  manifest="$dir/artifact-manifest.txt"
  [[ -d "$dir" && -s "$manifest" && -s "$dir/ARTIFACT_COMPLETE" ]] ||
    reject 'missing published artifact seal'
  [[ "$(field "$manifest" format)" == sd1-public-credential-free-artifact-v1 &&
     "$(field "$manifest" credentials)" == NOT_PROVISIONED &&
     "$(field "$manifest" model_abi)" == linux-x86_64-portable &&
     "$(field "$manifest" qualification)" == FULL_STATE_ONLY_NOT_LINUX_BOOT ]] ||
    reject 'wrong public test-only artifact schema'
  for name in simulator persistent.pre.host persistent.pre.rtl .snapshot-ready \
              relay-manifest.txt RELAY_COMPLETE save.log artifact-manifest.txt; do
    [[ -f "$dir/$name" && ! -L "$dir/$name" ]] || reject "missing or symlink artifact component $name"
  done
  (cd "$dir" && sha256sum --check --status ARTIFACT_COMPLETE) ||
    reject 'artifact component SHA/seal changed in transit'
  bash tools/ci/product_sd1_checkpoint_relay.sh verify "$dir" >/dev/null ||
    reject 'underlying native RTL+DDR stage not sealed'
  model_sha="$(sha "$dir/simulator")"
  [[ "$model_sha" == "$(field "$dir/.snapshot-ready" model_sha256)" &&
     "$model_sha" == "$(field "$manifest" model_sha256)" ]] ||
    reject 'the exact originating simulator binary is not available'
  [[ "$(field "$dir/.snapshot-ready" uart_policy_sha256)" == \
     "$(printf '%s\0' '' '' '0' | sha256sum | cut -d' ' -f1)" ]] ||
    reject 'public test snapshot unexpectedly includes guest UART input policy'
  origin="$(field "$manifest" source_commit)"
  previous="$(field "$dir/relay-manifest.txt" source_commit)"
  [[ "$origin" =~ ^[a-f0-9]{40}$ && "$previous" == "$origin" &&
     "$(field "$manifest" compatibility_sha256)" == "$(compatibility_sha)" ]] ||
    reject 'RTL/host ABI/firmware/media compatibility changed since frozen snapshot'
  state="$(inspect "$dir")"
  [[ "$(field "$manifest" cycles)" == "$(read_numeric "$state" cycles)" &&
     "$(field "$manifest" commits)" == "$(read_numeric "$state" commits)" ]] ||
    reject 'manifest/actual retired state mismatch'
  printf '%s\n' "$state"
}

require_public_seed() {
  [[ "${AETHERCORE_SD1_PUBLIC_ARTIFACT_CONFIRM:-}" == PUBLIC_CREDENTIAL_FREE_SD1_TEST &&
     "${AETHERCORE_SD1_RELAY_CONFIRM:-}" == SD1_FULL_DEBIAN_SIGNED_RELAY &&
     "${AETHERCORE_ALLOW_PRODUCT_SD1_PERSISTENT_CHECKPOINT:-}" == 1 &&
     "${AETHERCORE_VERILATOR_VERSION:-5.054}" == 5.054 &&
     "${SIM_SETTLE_MODE:-reference}" == reference &&
     "${VERILATOR_THREADS:-1}" == 1 &&
     "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-0}" == 0 ]] ||
    reject 'separate explicit public credential-free test permission and pinned simulation profile required'
  [[ -z "${RV64_SD1_LOGIN_USER:-}" && -z "${RV64_SD1_LOGIN_PASSWORD:-}" &&
     -z "${RV64_SD1_LOGIN_PASSWORD_HASH:-}" && -z "${RV64_SD1_LOGIN_PASSWORD_HASH_FILE:-}" &&
     -z "${AETHERCORE_SD1_PERSISTENT_UART_TRIGGER:-}" &&
     -z "${AETHERCORE_SD1_PERSISTENT_UART_COMMAND:-}" &&
     "${AETHERCORE_SD1_PERSISTENT_UART_POST_BUDGET:-0}" == 0 &&
     -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" &&
     -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] ||
    reject 'guest credentials and user-supplied media forbidden in public test artifacts'
  python3 tools/ci/product_sd1_official_seed.py verify >/dev/null ||
    reject 'exact official signed seed cache not verified'
  grep -Fxq 'console_login_credentials=NOT_PROVISIONED' \
    build/product-sd1-official-seed/console-provisioning.txt ||
    reject 'public seed contains guest credentials'
  grep -Fxq 'console_login_user=NONE' \
    build/product-sd1-official-seed/console-provisioning.txt ||
    reject 'public seed has a provisioned login user'
  AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_sd1_manual.sh preflight >/dev/null ||
    reject 'official SD/firmware/DTB/Linux/rootfs hash preflight failed'
}

seal() {
  local dir="$1" old="$2" state="$3" cycles commits
  [[ ! -e "$dir/relay-manifest.txt" && ! -e "$dir/RELAY_COMPLETE" &&
     ! -e "$dir/artifact-manifest.txt" && ! -e "$dir/ARTIFACT_COMPLETE" ]] ||
    reject 'cannot overwrite previously sealed stage'
  cycles="$(read_numeric "$state" cycles)"
  commits="$(read_numeric "$state" commits)"
  [[ "$commits" -gt 0 ]] || reject 'no guest instructions retired'
  {
    printf 'format=sd1-full-rtl-relay-v1\ntop=AetherCoreProductDebianSdRV64SimTop\n'
    printf 'qualification=FULL_STATE_ONLY_NOT_LINUX_BOOT\ncycles=%s\ncommits=%s\n' "$cycles" "$commits"
    printf 'source_cycle=%s\nsnapshot_host_sha256=%s\nsnapshot_rtl_sha256=%s\n' \
      "$old" "$(sha "$dir/persistent.pre.host")" "$(sha "$dir/persistent.pre.rtl")"
    printf 'source_commit=%s\n' "$(git rev-parse HEAD)"
    printf 'compatibility_sha256=%s\n' "$(compatibility_sha)"
  } > "$dir/relay-manifest.txt.tmp"
  mv -- "$dir/relay-manifest.txt.tmp" "$dir/relay-manifest.txt"
  (cd "$dir" && sha256sum -- persistent.pre.host persistent.pre.rtl .snapshot-ready relay-manifest.txt > RELAY_COMPLETE.tmp)
  mv -- "$dir/RELAY_COMPLETE.tmp" "$dir/RELAY_COMPLETE"
}

seal_artifact() {
  local dir="$1" state="$2"
  [[ ! -e "$dir/artifact-manifest.txt" && ! -e "$dir/ARTIFACT_COMPLETE" ]] ||
    reject 'artifact already sealed'
  {
    printf 'format=sd1-public-credential-free-artifact-v1\n'
    printf 'credentials=NOT_PROVISIONED\nmodel_abi=linux-x86_64-portable\n'
    printf 'qualification=FULL_STATE_ONLY_NOT_LINUX_BOOT\n'
    printf 'source_commit=%s\n' "$(git rev-parse HEAD)"
    printf 'compatibility_sha256=%s\n' "$(compatibility_sha)"
    printf 'cycles=%s\ncommits=%s\n' "$(read_numeric "$state" cycles)" "$(read_numeric "$state" commits)"
    printf 'model_sha256=%s\n' "$(sha "$dir/simulator")"
    printf 'official_sd_sha256=%s\n' "$(field build/rv64-debian-sd1-sd/result.txt sd_image_sha256)"
  } > "$dir/artifact-manifest.txt.tmp"
  mv -- "$dir/artifact-manifest.txt.tmp" "$dir/artifact-manifest.txt"
  (cd "$dir" && sha256sum -- simulator persistent.pre.host persistent.pre.rtl \
      .snapshot-ready relay-manifest.txt RELAY_COMPLETE save.log artifact-manifest.txt > ARTIFACT_COMPLETE.tmp)
  mv -- "$dir/ARTIFACT_COMPLETE.tmp" "$dir/ARTIFACT_COMPLETE"
  verify "$dir" >/dev/null
}

if [[ "$ACTION" == fingerprint ]]; then
  [[ $# == 1 ]] || reject 'fingerprint takes no checkpoint directory'
  fingerprint="$(compatibility_sha)" || reject 'cannot read full RTL/host/seed compatibility inputs'
  [[ "$fingerprint" =~ ^[a-f0-9]{64}$ ]] || reject 'invalid compatibility fingerprint'
  printf 'PRODUCT_SD1_PUBLIC_ARTIFACT_COMPATIBILITY_SHA256=%s\n' "$fingerprint"
  exit
fi

if [[ "$ACTION" == verify ]]; then
  [[ $# == 2 ]] || reject 'verify requires exactly one source stage'
  verify "$(realpath -e -- "$2")"
  exit
fi

require_public_seed

if [[ "$ACTION" == bootstrap ]]; then
  [[ $# == 2 ]] || reject 'bootstrap requires archive destination'
  dest="$2"
  [[ ! -e "$dest" ]] || reject 'destination already exists'
  output="$(bash tools/ci/product_sd1_checkpoint_relay.sh bootstrap)" ||
    reject 'official SD1 bounded cold bootstrap failed'
  source_dir="$(sed -n 's/^PRODUCT_SD1_RELAY_BOOTSTRAP_READY directory=\([^ ]*\) .*/\1/p' <<< "$output" | tail -1)"
  [[ -n "$source_dir" && -d "$source_dir" ]] || reject 'bootstrap did not produce a sealed checkpoint'
  bash tools/ci/product_sd1_checkpoint_relay.sh verify "$source_dir" >/dev/null
  mkdir -p -- "$dest"
  cp -- "$source_dir/persistent.pre.host" "$source_dir/persistent.pre.rtl" \
    "$source_dir/.snapshot-ready" "$source_dir/relay-manifest.txt" \
    "$source_dir/RELAY_COMPLETE" "$source_dir/save.log" "$dest/"
  cp -- build/product-sd1-persistent-checkpoint/savable-model/obj/VAetherCoreProductDebianSdRV64SimTop "$dest/simulator"
  state="$(inspect "$dest")"
  seal_artifact "$dest" "$state"
  echo "PRODUCT_SD1_PUBLIC_ARTIFACT_STAGE_READY cycles=$(read_numeric "$state" cycles) commits=$(read_numeric "$state" commits) directory=$dest"
  exit
fi

[[ $# == 4 ]] || reject 'advance requires source stage, new directory and target absolute cycle'
source_dir="$(realpath -e -- "$2")"
dest="$3"
next="$4"
[[ "$next" =~ ^[1-9][0-9]*$ ]] || reject 'target must be decimal'
[[ ! -e "$dest" ]] || reject 'destination exists; snapshots are immutable'
state="$(verify "$source_dir" | tail -1)"
old="$(read_numeric "$state" cycles)"
commits="$(read_numeric "$state" commits)"
(( next > old && next - old <= 20000000 && next <= 4000000000 )) ||
  reject 'advance limited to 1..20M cycles and hard 4B ceiling'

# Exact immutable media bytes, not a substitute SD/rootfs. The frozen guest
# snapshot itself validates these six SHA identities again before resuming.
fw_manifest=build/rv64-debian-boot/result.txt
sd_manifest=build/rv64-debian-sd1-sd/result.txt
fw="$(field "$fw_manifest" firmware_bin)"
dtb="$(field "$fw_manifest" fdt)"
sd="$(field "$sd_manifest" SD_IMAGE)"
model_sha="$(field "$source_dir/artifact-manifest.txt" model_sha256)"
fw_sha="$(field "$fw_manifest" firmware_bin_sha256)"
dtb_sha="$(field "$fw_manifest" fdt_sha256)"
sd_sha="$(field "$sd_manifest" sd_image_sha256)"
linux_sha="$(field "$fw_manifest" linux_image_sha256)"
rootfs_sha="$(field "$fw_manifest" rootfs_result_sha256)"
[[ "$sd_sha" == "$(field "$source_dir/artifact-manifest.txt" official_sd_sha256)" ]] ||
  reject 'official seed changed across Actions runs'
for key in model firmware dtb sd_image linux_image rootfs_result; do
  case "$key" in
    model) expected="$model_sha";; firmware) expected="$fw_sha";; dtb) expected="$dtb_sha";;
    sd_image) expected="$sd_sha";; linux_image) expected="$linux_sha";; rootfs_result) expected="$rootfs_sha";;
  esac
  [[ "$(field "$source_dir/.snapshot-ready" "${key}_sha256")" == "$expected" ]] ||
    reject "actual $key source identity changed"
done
for f in "$fw" "$dtb" "$sd"; do [[ -s "$f" ]] || reject "missing official media $f"; done
[[ "$(sha "$fw")" == "$fw_sha" && "$(sha "$dtb")" == "$dtb_sha" && "$(sha "$sd")" == "$sd_sha" ]] ||
  reject 'media bytes do not match qualified official SHA'
mkdir -p -- "$dest"
cp -- "$source_dir/simulator" "$dest/simulator"
chmod 0755 -- "$dest/simulator" # download-artifact normalizes file permissions to 0644
[[ "$(sha "$dest/simulator")" == "$model_sha" ]] || reject 'original simulator binary SHA mismatch'
timeout --signal=TERM --kill-after=10s 1800s \
  "$dest/simulator" advance "$fw" "$dtb" "$sd" "$source_dir/persistent" \
  "$model_sha" "$fw_sha" "$dtb_sha" "$sd_sha" "$linux_sha" "$rootfs_sha" \
  "$old" "$((next-old))" "$dest/persistent" > "$dest/save.log" 2>&1 || {
    tail -60 "$dest/save.log" >&2
    reject 'warm SD1 RTL/DDR/SD stage failed; source artifact remains intact'
  }
[[ -s "$dest/persistent.pre.host" && -s "$dest/persistent.pre.rtl" ]] ||
  reject 'simulator did not persist a complete next checkpoint'
sed -e "s/^boot_cycles=.*/boot_cycles=$next/" \
    -e "s/^snapshot_rtl_sha256=.*/snapshot_rtl_sha256=$(sha "$dest/persistent.pre.rtl")/" \
    -e "s/^snapshot_host_sha256=.*/snapshot_host_sha256=$(sha "$dest/persistent.pre.host")/" \
    "$source_dir/.snapshot-ready" > "$dest/.snapshot-ready.tmp"
mv -- "$dest/.snapshot-ready.tmp" "$dest/.snapshot-ready"
state="$(inspect "$dest" "$commits")"
[[ "$(read_numeric "$state" cycles)" == "$next" ]] || reject 'RTL cycle target not reached'
seal "$dest" "$old" "$state"
seal_artifact "$dest" "$state"
echo "PRODUCT_SD1_PUBLIC_ARTIFACT_STAGE_READY cycles=$next commits=$(read_numeric "$state" commits) directory=$dest"
if [[ "$(read_flag "$state" login)" == 1 &&
      "$(read_flag "$state" ext4_root_mounted)" == 1 &&
      "$(read_flag "$state" native_init_started)" == 1 ]]; then
  echo "PRODUCT_SD1_PUBLIC_ARTIFACT_NATIVE_GETTY_PROMPT_PASS cycles=$next source=$dest"
fi
