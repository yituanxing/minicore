#!/usr/bin/env bash
set -euo pipefail

# G5-SD1 is a physically backed SPI SDHC + ext4 Debian qualification, *not*
# the architectural D4 performance milestone. This entry is always manual;
# no source push, CI, or normal test target invokes it. The cheap card probe
# is separated from RTL elaboration and the one expensive full Linux attempt.
#
# Usage (after separately staging a pinned SD1 Debian rootfs/SD image/payload):
#   bash tools/ci/product_sim_sd1_manual.sh preflight
#   bash tools/ci/product_sim_sd1_manual.sh prepare-model
#   AETHERCORE_SD1_LONGRUN_CONFIRM=RUN_REAL_DEBIAN_ON_PRODUCT \
#     bash tools/ci/product_sim_sd1_manual.sh longrun
# The model build may reuse an exact previously cached compiled simulator.
# No RAM-only host snapshot is called an RTL checkpoint.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in preflight|prepare-model|longrun) ;; *)
  echo 'usage: product_sim_sd1_manual.sh preflight|prepare-model|longrun' >&2
  exit 2
  ;;
esac

if [[ "${AETHERCORE_VERILATOR_VERSION:-5.024}" != 5.024 ||
      "${SIM_SETTLE_MODE:-reference}" != reference ]]; then
  echo 'G5-SD1 accepts only proven Verilator 5.024 + reference memory settle.' >&2
  echo 'Qualify 5.054/adaptive separately by short Product A/B before enabling them.' >&2
  exit 3
fi

SD_RESULT="$ROOT/build/rv64-debian-sd1-sd/result.txt"
ROOTFS_RESULT="$ROOT/build/rv64-debian-rootfs/result.txt"
FW_RESULT="$ROOT/build/rv64-debian-boot/result.txt"
SD_IMAGE="$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img"
BUILD_DIR="${AETHERCORE_SD1_MODEL_BUILD_DIR:-$ROOT/build/product-g5-sd1-model}"
TOP=AetherCoreProductDebianSdRV64SimTop
ELABORATE_MAIN=aethercore.sim.ElaborateProductDebianSdRV64SimTop
SIM_CXXFLAGS="-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_SPI_SD -I$ROOT/sim/product_rv64_opensbi_shim -I$ROOT/sim"

result_field() {
  local key="$1" file="$2"
  [[ -s "$file" ]] || { echo "missing SD1 result manifest: $file" >&2; return 1; }
  local value
  value="$(sed -n "s/^${key}=//p" "$file" | head -n 1)"
  [[ -n "$value" ]] || { echo "missing SD1 manifest key $key in $file" >&2; return 1; }
  printf '%s\n' "$value"
}

check_artifacts() {
  local required sd_bytes disk_bytes rootfs_sha firmware dtb
  grep -Fxq 'RV64_DEBIAN_SD1_SD_IMAGE_RESULT: status=READY_UNVERIFIED' "$SD_RESULT" || {
    echo 'SD1 raw media manifest is not ready for preflight' >&2; exit 4;
  }
  grep -Fxq 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS' "$ROOTFS_RESULT"
  grep -Fxq 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' "$FW_RESULT"
  for required in 'stage=sd1' 'partition_table=MBR' 'partition_count=1' \
      'partition1_type=0x83' 'partition1_fs=ext4' \
      'partition1_first_lba=2048' 'sector_bytes=512'; do
    grep -Fxq "$required" "$SD_RESULT" || {
      echo "SD1 media manifest contract mismatch: $required" >&2; exit 5;
    }
  done
  grep -Fxq 'stage=sd1' "$ROOTFS_RESULT"
  grep -Fxq 'spi_sd=1' "$FW_RESULT"
  grep -Fq 'root=/dev/mmcblk0p1 rootfstype=ext4 rootwait rw init=/sbin/init' "$FW_RESULT"
  [[ "$(result_field SD_IMAGE "$SD_RESULT")" == "$SD_IMAGE" && -s "$SD_IMAGE" ]] || {
    echo 'SD1 SD_IMAGE must be the exact staged raw MBR/ext4 disk' >&2; exit 6;
  }
  [[ "$(result_field SD_IMAGE "$FW_RESULT")" == "$SD_IMAGE" ]] || {
    echo 'OpenSBI payload manifest identifies a different SD card' >&2; exit 7;
  }
  local sha
  sha="$(result_field sd_image_sha256 "$SD_RESULT")"
  [[ "$sha" =~ ^[a-f0-9]{64}$ ]] || { echo 'bad SD1 SHA256' >&2; exit 8; }
  printf '%s  %s\n' "$sha" "$SD_IMAGE" | sha256sum -c -
  [[ "$(result_field sd_image_sha256 "$FW_RESULT")" == "$sha" ]] || {
    echo 'SD1 payload and SD image SHA256 differ' >&2; exit 9;
  }
  rootfs_sha="$(sha256sum "$ROOTFS_RESULT" | awk '{print $1}')"
  [[ "$(result_field rootfs_result_sha256 "$SD_RESULT")" == "$rootfs_sha" ]] || {
    echo 'SD1 rootfs changed since disk preparation' >&2; exit 10;
  }
  [[ "$(result_field rootfs_result_sha256 "$FW_RESULT")" == "$rootfs_sha" ]] || {
    echo 'SD1 rootfs differs from OpenSBI firmware payload' >&2; exit 11;
  }
  sd_bytes="$(stat -c%s "$SD_IMAGE")"
  disk_bytes="$(result_field disk_bytes "$SD_RESULT")"
  [[ "$sd_bytes" == "$disk_bytes" ]] || {
    echo 'SD1 disk length differs from signed manifest' >&2; exit 12;
  }
  firmware="$(result_field firmware_bin "$FW_RESULT")"
  dtb="$(result_field fdt "$FW_RESULT")"
  [[ "$firmware" == "$ROOT"/* && -s "$firmware" &&
     "$dtb" == "$ROOT"/* && -s "$dtb" ]] || {
    echo 'SD1 firmware or device tree is missing/mismatched' >&2; exit 13;
  }
  [[ "$(result_field firmware_bin_sha256 "$FW_RESULT")" == "$(sha256sum "$firmware" | awk '{print $1}')" ]] || {
    echo 'SD1 independent DDR firmware SHA256 differs from payload manifest' >&2; exit 13;
  }
  [[ "$(result_field fdt_sha256 "$FW_RESULT")" == "$(sha256sum "$dtb" | awk '{print $1}')" ]] || {
    echo 'SD1 independent DDR DTB SHA256 differs from payload manifest' >&2; exit 13;
  }
  [[ -s "$ROOT/build/rv64-debian-boot/evidence/sha256.txt" ]] || {
    echo 'SD1 firmware+DTB checksum evidence is missing' >&2; exit 13;
  }
  sha256sum --check --status "$ROOT/build/rv64-debian-boot/evidence/sha256.txt" || {
    echo 'SD1 firmware/DTB/Linux image differs from payload checksums' >&2; exit 13;
  }
  (( $(stat -c%s "$firmware") <= 268435456 )) || {
    echo 'SD1 firmware payload cannot fit the qualified 256MiB simulator RAM' >&2; exit 14;
  }
  (( $(stat -c%s "$dtb") <= 1048576 )) || {
    echo 'SD1 independent DTB is above the maximum 1MiB DDR loader segment' >&2; exit 14;
  }
  printf 'PRODUCT_G5_SD1_OFFLINE_ARTIFACT_IDENTITY_PASS sha256=%s fw=%s\n' "$sha" "$firmware"
}

probe_card() {
  local probe_dir="$ROOT/build/product-g5-sd1-preflight"
  mkdir -p "$probe_dir"
  c++ -std=c++20 -O2 -Wall -Wextra -Werror -I"$ROOT/sim" \
    "$ROOT/tools/ci/product_sim_sd1_card_probe.cpp" -o "$probe_dir/card-probe"
  "$probe_dir/card-probe" "$SD_IMAGE"
}

check_artifacts
probe_card
[[ "$ACTION" == preflight ]] && exit 0

cache_args=("$BUILD_DIR" "$TOP" "$ELABORATE_MAIN" "$SIM_CXXFLAGS")
if [[ "$ACTION" == prepare-model ]]; then
  mkdir -p "$BUILD_DIR"
  if bash tools/ci/product_sim_cache.sh verify "${cache_args[@]}"; then
    echo 'PRODUCT_G5_SD1_EXACT_MODEL_REUSED: no RTL re-elaboration or model rebuild'
  else
    # This is deliberately absent from the cheap preflight path. Only an
    # explicit prepare-model request may install/verify the frozen toolchain.
    AETHERCORE_VERILATOR_FAST_VERIFY=1 \
      bash tools/ensure_verilator_5_024.sh
    cache_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
    verilator_bin="$cache_root/verilator-5.024/bin/verilator"
    [[ -x "$verilator_bin" ]] || {
      echo 'Pinned Verilator 5.024 binary was not installed' >&2; exit 17;
    }
    make -f Makefile.l32-linux-boot BUILD_DIR="$BUILD_DIR" \
      TOP="$TOP" ELABORATE_MAIN="$ELABORATE_MAIN" \
      VERILATOR="$verilator_bin -LDFLAGS -ldl" JOBS="${AETHERCORE_SD1_BUILD_JOBS:-4}" \
      SIM_CXXFLAGS="$SIM_CXXFLAGS" SIM_SETTLE_MODE=reference sim
    bash tools/ci/product_sim_cache.sh record "${cache_args[@]}"
  fi
  exit 0
fi

# A separate human confirmation is required even after the offline/pin-level
# probe and cached model exist. This entry never auto-builds or auto-retries
# an expensive Product Linux simulation on failure.
[[ "${AETHERCORE_SD1_LONGRUN_CONFIRM:-}" == RUN_REAL_DEBIAN_ON_PRODUCT ]] || {
  echo 'Refusing slow RTL: export AETHERCORE_SD1_LONGRUN_CONFIRM=RUN_REAL_DEBIAN_ON_PRODUCT' >&2
  exit 15
}
bash tools/ci/product_sim_cache.sh verify "${cache_args[@]}" || {
  echo 'Missing exact SD1 RTL model: explicitly run prepare-model first' >&2
  exit 16
}
export AETHERCORE_PRODUCT_SD_IMAGE="$SD_IMAGE"
export AETHERCORE_PRODUCT_DTB_IMAGE="$(result_field fdt "$FW_RESULT")"
FW_BIN="$(result_field firmware_bin "$FW_RESULT")"
make -f Makefile.l32-linux-boot \
  BUILD_DIR="$BUILD_DIR" TOP="$TOP" ELABORATE_MAIN="$ELABORATE_MAIN" \
  FW_BIN="$FW_BIN" MAX_CYCLES="${AETHERCORE_SD1_MAX_CYCLES:-1800000000}" \
  MILESTONE='login:' MIN_INTERRUPTS=0 MIN_STIP=0 MIN_SEIP=0 \
  REQUIRE_LAYERED_COMPRESSED=1 PROGRESS_INTERVAL_CYCLES=10000000 \
  SIM_CXXFLAGS="$SIM_CXXFLAGS" SIM_SETTLE_MODE=reference \
  run-existing
log="$BUILD_DIR/logs/boot-probe.log"
grep -Fq 'PRODUCT_SD1_SD_IMAGE_READY ' "$log"
grep -Fq 'PRODUCT_SD1_INDEPENDENT_DTB_READY ' "$log"
grep -Eq 'sd-reads=[1-9][0-9]*' "$log"
grep -Eq 'mmcblk0:.*p1|mmcblk0p1' "$log"
grep -Fq 'VFS: Mounted root (ext4 filesystem)' "$log"
grep -Fq 'Run /sbin/init as init process' "$log"
grep -Eq '(^|[[:space:]])login:' "$log"
grep -Fq 'L32_RUNTIME_MILESTONE_PASS' "$log"
printf '%s  %s\n' "$(result_field sd_image_sha256 "$SD_RESULT")" "$SD_IMAGE" | sha256sum -c -
echo 'PRODUCT_G5_SD1_RTL_SPI_SD_EXT4_NATIVE_DEBIAN_LOGIN_PASS'
