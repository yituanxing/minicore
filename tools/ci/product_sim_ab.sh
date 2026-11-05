#!/usr/bin/env bash
set -euo pipefail

# Explicit, SHORT Product RTL A/B qualification; intentionally never called by
# PR push/pull_request. Three identical-cycle Product runs qualify BOTH the
# change from 5.024 to 5.054 and the optional adaptive settle loop. Never
# promote a faster version using a historical V2/QEMU measurement as proof.
# The explicit SD1 top additionally uses the SAME pinned SDHC image, independent
# DDR DTB and firmware across all three profiles; only the simulator changes.

if [[ "${AETHERCORE_ALLOW_PRODUCT_AB:-0}" != 1 ]]; then
  echo 'Product simulator A/B requires explicit AETHERCORE_ALLOW_PRODUCT_AB=1' >&2
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
top="${TOP:-AetherCoreProductDebianRV64SimTop}"
case "$top" in
  AetherCoreProductDebianRV64SimTop)
    default_elaborate=aethercore.sim.ElaborateProductDebianRV64SimTop
    product_flag='-DAETHERCORE_PRODUCT_DEBIAN'
    sd1=0
    ;;
  AetherCoreProductOpenSbiRV64SimTop)
    default_elaborate=aethercore.sim.ElaborateProductOpenSbiRV64SimTop
    product_flag=''
    sd1=0
    ;;
  AetherCoreProductDebianSdRV64SimTop)
    default_elaborate=aethercore.sim.ElaborateProductDebianSdRV64SimTop
    product_flag='-DAETHERCORE_PRODUCT_DEBIAN -DAETHERCORE_PRODUCT_SPI_SD'
    sd1=1
    ;;
  *) echo "unsupported Product A/B top: $top" >&2; exit 4 ;;
esac
elaborate="${ELABORATE_MAIN:-$default_elaborate}"
fw_bin="${FW_BIN:-build/rv64-debian-boot/opensbi/platform/generic/firmware/fw_payload.bin}"
max_cycles="${MAX_CYCLES:-12000000}"
out="${OUT_ROOT:-build/product-sim-short-ab}"
jobs="${JOBS:-4}"
sd1_transport="${AETHERCORE_SD1_TRANSPORT_SHORT:-0}"
[[ "$sd1_transport" == 0 || "$sd1_transport" == 1 ]] || {
  echo 'AETHERCORE_SD1_TRANSPORT_SHORT must be 0 or 1' >&2; exit 4;
}
if (( sd1_transport )) && (( ! sd1 )); then
  echo 'Transport-only probe requires explicit AetherCoreProductDebianSdRV64SimTop' >&2; exit 4
fi
cache_root="${AETHERCORE_TOOLCHAIN_CACHE:-$HOME/.cache/aethercore/toolchains}"
[[ "${VERILATOR_THREADS:-1}" == 1 &&
   "${VERILATOR_MODEL_OPT:--O3}" == -O3 &&
   "${VERILATOR_MAKE_OPT:--MAKEFLAGS OPT_FAST=-O3 OPT_GLOBAL=-O3}" == '-MAKEFLAGS OPT_FAST=-O3 OPT_GLOBAL=-O3' ]] || {
  echo 'Product short A/B requires exactly one Verilator thread and the cached -O3 model/build options' >&2
  exit 4
}

# G3's real OpenSBI + S-mode UART smoke program needs up to 30M cycles;
# the SD1 and other short-prefix probes retain their 20M-cycle ceiling.
ab_max_cycles=20000000
[[ "$top" == AetherCoreProductOpenSbiRV64SimTop ]] && ab_max_cycles=30000000
[[ "$max_cycles" =~ ^[0-9]+$ ]] && (( max_cycles > 0 && max_cycles <= ab_max_cycles )) || {
  echo "Product A/B MAX_CYCLES must be in the short qualification range 1..$ab_max_cycles" >&2
  exit 3
}
if (( sd1 && max_cycles < 12000000 )); then
  echo 'Product SD1 A/B must compare at least 12000000 cycles (default) to avoid certifying a reset-only prefix' >&2
  exit 3
fi

# SD1 is a real SPI SDHC + independent DDR-DTB top.  Require the existing
# offline media/payload preflight before allocating a single RTL build.  Its
# manifests select the exact same firmware/DTB/media for every A/B profile;
# a caller cannot silently swap in a different payload via FW_BIN.
if (( sd1 && sd1_transport )); then
  # This opt-in short gate is NOT Debian. Its independent 64MiB SD/DTB and
  # baremetal PLIC/SPI exerciser can never produce G5 qualification evidence.
  bash tools/ci/product_sd1_transport_short_build.sh preflight
  fw_manifest="${AETHERCORE_SD1_TRANSPORT_INPUT_ROOT:-$root/build/product-sd1-transport-input}/result.txt"
  [[ "$fw_manifest" == /* ]] || fw_manifest="$root/$fw_manifest"
  sd_manifest="$fw_manifest"
  manifest_fw="$(sed -n 's/^firmware_path=//p' "$fw_manifest")"
  manifest_dtb="$(sed -n 's/^dtb_path=//p' "$fw_manifest")"
  manifest_sd="$(sed -n 's/^sd_image_path=//p' "$fw_manifest")"
  if [[ -n "${FW_BIN:-}" && "$(readlink -f "$FW_BIN")" != "$(readlink -f "$manifest_fw")" ]]; then
    echo 'Transport A/B refuses a firmware override differing from the input manifest' >&2; exit 3
  fi
  fw_bin="$manifest_fw"
  export AETHERCORE_PRODUCT_DTB_IMAGE="$manifest_dtb"
  export AETHERCORE_PRODUCT_SD_IMAGE="$manifest_sd"
elif (( sd1 )); then
  AETHERCORE_VERILATOR_VERSION=5.024 SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_sd1_manual.sh preflight
  fw_manifest="$root/build/rv64-debian-boot/result.txt"
  sd_manifest="$root/build/rv64-debian-sd1-sd/result.txt"
  manifest_fw="$(sed -n 's/^firmware_bin=//p' "$fw_manifest" | head -n 1)"
  manifest_dtb="$(sed -n 's/^fdt=//p' "$fw_manifest" | head -n 1)"
  manifest_sd="$(sed -n 's/^SD_IMAGE=//p' "$sd_manifest" | head -n 1)"
  [[ -n "$manifest_fw" && -s "$manifest_fw" &&
     -n "$manifest_dtb" && -s "$manifest_dtb" &&
     -n "$manifest_sd" && -s "$manifest_sd" ]] || {
    echo 'Product SD1 A/B requires matching firmware, independent DTB and raw SD artifacts' >&2
    exit 3
  }
  if [[ -n "${FW_BIN:-}" && "$(readlink -f "$FW_BIN")" != "$(readlink -f "$manifest_fw")" ]]; then
    echo 'Product SD1 A/B refuses FW_BIN differing from the preflight-approved payload' >&2
    exit 3
  fi
  fw_bin="$manifest_fw"
  export AETHERCORE_PRODUCT_DTB_IMAGE="$manifest_dtb"
  export AETHERCORE_PRODUCT_SD_IMAGE="$manifest_sd"
fi
[[ -s "$fw_bin" ]] || { echo "missing Product payload: $fw_bin" >&2; exit 3; }
firmware_bytes="$(stat -c%s "$fw_bin")"
if (( firmware_bytes > 268435456 )); then
  echo "Product A/B payload exceeds simulator's 256MiB RAM: ${firmware_bytes} bytes" >&2
  exit 3
fi
mkdir -p "$out/rtl-seed/rtl" "$out/logs"
flags="-std=c++20 -O3 -march=native"
[[ -n "$product_flag" ]] && flags+=" $product_flag"
flags+=" -I$root/sim/product_rv64_opensbi_shim -I$root/sim"
# The same G3 Product OpenSBI top is used by G4 Linux. Record three exact
# compiled model identities here so the PID1 verifier can reuse the expensive
# RTL elaboration and host binaries without trusting stale build products.
# Install both pinned toolchains before hashing: a missing installation must
# never be mistaken for an immutable generator identity.
unset VERILATOR_ROOT
for pinned in 5.024 5.054; do
  AETHERCORE_VERILATOR_VERSION="$pinned" AETHERCORE_VERILATOR_FAST_VERIFY=1 \
    bash tools/ensure_verilator_5_024.sh > "$out/logs/verilator-$pinned.preflight.log" 2>&1
done
ab_start_baseline="$(AETHERCORE_VERILATOR_VERSION=5.024 SIM_SETTLE_MODE=reference \
  bash tools/ci/product_sim_cache.sh fingerprint "$out" "$top" "$elaborate" "$flags")"
ab_start_ref="$(AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
  bash tools/ci/product_sim_cache.sh fingerprint "$out" "$top" "$elaborate" "$flags")"
ab_start_adaptive="$(AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=adaptive \
  bash tools/ci/product_sim_cache.sh fingerprint "$out" "$top" "$elaborate" "$flags")"
ab_start_script_sha="$(sha256sum "$root/tools/ci/product_sim_ab.sh" | awk '{print $1}')"
ab_start_fw_sha="$(sha256sum "$fw_bin" | awk '{print $1}')"
if (( sd1 && ! sd1_transport )); then
  sd1_start_preflight_script_sha="$(sha256sum "$root/tools/ci/product_sim_sd1_manual.sh" | awk '{print $1}')"
elif (( sd1_transport )); then
  sd1_start_preflight_script_sha="$(sha256sum "$root/tools/ci/product_sd1_transport_short_build.sh" | awk '{print $1}')"
  sd1_start_manifest_sha="$(sha256sum "$fw_manifest" | awk '{print $1}')"
  sd1_start_dtb_sha="$(sha256sum "$manifest_dtb" | awk '{print $1}')"
  sd1_start_media_sha="$(sha256sum "$manifest_sd" | awk '{print $1}')"
fi

results="$out/results.tsv"
printf 'version\tmode\tseconds\tcycles_per_second\n' > "$results"
rtl_prepared=0

for profile in 5.024:reference 5.054:reference 5.054:adaptive; do
  version="${profile%%:*}"
  mode="${profile#*:}"
  label="verilator-$version-$mode"
  # The exact pinned binary must perform the build. Setting only an output
  # directory name would accidentally run a different version from PATH.
  AETHERCORE_VERILATOR_VERSION="$version" \
    AETHERCORE_VERILATOR_FAST_VERIFY=1 bash tools/ensure_verilator_5_024.sh \
    > "$out/logs/$label.toolchain.log" 2>&1
  verilator_bin="$cache_root/verilator-$version/bin/verilator"
  [[ -x "$verilator_bin" ]] || {
    echo "missing pinned Verilator $version: $verilator_bin" >&2
    exit 5
  }
  unset VERILATOR_ROOT
  # A CMake build of the pinned shallow upstream revision can legitimately
  # report "rev UNKNOWN.REV" instead of a release-version string. The pinned
  # installer already verifies the source revision, runtime assets and host
  # binary; bind this invocation to that exact marker rather than a banner.
  case "$version" in
    5.024) expected_revision=522bead374d6b7b2adb316304126e5361b18bcf1 ;;
    5.054) expected_revision=bbb60a403932a9f695cb6ff001ceb73de6ad9234 ;;
  esac
  revision_marker="$cache_root/verilator-$version/.aethercore-source-revision"
  [[ -f "$revision_marker" && "$(cat "$revision_marker")" == "$expected_revision" &&
     -x "$cache_root/verilator-$version/bin/verilator_bin" ]] || {
    echo "wrong Verilator toolchain: selected $version but pinned revision/binary is missing" >&2
    exit 5
  }
  model_root="$out/model-$label"
  obj="$model_root/obj"
  model_args=("$model_root" "$top" "$elaborate" "$flags")
  extra=''
  [[ "$mode" == adaptive ]] && extra='-DAETHERCORE_SIM_ADAPTIVE_SETTLE'
  if AETHERCORE_VERILATOR_VERSION="$version" SIM_SETTLE_MODE="$mode" \
      bash tools/ci/product_sim_cache.sh verify "${model_args[@]}" \
      > "$out/logs/$label.cache.log" 2>&1; then
    echo "PRODUCT_SIM_AB_MODEL_REUSED: $label"
  else
    if (( ! rtl_prepared )); then
      make -f Makefile.l32-linux-boot \
        BUILD_DIR="$out/rtl-seed" TOP="$top" ELABORATE_MAIN="$elaborate" rtl
      rtl_prepared=1
    fi
    mkdir -p "$obj"
    model_build_identity_before="$(AETHERCORE_VERILATOR_VERSION="$version" SIM_SETTLE_MODE="$mode" \
      bash tools/ci/product_sim_cache.sh fingerprint "${model_args[@]}")"
    PATH="$cache_root/verilator-$version/bin:$PATH" \
    "$verilator_bin" -LDFLAGS -ldl -O3 -MAKEFLAGS 'OPT_FAST=-O3 OPT_GLOBAL=-O3' \
      --cc --exe --build -Wall -Wno-fatal \
      --top-module "$top" -Mdir "$obj" \
      -CFLAGS "$flags $extra" \
      "$out"/rtl-seed/rtl/*.sv "$root/sim/opensbi_boot_main.cpp" \
      -j "$jobs" > "$out/logs/$label.compile.log" 2>&1
    [[ "$model_build_identity_before" == "$(AETHERCORE_VERILATOR_VERSION="$version" SIM_SETTLE_MODE="$mode" \
      bash tools/ci/product_sim_cache.sh fingerprint "${model_args[@]}")" ]] || {
      echo "Product model sources/toolchain changed while building $label" >&2
      exit 10
    }
    AETHERCORE_VERILATOR_VERSION="$version" SIM_SETTLE_MODE="$mode" \
      bash tools/ci/product_sim_cache.sh record "${model_args[@]}" > "$out/logs/$label.cache.log"
  fi

  sim="$obj/V$top"
  [[ -x "$sim" ]] || { echo "Product simulator missing: $sim" >&2; exit 5; }
  log="$out/logs/$label.run.log"
  begin="$(date +%s%N)"
  set +e
  "$sim" "$fw_bin" "$max_cycles" '__PRODUCT_SIM_AB_NEVER_MATCH__' \
    0 0 '' '' 0 "$max_cycles" 0 0 0 > "$log" 2>&1
  rc=$?
  set -e
  end="$(date +%s%N)"
  [[ "$rc" == 2 ]] || { echo "Product A/B unexpected $label runner exit=$rc" >&2; exit 6; }
  grep -E '^L32_OPENSBI_TIMEOUT .*ram-fingerprint=0x' "$log" > "$out/$label.state"
  [[ -s "$out/$label.state" ]] || { echo "Product $label has no full state fingerprint" >&2; exit 7; }
  elapsed="$(awk -v start="$begin" -v stop="$end" 'BEGIN { printf "%.6f", (stop-start)/1000000000 }')"
  cps="$(awk -v n="$max_cycles" -v sec="$elapsed" 'BEGIN { printf "%.3f", n/sec }')"
  printf '%s\t%s\t%s\t%s\n' "$version" "$mode" "$elapsed" "$cps" >> "$results"
done

# The three results are comparable only if both simulator toolchains, all
# generated/host source inputs, the A/B script and the firmware stayed fixed
# across the entire run. A cache record for an earlier source generation is
# harmless, but must never grant qualification for a different generation.
ab_end_baseline="$(AETHERCORE_VERILATOR_VERSION=5.024 SIM_SETTLE_MODE=reference \
  bash tools/ci/product_sim_cache.sh fingerprint "$out" "$top" "$elaborate" "$flags")"
ab_end_ref="$(AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
  bash tools/ci/product_sim_cache.sh fingerprint "$out" "$top" "$elaborate" "$flags")"
ab_end_adaptive="$(AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=adaptive \
  bash tools/ci/product_sim_cache.sh fingerprint "$out" "$top" "$elaborate" "$flags")"
[[ "$ab_start_baseline" == "$ab_end_baseline" &&
   "$ab_start_ref" == "$ab_end_ref" &&
   "$ab_start_adaptive" == "$ab_end_adaptive" &&
   "$(sha256sum "$fw_bin" | awk '{print $1}')" == "$ab_start_fw_sha" &&
   "$(sha256sum "$root/tools/ci/product_sim_ab.sh" | awk '{print $1}')" == "$ab_start_script_sha" ]] || {
  echo 'Product A/B firmware, runner, source or toolchain changed during comparison' >&2
  exit 10
}

if ! diff -u "$out/verilator-5.024-reference.state" \
              "$out/verilator-5.054-reference.state" > "$out/version-state.diff"; then
  echo 'PRODUCT_SIM_AB_VERSION_STATE_MISMATCH: keep pinned Verilator 5.024 for Product Linux' >&2
  exit 8
fi
echo 'PRODUCT_SIM_AB_VERSION_STATE_MATCH: same Product cycles, commits, traps, interrupts, RAM/LRSC and recent PCs'

if ! diff -u "$out/verilator-5.054-reference.state" \
              "$out/verilator-5.054-adaptive.state" > "$out/adaptive-state.diff"; then
  echo 'PRODUCT_SIM_AB_ADAPTIVE_STATE_MISMATCH: keep reference settle for all Product Linux runs' >&2
  exit 9
fi
echo 'PRODUCT_SIM_AB_ADAPTIVE_STATE_MATCH: same Product cycles, commits, traps, interrupts, RAM/LRSC and recent PCs'
if (( sd1 )); then
  echo 'PRODUCT_SIM_AB_SD1_STATE_MATCH: same Product SD1 UART/CPU/RAM and SD SPI command/read/write progress at the compared cycle'
fi

awk -F '\t' '
  NR == 2 {base=$4}
  NR == 3 {
    speedup=$4/base
    printf "PRODUCT_SIM_AB_VERSION_RATE v5_024_cps=%.0f v5_054_cps=%.0f speedup=%.4fx\n", base, $4, speedup
    if (speedup >= 1.03) print "PRODUCT_SIM_AB_V5054_CANDIDATE_ONLY: requires independent review before long-run promotion"
    else print "PRODUCT_SIM_AB_V5054_NO_PROMOTION"
  }
  NR == 3 {reference=$4}
  NR == 4 {
    speedup=$4/reference
    vs_base=$4/base
    printf "PRODUCT_SIM_AB_ADAPTIVE_RATE reference_cps=%.0f adaptive_cps=%.0f speedup=%.4fx vs_5_024_baseline=%.4fx\n", reference, $4, speedup, vs_base
    if (vs_base >= 1.03) print "PRODUCT_SIM_AB_ADAPTIVE_CANDIDATE_ONLY: >=1.03x vs 5.024 baseline, requires exact SD1 evidence for longrun"
    else print "PRODUCT_SIM_AB_ADAPTIVE_NO_PROMOTION"
  }
' "$results"

if (( sd1 && ! sd1_transport )); then
  # A candidate is eligible for the MANUAL SD1 runner only while every
  # fingerprinted model input and each independently checked media/firmware
  # SHA still matches. Never interpret the short timeout as a Linux login.
  # In particular the immutable SD image may have been replaced during a
  # multi-profile run: the first preflight alone is not sufficient evidence.
  AETHERCORE_VERILATOR_VERSION=5.024 SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_sd1_manual.sh preflight
  state_sha="$(sha256sum "$out/verilator-5.024-reference.state" | awk '{print $1}')"
  # Reuse the exact normalized flags from the first model fingerprint.
  eligible_reference="$(awk -F '\t' 'NR==2 {base=$4} NR==3 {print (base > 0 && $4/base >= 1.03 ? 1 : 0)}' "$results")"
  eligible_adaptive="$(awk -F '\t' 'NR==2 {base=$4} NR==4 {print (base > 0 && $4/base >= 1.03 ? 1 : 0)}' "$results")"
  [[ "$eligible_reference" =~ ^[01]$ && "$eligible_adaptive" =~ ^[01]$ ]] || {
    echo 'Product SD1 A/B failed to calculate bounded candidate speedup eligibility' >&2
    exit 10
  }
  identity_ref="$(AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_cache.sh fingerprint "$out" "$top" "$elaborate" "$flags")"
  identity_adaptive="$(AETHERCORE_VERILATOR_VERSION=5.054 SIM_SETTLE_MODE=adaptive \
    bash tools/ci/product_sim_cache.sh fingerprint "$out" "$top" "$elaborate" "$flags")"
  identity_baseline="$(AETHERCORE_VERILATOR_VERSION=5.024 SIM_SETTLE_MODE=reference \
    bash tools/ci/product_sim_cache.sh fingerprint "$out" "$top" "$elaborate" "$flags")"
  [[ "$identity_baseline" == "$ab_start_baseline" &&
     "$identity_ref" == "$ab_start_ref" &&
     "$identity_adaptive" == "$ab_start_adaptive" &&
     "$(sha256sum "$root/tools/ci/product_sim_ab.sh" | awk '{print $1}')" == "$ab_start_script_sha" &&
     "$(sha256sum "$root/tools/ci/product_sim_sd1_manual.sh" | awk '{print $1}')" == "$sd1_start_preflight_script_sha" ]] || {
    echo 'Product SD1 A/B source/toolchain or A/B runner changed during elaboration, build or comparison; refuse stale promotion' >&2
    exit 10
  }
  evidence="$out/sd1-accelerator-qualification.txt"
  {
    printf 'qualification=PRODUCT_SD1_SHORT_AB_IDENTICAL_NOT_DEBIAN_PASS\n'
    printf 'compared_cycles=%s\n' "$max_cycles"
    printf 'top=%s\nelaborate=%s\n' "$top" "$elaborate"
    printf 'firmware_sha256=%s\n' "$(sed -n 's/^firmware_bin_sha256=//p' "$fw_manifest" | head -n 1)"
    printf 'dtb_sha256=%s\n' "$(sed -n 's/^fdt_sha256=//p' "$fw_manifest" | head -n 1)"
    printf 'sd_image_sha256=%s\n' "$(sed -n 's/^sd_image_sha256=//p' "$sd_manifest" | head -n 1)"
    printf 'state_sha256=%s\n' "$state_sha"
    printf 'ab_script_sha256=%s\n' "$(sha256sum "$root/tools/ci/product_sim_ab.sh" | awk '{print $1}')"
    printf 'model_identity_5024_baseline=%s\n' "$identity_baseline"
    printf 'eligible_5054_reference=%s\n' "$eligible_reference"
    printf 'eligible_5054_adaptive=%s\n' "$eligible_adaptive"
    printf 'model_identity_5054_reference=%s\n' "$identity_ref"
    printf 'model_identity_5054_adaptive=%s\n' "$identity_adaptive"
  } > "$evidence.$$.tmp"
  mv -f "$evidence.$$.tmp" "$evidence"
  printf 'PRODUCT_SIM_AB_SD1_SHORT_EVIDENCE_RECORDED: v5054_reference_eligible=%s v5054_adaptive_eligible=%s evidence=%s; native Debian login NOT tested\n' \
    "$eligible_reference" "$eligible_adaptive" "$evidence"
fi

if (( sd1_transport )); then
  # Never certify a reset-only comparison as an SD transport check. All three
  # real RTL simulators must have executed the same actual SPI/DDR/PLIC probe.
  for profile in 5.024:reference 5.054:reference 5.054:adaptive; do
    label="verilator-${profile%%:*}-${profile#*:}"
    log="$out/logs/$label.run.log"
    grep -Fq 'PRODUCT_SD1_INDEPENDENT_DTB_READY address=0x87f00000' "$log" || {
      echo "SD1 transport $label missing independent DDR DTB" >&2; exit 11;
    }
    grep -Fq 'PRODUCT_SD1_SD_IMAGE_READY sectors=131072 partition1_lba=2048' "$log" || {
      echo "SD1 transport $label missing valid 64MiB media" >&2; exit 11;
    }
    grep -Fq 'SD1 TRANSPORT INDEPENDENT DDR FDT OK' "$log" || {
      echo "SD1 transport $label did not read independent FDT from physical DDR" >&2; exit 11;
    }
    grep -Fq 'SD1 TRANSPORT SPI PLIC11 CMD17 OK' "$log" || {
      echo "SD1 transport $label did not observe real SPI, MBR read and PLIC11" >&2; exit 11;
    }
    grep -Eq 'sd-cmds=([6-9]|[1-9][0-9]+) sd-reads=[1-9][0-9]* .*sd-last-cmd=17 sd-init=1' "$log" || {
      echo "SD1 transport $label did not finish CMD0/CMD8/CMD55/ACMD41/CMD58/CMD17 and read a sector" >&2; exit 11;
    }
  done
  bash tools/ci/product_sd1_transport_short_build.sh preflight
  [[ "$(sha256sum "$fw_manifest" | awk '{print $1}')" == "$sd1_start_manifest_sha" &&
     "$(sha256sum "$root/tools/ci/product_sd1_transport_short_build.sh" | awk '{print $1}')" == "$sd1_start_preflight_script_sha" &&
     "$(sha256sum "$manifest_dtb" | awk '{print $1}')" == "$sd1_start_dtb_sha" &&
     "$(sha256sum "$manifest_sd" | awk '{print $1}')" == "$sd1_start_media_sha" ]] || {
    echo 'SD1 transport inputs changed during same-cycle A/B' >&2; exit 10
  }
  [[ ! -e "$out/sd1-accelerator-qualification.txt" ]] || {
    echo 'Refusing ambiguous transport-only directory containing Debian promotion evidence' >&2; exit 10
  }
  evidence="$out/sd1-transport-qualification.txt"
  {
    printf 'qualification=PRODUCT_SD1_TRANSPORT_SHORT_AB_IDENTICAL_NOT_LINUX_NOT_G5_ELIGIBLE\n'
    printf 'compared_cycles=%s\ntop=%s\nelaborate=%s\n' "$max_cycles" "$top" "$elaborate"
    printf 'firmware_sha256=%s\n' "$ab_start_fw_sha"
    printf 'dtb_sha256=%s\nsd_image_sha256=%s\n' "$sd1_start_dtb_sha" "$sd1_start_media_sha"
    printf 'input_manifest_sha256=%s\n' "$sd1_start_manifest_sha"
    printf 'state_sha256=%s\n' "$(sha256sum "$out/verilator-5.024-reference.state" | awk '{print $1}')"
    printf 'ab_script_sha256=%s\n' "$ab_start_script_sha"
    printf 'model_identity_5024_baseline=%s\n' "$ab_start_baseline"
    printf 'model_identity_5054_reference=%s\n' "$ab_start_ref"
    printf 'model_identity_5054_adaptive=%s\n' "$ab_start_adaptive"
    printf 'sd_spi_plic11_probe=PASS\nlinux_pid1=NOT_RUN\ndebian_login=NOT_RUN\ng5_unlock=FORBIDDEN\n'
  } > "$evidence.$$.tmp"
  mv -f "$evidence.$$.tmp" "$evidence"
  printf 'PRODUCT_SIM_AB_SD1_TRANSPORT_SHORT_PASS: %s; NOT Debian, NOT Linux PID1, NOT G5 qualification\n' "$evidence"
fi
