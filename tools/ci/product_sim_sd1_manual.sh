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
source "$ROOT/tools/ci/product_model_identity.sh"
ACTION="${1:-}"
case "$ACTION" in preflight|prepare-model|longrun) ;; *)
  echo 'usage: product_sim_sd1_manual.sh preflight|prepare-model|longrun' >&2
  exit 2
  ;;
esac

# Refuse accidental multi-billion-cycle requests before even hashing a large
# disk image or compiling the lightweight offline SD-card probe. Only the
# explicitly confirmed longrun can reach the already prepared RTL executable.
if [[ "$ACTION" == longrun ]]; then
  [[ "${AETHERCORE_SD1_LONGRUN_CONFIRM:-}" == RUN_REAL_DEBIAN_ON_PRODUCT ]] || {
    echo 'Refusing slow RTL: export AETHERCORE_SD1_LONGRUN_CONFIRM=RUN_REAL_DEBIAN_ON_PRODUCT' >&2
    exit 15
  }
  sd1_max_cycles="${AETHERCORE_SD1_MAX_CYCLES:-1800000000}"
  [[ "$sd1_max_cycles" =~ ^[0-9]+$ ]] &&
    (( sd1_max_cycles > 0 && sd1_max_cycles <= 1800000000 )) || {
    echo 'G5-SD1 RTL cycle budget must be 1..1800000000 (single manually gated attempt)' >&2
    exit 18
  }
fi

export AETHERCORE_VERILATOR_VERSION="${AETHERCORE_VERILATOR_VERSION:-5.054}"
export SIM_SETTLE_MODE="${SIM_SETTLE_MODE:-reference}"
case "$AETHERCORE_VERILATOR_VERSION:$SIM_SETTLE_MODE" in
  5.024:reference) sd1_fast_profile=0 ;;
  5.054:reference|5.054:adaptive) sd1_fast_profile=1 ;;
  *)
    echo 'G5-SD1 defaults to 5.054/reference (requires matching SD1 short A/B evidence); explicit 5.024/reference is the legacy fallback.' >&2
    exit 3
    ;;
esac

SD_RESULT="$ROOT/build/rv64-debian-sd1-sd/result.txt"
ROOTFS_RESULT="$ROOT/build/rv64-debian-rootfs/result.txt"
FW_RESULT="$ROOT/build/rv64-debian-boot/result.txt"
LINUX_RESULT="$ROOT/build/rv64-debian-linux/result.txt"
LINUX_IMAGE="$ROOT/build/rv64-debian-linux/obj/arch/riscv/boot/Image"
LINUX_CONFIG="$ROOT/build/rv64-debian-linux/obj/.config"
SD_IMAGE="$ROOT/build/rv64-debian-sd1-sd/sd-rootfs.img"
# Preserve the historical 5.024/reference model cache. Fast candidates get
# distinct default build trees, so switching Verilator/settle cannot inherit
# stale generated objects or an old executable from a different profile.
if (( sd1_fast_profile )); then
  sd1_default_model_dir="$ROOT/build/product-g5-sd1-model-$AETHERCORE_VERILATOR_VERSION-$SIM_SETTLE_MODE"
else
  sd1_default_model_dir="$ROOT/build/product-g5-sd1-model"
fi
BUILD_DIR="${AETHERCORE_SD1_MODEL_BUILD_DIR:-$sd1_default_model_dir}"
TOP=AetherCoreProductDebianSdRV64SimTop
ELABORATE_MAIN=aethercore.sim.ElaborateProductDebianSdRV64SimTop
SIM_CXXFLAGS="-std=c++20 -O3 -march=native -DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_SPI_SD -I$ROOT/sim/product_rv64_opensbi_shim -I$ROOT/sim"
cache_args=("$BUILD_DIR" "$TOP" "$ELABORATE_MAIN" "$SIM_CXXFLAGS")

result_field() {
  local key="$1" file="$2"
  [[ -s "$file" ]] || { echo "missing SD1 result manifest: $file" >&2; return 1; }
  local value
  value="$(sed -n "s/^${key}=//p" "$file" | head -n 1)"
  [[ -n "$value" ]] || { echo "missing SD1 manifest key $key in $file" >&2; return 1; }
  printf '%s\n' "$value"
}

check_artifacts() {
  local required sd_bytes disk_bytes rootfs_sha firmware dtb linux_image_sha linux_config_sha
  grep -Fxq 'RV64_DEBIAN_SD1_SD_IMAGE_RESULT: status=READY_UNVERIFIED' "$SD_RESULT" || {
    echo 'SD1 raw media manifest is not ready for preflight' >&2; exit 4;
  }
  grep -Fxq 'RV64_DEBIAN_ROOTFS_BUILD_RESULT: status=PASS' "$ROOTFS_RESULT"
  grep -Fxq 'RV64_DEBIAN_LINUX_BUILD_RESULT: status=PASS' "$LINUX_RESULT"
  grep -Fxq 'RV64_DEBIAN_PAYLOAD_BUILD_RESULT: status=PASS' "$FW_RESULT"
  for required in 'stage=sd1' 'partition_table=MBR' 'partition_count=1' \
      'partition1_type=0x83' 'partition1_fs=ext4' \
      'partition1_first_lba=2048' 'sector_bytes=512'; do
    grep -Fxq "$required" "$SD_RESULT" || {
      echo "SD1 media manifest contract mismatch: $required" >&2; exit 5;
    }
  done
  grep -Fxq 'stage=sd1' "$ROOTFS_RESULT"
  grep -Fxq 'stage=sd1' "$LINUX_RESULT"
  [[ "$(result_field linux_stage "$FW_RESULT")" == sd1 ]] || {
    echo 'SD1 firmware is not tied to a real SD1 Linux Image' >&2; exit 5;
  }
  grep -Fxq 'spi_sd=1' "$FW_RESULT"
  # Older Product payloads inherited the V2 DTS's 16-source PLIC despite
  # Product RTL implementing 52 sources. Do not spend another longrun on a
  # stale image without the explicitly selected Product board profile.
  grep -Fxq 'product_board=1' "$FW_RESULT" || {
    echo 'SD1 payload was not emitted for the 52-source Product board' >&2; exit 5;
  }
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
  [[ "$(result_field rootfs_result_sha256 "$LINUX_RESULT")" == "$rootfs_sha" &&
     "$(result_field rootfs_tree_sha256 "$LINUX_RESULT")" == "$(result_field rootfs_tree_sha256 "$ROOTFS_RESULT")" &&
     "$(result_field rootfs_tree_sha256 "$FW_RESULT")" == "$(result_field rootfs_tree_sha256 "$SD_RESULT")" ]] || {
    echo 'SD1 Linux, firmware and SD image reference different full Debian rootfs generations' >&2; exit 11;
  }
  [[ -s "$LINUX_IMAGE" && -s "$LINUX_CONFIG" &&
     "$(result_field image "$LINUX_RESULT")" == "$LINUX_IMAGE" &&
     "$(result_field linux_image "$FW_RESULT")" == "$LINUX_IMAGE" ]] || {
    echo 'SD1 Linux Image/config is missing or differs from the firmware input' >&2; exit 11;
  }
  linux_image_sha="$(sha256sum "$LINUX_IMAGE" | awk '{print $1}')"
  linux_config_sha="$(sha256sum "$LINUX_CONFIG" | awk '{print $1}')"
  [[ "$(result_field image_sha256 "$LINUX_RESULT")" == "$linux_image_sha" &&
     "$(result_field linux_image_sha256 "$FW_RESULT")" == "$linux_image_sha" &&
     "$(result_field config_sha256 "$LINUX_RESULT")" == "$linux_config_sha" &&
     "$(result_field linux_config_sha256 "$FW_RESULT")" == "$linux_config_sha" &&
     "$(result_field linux_result_sha256 "$FW_RESULT")" == "$(sha256sum "$LINUX_RESULT" | awk '{print $1}')" ]] || {
    echo 'SD1 Linux Image, resolved config or qualified Linux manifest changed after firmware preparation' >&2; exit 11;
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
  local probe_bin="$probe_dir/card-probe"
  local probe_identity_file="$probe_dir/card-probe.identity"
  local probe_identity actual_bin_sha
  mkdir -p "$probe_dir"
  # Every preflight/prepare-model/longrun used to recompile this unchanged host
  # probe. Its two local sources and compiler flags determine the executable;
  # reuse is allowed only if both that exact identity and the executable hash
  # match the previously recorded pair. The *SD media itself* is never cached:
  # check_artifacts above always re-hashes the staged disk on every action.
  probe_identity="$({
    printf '%s\n' 'compiler=c++' 'flags=-std=c++20 -O2 -Wall -Wextra -Werror -I<root>/sim'
    command -v c++
    c++ --version | sed -n '1p'
    # Do not trust only the compiler version string: an executable replaced
    # in place can report the same version while producing a different probe.
    # The same immutable-input rule applies to this cheap binary and to the
    # separately content-addressed Product RTL model.
    sha256sum "$(readlink -f "$(command -v c++)")"
    sha256sum "$ROOT/sim/l32_sd_spi_card.h" \
      "$ROOT/tools/ci/product_sim_sd1_card_probe.cpp"
  } | sha256sum | awk '{print $1}')"
  if [[ ! -x "$probe_bin" || ! -f "$probe_identity_file" ]] ||
      ! grep -Fxq "source=$probe_identity" "$probe_identity_file" ||
      ! grep -Fxq "binary=$(sha256sum "$probe_bin" | awk '{print $1}')" "$probe_identity_file"; then
    local probe_tmp="$probe_dir/card-probe.$$.tmp"
    if ! c++ -std=c++20 -O2 -Wall -Wextra -Werror -I"$ROOT/sim" \
        "$ROOT/tools/ci/product_sim_sd1_card_probe.cpp" -o "$probe_tmp"; then
      rm -f "$probe_tmp"
      return 1
    fi
    mv -f "$probe_tmp" "$probe_bin"
    actual_bin_sha="$(sha256sum "$probe_bin" | awk '{print $1}')"
    {
      printf 'source=%s\n' "$probe_identity"
      printf 'binary=%s\n' "$actual_bin_sha"
    } > "$probe_identity_file.$$.tmp"
    mv -f "$probe_identity_file.$$.tmp" "$probe_identity_file"
  else
    echo 'PRODUCT_G5_SD1_HOST_CARD_PROBE_REUSED: source and executable hashes match'
  fi
  "$probe_bin" "$SD_IMAGE"
}

verify_fast_profile_qualification() {
  # The old V2/Linux speedups are not Product SD1 equivalence evidence.  A
  # separate short SD1 A/B must have compared all three profiles at the same
  # cycle and with the SAME media, independent DTB and firmware. Verify the
  # actual current Product model fingerprint (Scala/C++/host/Verilator bytes,
  # model flags and CPU features) and retained A/B state files before allowing
  # a fast longrun or even spending time compiling its candidate model.
  local evidence="${AETHERCORE_SD1_AB_EVIDENCE:-$ROOT/build/product-sim-short-ab/sd1-accelerator-qualification.txt}"
  [[ -s "$evidence" ]] || {
    echo "Missing SD1 fast-profile qualification: $evidence" >&2
    echo 'Run explicit short Product SD1 A/B first; historical V2 results are insufficient.' >&2
    exit 19
  }
  local evidence_dir ref_state fast_state adaptive_state state_sha model_identity fingerprint_key compared_cycles
  evidence_dir="$(dirname "$evidence")"
  ref_state="$evidence_dir/verilator-5.024-reference.state"
  fast_state="$evidence_dir/verilator-5.054-reference.state"
  adaptive_state="$evidence_dir/verilator-5.054-adaptive.state"
  [[ -s "$ref_state" && -s "$fast_state" && -s "$adaptive_state" ]] &&
    cmp -s "$ref_state" "$fast_state" &&
    cmp -s "$ref_state" "$adaptive_state" || {
      echo 'SD1 A/B reference and fast same-cycle state files are missing or disagree' >&2
      exit 19
    }
  state_sha="$(sha256sum "$ref_state" | awk '{print $1}')"
  compared_cycles="$(sed -n 's/^compared_cycles=//p' "$evidence" | head -n 1)"
  [[ "$compared_cycles" =~ ^[0-9]+$ ]] &&
    (( compared_cycles >= 12000000 && compared_cycles <= 20000000 )) || {
      echo 'SD1 A/B evidence has an unqualified or missing same-cycle comparison window' >&2
      exit 19
    }
  for expected in \
      'qualification=PRODUCT_SD1_SHORT_AB_IDENTICAL_NOT_DEBIAN_PASS' \
      "top=$TOP" "elaborate=$ELABORATE_MAIN" "compared_cycles=$compared_cycles" \
      "state_sha256=$state_sha" \
      "ab_script_sha256=$(sha256sum "$ROOT/tools/ci/product_sim_ab.sh" | awk '{print $1}')" \
      "firmware_sha256=$(result_field firmware_bin_sha256 "$FW_RESULT")" \
      "dtb_sha256=$(result_field fdt_sha256 "$FW_RESULT")" \
      "sd_image_sha256=$(result_field sd_image_sha256 "$SD_RESULT")"; do
    grep -Fxq "$expected" "$evidence" || {
      echo "SD1 A/B qualification input changed: $expected" >&2
      exit 19
    }
  done
  case "$AETHERCORE_VERILATOR_VERSION:$SIM_SETTLE_MODE" in
    5.054:reference)
      fingerprint_key=model_identity_5054_reference
      grep -Fxq 'eligible_5054_reference=1' "$evidence" || {
        echo 'Short SD1 A/B did not measure >=1.03x for 5.054/reference vs 5.024/reference' >&2
        exit 19
      }
      ;;
    5.054:adaptive)
      fingerprint_key=model_identity_5054_adaptive
      grep -Fxq 'eligible_5054_adaptive=1' "$evidence" || {
        echo 'Short SD1 A/B did not measure >=1.03x for 5.054/adaptive vs 5.024/reference' >&2
        exit 19
      }
      ;;
  esac
  model_identity="$(product_model_fingerprint "$AETHERCORE_VERILATOR_VERSION" \
    "$SIM_SETTLE_MODE" "${cache_args[@]}")"
  grep -Fxq "$fingerprint_key=$model_identity" "$evidence" || {
    echo 'SD1 A/B evidence does not match the current Product model and host toolchain bytes' >&2
    exit 19
  }
  echo "PRODUCT_G5_SD1_SHORT_AB_PROFILE_VERIFIED profile=$AETHERCORE_VERILATOR_VERSION/$SIM_SETTLE_MODE state_sha256=$state_sha"
  echo 'Short-window simulator equivalence only; native Debian boot remains unverified.'
}

check_artifacts
if (( sd1_fast_profile )) && [[ "$ACTION" != preflight ]]; then
  verify_fast_profile_qualification
fi
probe_card
[[ "$ACTION" == preflight ]] && exit 0

if [[ "$ACTION" == prepare-model ]]; then
  mkdir -p "$BUILD_DIR"
  if product_model_verify "$AETHERCORE_VERILATOR_VERSION" "$SIM_SETTLE_MODE" "${cache_args[@]}"; then
    echo 'PRODUCT_G5_SD1_EXACT_MODEL_REUSED: no RTL re-elaboration or model rebuild'
  else
    # This is deliberately absent from the cheap preflight path. Only an
    # explicit prepare-model request may install/verify the frozen toolchain.
    AETHERCORE_VERILATOR_FAST_VERIFY=1 \
      bash tools/ensure_verilator_5_024.sh
    cache_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
    verilator_bin="$cache_root/verilator-$AETHERCORE_VERILATOR_VERSION/bin/verilator"
    [[ -x "$verilator_bin" ]] || {
      echo "Pinned Verilator $AETHERCORE_VERILATOR_VERSION binary was not installed" >&2; exit 17;
    }
    # If another editing agent changes RTL/C++ while this costly build runs,
    # the resulting executable must not be recorded under a newer source key.
    model_build_identity_before="$(product_model_fingerprint "$AETHERCORE_VERILATOR_VERSION" \
      "$SIM_SETTLE_MODE" "${cache_args[@]}")"
    make -f Makefile.l32-linux-boot BUILD_DIR="$BUILD_DIR" \
      TOP="$TOP" ELABORATE_MAIN="$ELABORATE_MAIN" \
      VERILATOR="$verilator_bin -LDFLAGS -ldl" JOBS="${AETHERCORE_SD1_BUILD_JOBS:-4}" \
      SIM_CXXFLAGS="$SIM_CXXFLAGS" SIM_SETTLE_MODE="$SIM_SETTLE_MODE" sim
    [[ "$model_build_identity_before" == "$(product_model_fingerprint "$AETHERCORE_VERILATOR_VERSION" \
      "$SIM_SETTLE_MODE" "${cache_args[@]}")" ]] || {
      echo 'Product SD1 model inputs changed while compiling; refusing to cache an executable from stale RTL' >&2
      exit 20
    }
    product_model_record "$AETHERCORE_VERILATOR_VERSION" "$SIM_SETTLE_MODE" "${cache_args[@]}"
  fi
  exit 0
fi

# The confirmation and bounded cycle budget were already checked before the
# offline probe. This path never auto-builds or auto-retries the expensive RTL.
product_model_verify "$AETHERCORE_VERILATOR_VERSION" "$SIM_SETTLE_MODE" "${cache_args[@]}" || {
  echo 'Missing exact SD1 RTL model: explicitly run prepare-model first' >&2
  exit 16
}
export AETHERCORE_PRODUCT_SD_IMAGE="$SD_IMAGE"
export AETHERCORE_PRODUCT_DTB_IMAGE="$(result_field fdt "$FW_RESULT")"
FW_BIN="$(result_field firmware_bin "$FW_RESULT")"
make -f Makefile.l32-linux-boot \
  BUILD_DIR="$BUILD_DIR" TOP="$TOP" ELABORATE_MAIN="$ELABORATE_MAIN" \
  FW_BIN="$FW_BIN" MAX_CYCLES="$sd1_max_cycles" \
  MILESTONE='login:' MIN_INTERRUPTS=0 MIN_STIP=0 MIN_SEIP=0 \
  REQUIRE_LAYERED_COMPRESSED=1 PROGRESS_INTERVAL_CYCLES=10000000 \
  SIM_CXXFLAGS="$SIM_CXXFLAGS" SIM_SETTLE_MODE="$SIM_SETTLE_MODE" \
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
