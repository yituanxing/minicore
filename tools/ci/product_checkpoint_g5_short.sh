#!/usr/bin/env bash
set -euo pipefail

# Bounded RAM-initramfs Debian D1/D2 Product RTL checkpoint/replay. This is the
# real G5 Debian CPU top, NOT the G4 OpenSBI-only top and NOT SD1 external media.
# The same G4/G5 host implementation is compiled against a separately generated
# --savable G5 model. A successful short prefix never qualifies Debian PID1.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in build|run|diagnose|verify-proof) ;; *) echo 'usage: product_checkpoint_g5_short.sh build|run|diagnose|verify-proof [PROOF_DIR]' >&2; exit 2 ;; esac
reject() { echo "PRODUCT_G5_PERSISTENT_REJECTED: $*" >&2; exit 3; }

verify_proof() {
  local dir="${1:?missing proof directory}" state
  [[ -s "$dir/.complete" && -s "$dir/sha256.txt" &&
     -s "$dir/save.log" && -s "$dir/restore.log" ]] || return 10
  for state in persistent.pre.rtl persistent.pre.host \
      persistent.continuous.rtl persistent.continuous.host \
      persistent.restored.rtl persistent.restored.host; do
    [[ -s "$dir/$state" ]] || return 10
  done
  for field in 'complete=1' 'top=AetherCoreProductDebianRV64SimTop' \
      'milestone=Linux version 6.6.143' 'boot_max=100000000' \
      'checkpoint_at=100000000' 'replay_cycles=500000' 'external_sd=0' \
      'pid1_qualified=0'; do
    grep -Fxq "$field" "$dir/.complete" || return 10
  done
  for key in model_sha256 firmware_sha256 linux_image_sha256 rootfs_manifest_sha256; do
    grep -Eq "^${key}=[0-9a-f]{64}$" "$dir/.complete" || return 10
  done
  [[ "$(stat -c%s "$dir/persistent.pre.host")" -gt 268435456 &&
     "$(stat -c%s "$dir/persistent.continuous.host")" -gt 268435456 &&
     "$(stat -c%s "$dir/persistent.restored.host")" -gt 268435456 &&
     "$(head -c8 "$dir/persistent.pre.host")" == AETG5CP1 ]] || return 10
  (cd "$dir" && sha256sum --check --status sha256.txt) || return 10
  [[ "$(grep -c '^PRODUCT_G5_PERSISTENT_CHECKPOINT_SAVED ' "$dir/save.log")" == 1 &&
     "$(grep -c '^PRODUCT_G5_PERSISTENT_CHECKPOINT_RESTORED ' "$dir/restore.log")" == 1 ]] || return 10
  grep '^PRODUCT_G5_PERSISTENT_CONTINUATION ' "$dir/save.log" > "$dir/continuous.state"
  grep '^PRODUCT_G5_PERSISTENT_CONTINUATION ' "$dir/restore.log" > "$dir/restored.state"
  grep -Eq ' linux_banner=1 opensbi_banner=1 ' "$dir/continuous.state" &&
    cmp -s "$dir/continuous.state" "$dir/restored.state" &&
    cmp -s "$dir/persistent.continuous.rtl" "$dir/persistent.restored.rtl" &&
    cmp -s "$dir/persistent.continuous.host" "$dir/persistent.restored.host" || return 10
  echo 'PRODUCT_G5_PERSISTENT_CROSS_PROCESS_PASS debian_product_top=1 real_linux_banner=1 replay_cycles=500000 rtl_bytes=IDENTICAL host_ddr_bytes=IDENTICAL uart_irq_trace=IDENTICAL pid1_qualified=0 external_sd=0'
}

if [[ "$ACTION" == verify-proof ]]; then
  [[ $# == 2 ]] || reject 'verify-proof needs a directory'
  verify_proof "$2" || reject 'G5 persisted RTL/host proof diverged or is incomplete'
  exit 0
fi

[[ "${AETHERCORE_ALLOW_PRODUCT_G5_PERSISTENT_CHECKPOINT:-0}" == 1 ]] || reject 'explicit bounded G5 opt-in required'
if [[ "$ACTION" == diagnose ]]; then
  [[ "${AETHERCORE_ALLOW_PRODUCT_G5_LONG_DIAGNOSE:-0}" == 1 ]] || \
    reject 'long D2 diagnosis additionally requires explicit AETHERCORE_ALLOW_PRODUCT_G5_LONG_DIAGNOSE=1'
fi
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" && -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || reject 'external SD1 media and independent DTB belong to a different top'
[[ "${VERILATOR_THREADS:-1}" == 1 && "${SIM_SETTLE_MODE:-reference}" == reference &&
   "${AETHERCORE_VERILATOR_VERSION:-5.054}" == 5.054 &&
   "${AETHERCORE_DATA_MEM_WAIT_CYCLES:-0}" == 0 ]] || reject 'only pinned 5.054/reference/one thread/zero-wait short replay is supported'

TOP=AetherCoreProductDebianRV64SimTop
ELABORATE=aethercore.sim.ElaborateProductDebianRV64SimTop
OUT="${PRODUCT_G5_PERSISTENT_OUT:-$ROOT/build/product-g5-persistent-checkpoint}"
MODEL="$OUT/savable-model"
OBJ="$MODEL/obj"
ARCHIVE="$OBJ/V${TOP}__ALL.a"
SIM="$OUT/product-g5-checkpoint"
SOURCE="$ROOT/sim/product_rv64_checkpoint_g4_main.cpp"
HOST_OBJ="$OUT/product-g5-checkpoint.o"
FLAGS="-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_CHECKPOINT_G5 -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE -I$ROOT/sim/product_rv64_opensbi_shim -I$ROOT/sim"
MODEL_ARGS=("$MODEL" "$TOP" "$ELABORATE" "$FLAGS")
TOOLCHAIN="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
VERILATOR_BIN="$TOOLCHAIN/verilator-5.054/bin/verilator"
VERILATOR_INCLUDE="$TOOLCHAIN/verilator-5.054/include"
FW_RESULT="$ROOT/build/rv64-debian-boot/result.txt"
FW_SHA_LIST="$ROOT/build/rv64-debian-boot/evidence/sha256.txt"
LINUX_RESULT="$ROOT/build/rv64-debian-linux/result.txt"
ROOTFS_RESULT="$ROOT/build/rv64-debian-rootfs/result.txt"

[[ -s "$FW_RESULT" && -s "$FW_SHA_LIST" && -s "$LINUX_RESULT" && -s "$ROOTFS_RESULT" ]] || reject 'D1/D2 Debian Linux, rootfs, firmware and hashes must be built first'
grep -Fxq 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' "$FW_RESULT" || reject 'Debian firmware is not qualified'
grep -Fxq 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "$LINUX_RESULT" || reject 'Debian Linux Image is not qualified'
grep -Fxq 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS' "$ROOTFS_RESULT" || reject 'Debian rootfs is not qualified'
grep -Fxq 'spi_sd=0' "$FW_RESULT" || reject 'this G5 checkpoint supports only RAM-initramfs D1/D2'
STAGE="$(sed -n 's/^stage=//p' "$ROOTFS_RESULT" | head -1)"
[[ "$STAGE" == d1 || "$STAGE" == d2 ]] || reject 'only Debian D1/D2 native initramfs is allowed'
grep -Fxq "stage=$STAGE" "$LINUX_RESULT" || reject 'Debian Linux/rootfs stage mismatch'
sha256sum --check --status "$FW_SHA_LIST" || reject 'Debian firmware payload bytes drifted'
FW_BIN="$(sed -n 's/^firmware_bin=//p' "$FW_RESULT" | head -1)"
LINUX_IMAGE="$(sed -n 's/^linux_image=//p' "$FW_RESULT" | head -1)"
[[ -s "$FW_BIN" && -s "$LINUX_IMAGE" ]] || reject 'Debian firmware or Linux Image missing'
FW_SHA="$(sha256sum "$FW_BIN" | awk '{print $1}')"
LINUX_SHA="$(sha256sum "$LINUX_IMAGE" | awk '{print $1}')"
ROOTFS_SHA="$(sha256sum "$ROOTFS_RESULT" | awk '{print $1}')"
grep -Fxq "linux_image_sha256=$LINUX_SHA" "$FW_RESULT" || reject 'Debian Linux Image differs from firmware input'
grep -Fxq "rootfs_result_sha256=$ROOTFS_SHA" "$LINUX_RESULT" || reject 'Debian Linux Image was built for another rootfs manifest'
(( "$(stat -c%s "$FW_BIN")" <= 268435456 )) || reject 'Debian firmware cannot fit the 256MiB Product RAM'

source_key() {
  {
    AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
      bash tools/ci/product_sim_cache.sh fingerprint "${MODEL_ARGS[@]}"
    # The G5 save/restore/resume host includes this diagnostic header. Bind
    # its bytes as well as the C++ translation unit or a stale savable host
    # could accept a new watchdog policy with an old checkpoint ABI.
    sha256sum "$SOURCE" sim/l32_g5_silence_watchdog.h \
      tools/ci/product_checkpoint_g5_short.sh
    printf 'model-option=--savable\nmodel-source=%s\n' "$TOP"
  } | sha256sum | awk '{print $1}'
}
verify_model() {
  [[ -s "$ARCHIVE" && -s "$OBJ/verilated.o" && -s "$OBJ/verilated_save.o" &&
     -s "$MODEL/.savable-source-key" && -x "$OBJ/V$TOP" ]] || return 1
  [[ "$(< "$MODEL/.savable-source-key")" == "$(source_key)" ]] || return 1
  AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_cache.sh verify "${MODEL_ARGS[@]}" >/dev/null
}
host_key() {
  { source_key; sha256sum "$ARCHIVE" "$SOURCE"; } | sha256sum | awk '{print $1}'
}

if [[ "$ACTION" == build ]]; then
  mkdir -p "$OUT/logs"
  AETHERCORE_VERILATOR_VERSION=5.054 AETHERCORE_VERILATOR_FAST_VERIFY=1 bash tools/ensure_verilator_5_024.sh
  [[ -x "$VERILATOR_BIN" ]] || reject 'pinned Verilator 5.054 unavailable'
  if ! verify_model; then
    mkdir -p "$OUT/rtl-seed" "$OBJ"
    make -f Makefile.l32-linux-boot BUILD_DIR="$OUT/rtl-seed" \
      TOP="$TOP" ELABORATE_MAIN="$ELABORATE" rtl > "$OUT/logs/rtl-build.log" 2>&1
    [[ -n "$(find "$OUT/rtl-seed/rtl" -maxdepth 1 -name '*.sv' -print -quit)" ]] || reject 'Debian Product RTL missing'
    PATH="$TOOLCHAIN/verilator-5.054/bin:$PATH" "$VERILATOR_BIN" \
      -LDFLAGS -ldl -O3 -MAKEFLAGS 'OPT_FAST=-O3 OPT_GLOBAL=-O3' \
      --savable --cc --exe --build -Wall -Wno-fatal --top-module "$TOP" \
      -Mdir "$OBJ" -CFLAGS "$FLAGS" \
      "$OUT"/rtl-seed/rtl/*.sv "$ROOT/sim/opensbi_boot_main.cpp" \
      -j "${JOBS:-4}" > "$OUT/logs/verilator-savable-build.log" 2>&1 || {
        tail -80 "$OUT/logs/verilator-savable-build.log" >&2; exit 4;
      }
    AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
      bash tools/ci/product_sim_cache.sh record "${MODEL_ARGS[@]}"
    source_key > "$MODEL/.savable-source-key"
  fi
  verify_model || reject 'G5 savable RTL archive does not match exact source/ABI'
  [[ -s "$VERILATOR_INCLUDE/verilated_save.h" && -s "$OBJ/V${TOP}.h" ]] || reject 'G5 savable model headers missing'
  key="$(host_key)"
  if [[ -x "$SIM" && -s "$OUT/.host-source-key" && "$(< "$OUT/.host-source-key")" == "$key" &&
        -s "$OUT/.host-binary-sha" && "$(< "$OUT/.host-binary-sha")" == "$(sha256sum "$SIM" | awk '{print $1}')" ]]; then
    echo "PRODUCT_G5_PERSISTENT_MODEL_REUSED source_key=$key"
    exit 0
  fi
  "${CXX:-g++}" -std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_DEBIAN \
    -DAETHERCORE_PRODUCT_CHECKPOINT_G5 -DAETHERCORE_PRODUCT_CHECKPOINT_SAVABLE \
    -I"$OBJ" -I"$ROOT/sim" -I"$VERILATOR_INCLUDE" -I"$VERILATOR_INCLUDE/vltstd" \
    -c "$SOURCE" -o "$HOST_OBJ" > "$OUT/logs/host-compile.log" 2>&1 || {
      tail -80 "$OUT/logs/host-compile.log" >&2; exit 4;
    }
  runtime_objs=("$OBJ/verilated.o" "$OBJ/verilated_save.o")
  for extra in verilated_threads.o verilated_timing.o verilated_dpi.o; do
    [[ ! -s "$OBJ/$extra" ]] || runtime_objs+=("$OBJ/$extra")
  done
  "${CXX:-g++}" -O3 "$HOST_OBJ" "$ARCHIVE" "${runtime_objs[@]}" \
    -pthread -ldl -latomic -o "$SIM" > "$OUT/logs/host-link.log" 2>&1 || {
      tail -80 "$OUT/logs/host-link.log" >&2; exit 4;
    }
  [[ "$key" == "$(host_key)" ]] || reject 'Debian host/RTL source mutated during link'
  printf '%s\n' "$key" > "$OUT/.host-source-key"
  sha256sum "$SIM" | awk '{print $1}' > "$OUT/.host-binary-sha"
  echo "PRODUCT_G5_PERSISTENT_MODEL_BUILT source_key=$key independent_debian_top=1"
  exit 0
fi

if [[ "$ACTION" == diagnose ]]; then
  [[ "$STAGE" == d2 ]] || reject 'long diagnosis is D2 native Debian only'
  grep -Fq 'initcall_debug loglevel=8' "$FW_RESULT" || \
    reject 'long D2 diagnosis requires initcall_debug loglevel=8 firmware bootargs'
  # This action is deliberately not a performance A/B. Reuse or build one
  # G5 --savable host, then pay for the cold Product RTL prefix exactly once.
  if ! verify_model || [[ ! -x "$SIM" ]]; then
    bash "$0" build
  fi
  verify_model || reject 'unqualified savable Product G5 model'
  [[ -x "$SIM" && -s "$OUT/.host-binary-sha" &&
     -s "$OUT/.host-source-key" &&
     "$(< "$OUT/.host-binary-sha")" == "$(sha256sum "$SIM" | awk '{print $1}')" &&
     "$(< "$OUT/.host-source-key")" == "$(host_key)" ]] ||
    reject 'long diagnosis host or savable model identity drifted'

  checkpoint="${AETHERCORE_G5_LONG_CHECKPOINT_CYCLES:-900000000}"
  total="${AETHERCORE_G5_LONG_TOTAL_CYCLES:-1800000000}"
  [[ "$checkpoint" =~ ^[0-9]+$ && "$total" =~ ^[0-9]+$ ]] ||
    reject 'diagnosis cycle bounds must be decimal'
  (( checkpoint >= 100000000 && checkpoint < total && total <= 1800000000 &&
     total - checkpoint >= 1000000 )) ||
    reject 'diagnosis cycle bounds are outside Product G5 1.8B cap'
  input_suffix="${AETHERCORE_G5_DIAGNOSTIC_INPUT_SUFFIX:-pre}"
  output_suffix="${AETHERCORE_G5_DIAGNOSTIC_OUTPUT_SUFFIX:-diagnostic}"
  [[ "$input_suffix" =~ ^[[:alnum:]-]{1,32}$ &&
     "$output_suffix" =~ ^[[:alnum:]-]{1,32}$ &&
     "$input_suffix" != "$output_suffix" && "$output_suffix" != pre &&
     "$output_suffix" != late ]] ||
    reject 'diagnostic checkpoint suffix invalid, unsafe, or collides with original'
  vmlinux="$(sed -n 's/^vmlinux=//p' "$LINUX_RESULT" | head -1)"
  firmware_elf="$(sed -n 's/^firmware=//p' "$FW_RESULT" | head -1)"
  [[ -s "$vmlinux" && -s "$firmware_elf" ]] ||
    reject 'missing Linux/OpenSBI ELF for PC-to-function diagnosis'
  vmlinux_sha="$(sha256sum "$vmlinux" | awk '{print $1}')"
  firmware_elf_sha="$(sha256sum "$firmware_elf" | awk '{print $1}')"
  proof="${PRODUCT_G5_LONG_PROOF_DIR:-}"
  if [[ -z "$proof" ]]; then
    mkdir -p "$OUT"
    proof="$(mktemp -d "$OUT/diagnose-G5.XXXXXXXX")"
  fi
  [[ -d "$proof" && ! -L "$proof" ]] || reject 'diagnosis proof directory is not a real directory'
  prefix="$proof/persistent"
  for artifact in "$prefix.${output_suffix}.rtl" "$prefix.${output_suffix}.host" \
      "$proof/${output_suffix}.sha256" "$proof/${output_suffix}.log"; do
    [[ ! -e "$artifact" && ! -L "$artifact" ]] ||
      reject "diagnosis will not overwrite existing output: $artifact"
  done
  model_sha="$(sha256sum "$SIM" | awk '{print $1}')"
  identity="$proof/identity.txt"
  if [[ ! -e "$identity" ]]; then
    [[ ! -e "$prefix.pre.rtl" && ! -e "$prefix.pre.host" ]] ||
      reject 'unverified existing cold checkpoint; refusing overwrite'
    printf 'model_sha256=%s\nfirmware_sha256=%s\nlinux_image_sha256=%s\nrootfs_result_sha256=%s\ncheckpoint_cycles=%s\ntop=%s\nvmlinux_sha256=%s\nfirmware_elf_sha256=%s\n' \
      "$model_sha" "$FW_SHA" "$LINUX_SHA" "$ROOTFS_SHA" "$checkpoint" "$TOP" \
      "$vmlinux_sha" "$firmware_elf_sha" > "$identity"
    readelf --wide --symbols "$vmlinux" > "$proof/vmlinux-symbols.txt" ||
      reject 'cannot capture unstripped Linux kernel ELF symbols'
    readelf --wide --symbols "$firmware_elf" > "$proof/opensbi-symbols.txt" ||
      reject 'cannot capture OpenSBI ELF symbols'
    echo "PRODUCT_G5_LONG_COLD_PREFIX_STARTED checkpoint=$checkpoint proof=$proof"
    AETHERCORE_PRODUCT_G5_CHECKPOINT_AT_CYCLES="$checkpoint" \
      timeout --signal=TERM --kill-after=10s 21600s \
      "$SIM" save "$FW_BIN" "$prefix" "$model_sha" "$FW_SHA" "$checkpoint" \
        'Linux version 6.6.143' 500000 > "$proof/save.log" 2>&1 || {
          tail -70 "$proof/save.log" >&2
          reject 'G5 long cold prefix failed; no diagnostic milestone claim'
        }
    # Rehydrate the exact late snapshot in a fresh process for a *500k-cycle*
    # byte-for-byte proof before spending another lengthy guest continuation.
    # It reuses the save's already-computed 500k baseline, not a second cold run.
    timeout --signal=TERM --kill-after=10s 120s \
      "$SIM" restore "$FW_BIN" "$prefix" "$model_sha" "$FW_SHA" "$checkpoint" \
        'Linux version 6.6.143' 500000 > "$proof/restore.log" 2>&1 || {
          tail -60 "$proof/restore.log" >&2
          reject 'G5 late RTL/host snapshot failed bounded fresh-process restore'
        }
    grep '^PRODUCT_G5_PERSISTENT_CONTINUATION ' "$proof/save.log" > "$proof/continuous.state"
    grep '^PRODUCT_G5_PERSISTENT_CONTINUATION ' "$proof/restore.log" > "$proof/restored.state"
    cmp -s "$proof/continuous.state" "$proof/restored.state" &&
      cmp -s "$prefix.continuous.rtl" "$prefix.restored.rtl" &&
      cmp -s "$prefix.continuous.host" "$prefix.restored.host" ||
      reject 'G5 late snapshot first 500k cycles diverged from uninterrupted RTL/host'
    (cd "$proof" && sha256sum persistent.pre.rtl persistent.pre.host > pre.sha256)
    echo 'PRODUCT_G5_LONG_RESTORE_PROOF_PASS replay_cycles=500000 rtl=IDENTICAL host=IDENTICAL'
  fi
  for field in "model_sha256=$model_sha" "firmware_sha256=$FW_SHA" \
      "linux_image_sha256=$LINUX_SHA" "rootfs_result_sha256=$ROOTFS_SHA" \
      "checkpoint_cycles=$checkpoint" "top=$TOP" \
      "vmlinux_sha256=$vmlinux_sha" "firmware_elf_sha256=$firmware_elf_sha"; do
    grep -Fxq "$field" "$identity" || reject "saved D2 prefix identity differs: $field"
  done
  [[ -s "$prefix.pre.rtl" && -s "$prefix.pre.host" && -s "$proof/pre.sha256" ]] ||
    reject 'long D2 prefix is incomplete'
  (cd "$proof" && sha256sum --check --status pre.sha256) || \
    reject 'stored D2 prefix RTL/host digest mismatch'
  echo "PRODUCT_G5_LONG_PREFIX_VERIFIED sha256=PASS checkpoint=$checkpoint proof=$proof"

  start_cycle="$checkpoint"
  if [[ "$input_suffix" != pre ]]; then
    [[ -s "$proof/${input_suffix}.sha256" ]] || reject 'prior diagnostic checkpoint lacks manifest'
    (cd "$proof" && sha256sum --check --status "${input_suffix}.sha256") || \
      reject 'prior diagnostic RTL/host digest mismatch'
    start_cycle="$(sed -n "s/^checkpoint_cycle=${input_suffix}://p" "$identity" | head -1)"
    [[ "$start_cycle" =~ ^[0-9]+$ ]] ||
      reject 'prior diagnostic checkpoint has no verified cycle bound'
  fi
  replay="${AETHERCORE_G5_LONG_REPLAY_CYCLES:-$((total - start_cycle))}"
  [[ "$replay" =~ ^[0-9]+$ ]] || reject 'diagnosis replay cycles must be decimal'
  (( start_cycle < total && replay >= 1000000 && replay <= total - start_cycle )) ||
    reject 'requested replay exceeds remaining Product G5 cycle envelope'
  if [[ -e "$prefix.late.rtl" || -e "$prefix.late.host" ]]; then
    [[ -s "$proof/late.sha256" ]] || reject 'existing late snapshot lacks digest manifest'
    (cd "$proof" && sha256sum --check --status late.sha256) ||
      reject 'existing late RTL/host digest mismatch'
  fi
  rc=0
  AETHERCORE_G5_DIAGNOSTIC_MILESTONE=DEBIAN_RISCV64_D2_PROCESSES_FS_SIGNALS_OK \
    AETHERCORE_G5_STALL_WATCHDOG_CYCLES="${AETHERCORE_G5_STALL_WATCHDOG_CYCLES:-100000000}" \
    AETHERCORE_G5_DIAGNOSTIC_INPUT_SUFFIX="$input_suffix" \
    AETHERCORE_G5_DIAGNOSTIC_OUTPUT_SUFFIX="$output_suffix" \
    timeout --signal=TERM --kill-after=10s 21600s \
    "$SIM" resume-diagnose "$FW_BIN" "$prefix" "$model_sha" "$FW_SHA" "$total" \
      'Linux version 6.6.143' "$replay" > "$proof/${output_suffix}.log" 2>&1 || rc=$?
  if [[ -s "$prefix.late.rtl" && -s "$prefix.late.host" &&
        ! -e "$proof/late.sha256" ]]; then
    [[ "$(stat -c%s "$prefix.late.host")" -gt 268435456 &&
       "$(head -c8 "$prefix.late.host")" == AETG5CP1 ]] ||
      reject 'late initcall host snapshot is incomplete'
    late_cycle="$(sed -n -E 's/^PRODUCT_G5_LATE_INITCALL_CHECKPOINT_SAVED cycles=([0-9]+).*/\1/p' "$proof/${output_suffix}.log" | tail -1)"
    [[ "$late_cycle" =~ ^[0-9]+$ ]] ||
      reject 'late initcall snapshot has no confirmed cycle boundary'
    (cd "$proof" && sha256sum persistent.late.rtl persistent.late.host > late.sha256)
    printf 'checkpoint_cycle=late:%s\n' "$late_cycle" >> "$identity"
  fi
  [[ "$rc" == 0 || "$rc" == 2 || "$rc" == 14 || "$rc" == 15 ]] || {
    tail -80 "$proof/${output_suffix}.log" >&2
    reject "D2 diagnosis failed before a classified snapshot: rc=$rc"
  }
  [[ -s "$prefix.${output_suffix}.rtl" && -s "$prefix.${output_suffix}.host" ]] ||
    reject 'classified D2 diagnosis failed to retain full checkpoint'
  (cd "$proof" && sha256sum "persistent.${output_suffix}.rtl" \
    "persistent.${output_suffix}.host" > "${output_suffix}.sha256")
  diagnosed_cycles="$(sed -n -E 's/^PRODUCT_G5_RESUME_DIAGNOSTIC_RESULT status=[0-9]+ cycles=([0-9]+).*/\1/p' "$proof/${output_suffix}.log" | tail -1)"
  [[ "$diagnosed_cycles" =~ ^[0-9]+$ ]] ||
    reject 'classified diagnosis has no final snapshot cycle'
  printf 'checkpoint_cycle=%s:%s\n' "$output_suffix" "$diagnosed_cycles" >> "$identity"
  grep -E '^PRODUCT_G5_RESUME_DIAGNOSTIC_RESULT ' "$proof/${output_suffix}.log" ||
    reject 'D2 diagnosis did not emit classified PC/IRQ result'
  echo "PRODUCT_G5_LONG_DIAGNOSIS_PROOF_DIR=$proof result_rc=$rc"
  exit "$rc"
fi

verify_model || reject 'run build first to produce exact G5 savable model'
[[ -x "$SIM" && -s "$OUT/.host-source-key" && -s "$OUT/.host-binary-sha" &&
   "$(< "$OUT/.host-source-key")" == "$(host_key)" &&
   "$(< "$OUT/.host-binary-sha")" == "$(sha256sum "$SIM" | awk '{print $1}')" ]] || reject 'G5 checkpoint host missing/stale'
MODEL_SHA="$(sha256sum "$SIM" | awk '{print $1}')"
PROOF="$(mktemp -d "$OUT/persist-G5.XXXXXXXX")"
PREFIX="$PROOF/persistent"
AETHERCORE_PRODUCT_G5_CHECKPOINT_AT_CYCLES=100000000 \
timeout --signal=TERM --kill-after=10s 900s \
  "$SIM" save "$FW_BIN" "$PREFIX" "$MODEL_SHA" "$FW_SHA" 100000000 \
    'Linux version 6.6.143' 500000 > "$PROOF/save.log" 2>&1 || {
      tail -70 "$PROOF/save.log" >&2; reject 'G5 Debian Linux short save failed';
    }
(cd "$PROOF" && sha256sum persistent.pre.rtl persistent.pre.host > sha256.txt)
[[ "$(sha256sum "$FW_BIN" | awk '{print $1}')" == "$FW_SHA" &&
   "$(sha256sum "$LINUX_IMAGE" | awk '{print $1}')" == "$LINUX_SHA" &&
   "$(sha256sum "$ROOTFS_RESULT" | awk '{print $1}')" == "$ROOTFS_SHA" ]] || reject 'Debian inputs mutated across checkpoint process boundary'
timeout --signal=TERM --kill-after=10s 180s \
  "$SIM" restore "$FW_BIN" "$PREFIX" "$MODEL_SHA" "$FW_SHA" 100000000 \
    'Linux version 6.6.143' 500000 > "$PROOF/restore.log" 2>&1 || {
      tail -70 "$PROOF/restore.log" >&2; reject 'G5 Debian Linux restore failed';
    }
(cd "$PROOF" && sha256sum persistent.pre.rtl persistent.pre.host \
  persistent.continuous.rtl persistent.continuous.host \
  persistent.restored.rtl persistent.restored.host > sha256.txt)
printf 'complete=1\ntop=%s\nmodel_sha256=%s\nfirmware_sha256=%s\nlinux_image_sha256=%s\nrootfs_manifest_sha256=%s\nmilestone=Linux version 6.6.143\nboot_max=100000000\ncheckpoint_at=100000000\nreplay_cycles=500000\nexternal_sd=0\npid1_qualified=0\n' \
  "$TOP" "$MODEL_SHA" "$FW_SHA" "$LINUX_SHA" "$ROOTFS_SHA" > "$PROOF/.complete"
verify_proof "$PROOF" | tee "$PROOF/qualification.txt"
echo "PRODUCT_G5_PERSISTENT_PROOF_DIR=$PROOF"
