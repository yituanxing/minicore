#!/usr/bin/env bash
set -euo pipefail

# G3-sized real Product RTL qualification before the expensive Linux PID1 run.
# This executes the *unchanged* OpenSBI 1.6 + S-mode UART payload on precisely
# the same Product OpenSbi TOP that G4 uses, with the same 30M-cycle boundary
# in each simulator profile. There is no Debian/SDHC work in this gate.
#
# Requires the existing pinned G3 firmware to have been built separately:
#   AETHERCORE_ALLOW_PRODUCT_AB=1 \
#     bash tools/ci/product_g3_short_ab.sh
# Optionally set G3_RESULT to the result.txt produced by an earlier G3 build.
# No source push or ordinary CI event invokes this costly three-model A/B.

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
[[ "${AETHERCORE_ALLOW_PRODUCT_AB:-0}" == 1 ]] || {
  echo 'G3 short Product A/B requires AETHERCORE_ALLOW_PRODUCT_AB=1' >&2
  exit 2
}
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" &&
   -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || {
  echo 'G3 small-program A/B must not inherit Debian SD1 media or independent DTB' >&2
  exit 2
}
[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" &&
   -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] || {
  echo 'G3 OpenSBI short test cannot use Debian SDHC or independent DTB inputs' >&2
  exit 2
}

result="${G3_RESULT:-$root/build/rv64-opensbi-first-exec/result.txt}"
[[ -s "$result" ]] &&
  grep -Fxq 'RV64_OPENSBI_FIRST_EXEC_BUILD: status=PASS' "$result" &&
  grep -Fxq 'opensbi_version=1.6' "$result" &&
  grep -Fxq 'xlen=64' "$result" &&
  grep -Fxq 'milestone=RV64 OpenSBI S-mode payload PASS' "$result" || {
    echo "Missing qualified G3 OpenSBI/S-mode UART build manifest: $result" >&2
    exit 3
  }
firmware="$(sed -n 's/^firmware_bin=//p' "$result" | head -n 1)"
[[ -n "$firmware" && -s "$firmware" ]] || {
  echo 'G3 build manifest does not point to an existing fw_payload.bin' >&2
  exit 3
}
# G3 builder writes checksums for OpenSBI, S-mode payload, DTB and firmware.
# Do not accept a stale result.txt paired with an altered firmware binary.
checksums="$(dirname "$result")/evidence/sha256.txt"
[[ -s "$checksums" ]] && sha256sum --check --status "$checksums" || {
  echo 'G3 firmware/payload/DTB do not match their frozen build checksums' >&2
  exit 3
}

out="$root/build/product-g3-short-ab"
# An interrupted/new short comparison must not leave an older qualification
# eligible for the later G4 PID1 runner while new evidence is incomplete.
mkdir -p "$out"
: > "$out/inprogress"
top=AetherCoreProductOpenSbiRV64SimTop
elaborate=aethercore.sim.ElaborateProductOpenSbiRV64SimTop
cycles=30000000
# Exactly the G4 OpenSBI C++ flags. The same-source compiled models must also
# carry the same cache identity when reused by the subsequent PID1 probe.
flags="-std=c++20 -O3 -march=native -I$root/sim/product_rv64_opensbi_shim -I$root/sim"
unset VERILATOR_ROOT
firmware_sha_before="$(sha256sum "$firmware" | awk '{print $1}')"
result_sha_before="$(sha256sum "$result" | awk '{print $1}')"
ab_script_sha_before="$(sha256sum "$root/tools/ci/product_sim_ab.sh" | awk '{print $1}')"
runner_sha_before="$(sha256sum "$root/tools/ci/product_g3_short_ab.sh" | awk '{print $1}')"

TOP="$top" ELABORATE_MAIN="$elaborate" FW_BIN="$firmware" \
  MAX_CYCLES="$cycles" OUT_ROOT="$out" \
  bash tools/ci/product_sim_ab.sh

[[ "$firmware_sha_before" == "$(sha256sum "$firmware" | awk '{print $1}')" &&
   "$result_sha_before" == "$(sha256sum "$result" | awk '{print $1}')" &&
   "$ab_script_sha_before" == "$(sha256sum "$root/tools/ci/product_sim_ab.sh" | awk '{print $1}')" &&
   "$runner_sha_before" == "$(sha256sum "$root/tools/ci/product_g3_short_ab.sh" | awk '{print $1}')" ]] || {
  echo 'G3 firmware, manifest or qualification scripts changed during A/B' >&2
  exit 8
}

# A successful compare of a reset-only prefix is not a small-program proof.
# G3 must actually execute the real OpenSBI/S-mode payload, produce the UART
# success marker, retire real instructions and still match at the same cycle.
for profile in 5.024:reference 5.054:reference 5.054:adaptive; do
  version="${profile%%:*}"
  mode="${profile#*:}"
  label="verilator-$version-$mode"
  log="$out/logs/$label.run.log"
  state="$out/$label.state"
  [[ -s "$log" && -s "$state" ]] &&
    grep -Fq 'OpenSBI v1.6' "$log" &&
    grep -Fq 'RV64 OpenSBI S-mode payload PASS' "$log" &&
    grep -Eq '^L32_OPENSBI_TIMEOUT cycles=30000000 commits=[1-9][0-9]*' "$state" || {
      echo "G3 $label did not execute real OpenSBI + S-mode UART by 30M cycles" >&2
      exit 9
    }
done
cmp -s "$out/verilator-5.024-reference.state" "$out/verilator-5.054-reference.state" &&
  cmp -s "$out/verilator-5.024-reference.state" "$out/verilator-5.054-adaptive.state" || {
    echo 'G3 Product three-profile timeout state diverged' >&2
    exit 9
  }

# Preserve exact-model and executable identities with the qualification, so
# G4 can reuse the already compiled models rather than building three again.
evidence="$out/small-qualification.txt"
results="$out/results.tsv"
[[ -s "$results" ]] || { echo 'Missing three-profile A/B timing results' >&2; exit 10; }
selection="$(LC_ALL=C awk -F '\t' '
  NR == 2 {
    if ($1 != "5.024" || $2 != "reference" || $4 <= 0) exit 1
    base=$4; winner="5.024:reference"; best=base
  }
  NR == 3 {
    if ($1 != "5.054" || $2 != "reference" || $4 <= 0) exit 1
    if ($4/base >= 1.03 && $4 > best) {winner="5.054:reference"; best=$4}
    qualified_ref=($4/base >= 1.03 ? 1 : 0)
  }
  NR == 4 {
    if ($1 != "5.054" || $2 != "adaptive" || $4 <= 0) exit 1
    if ($4/base >= 1.03 && $4 > best) {winner="5.054:adaptive"; best=$4}
    qualified_adaptive=($4/base >= 1.03 ? 1 : 0)
  }
  END {
    if (NR != 4 || base <= 0 || best <= 0) exit 1
    printf "%s %s %d %d\n", winner, best, qualified_ref, qualified_adaptive
  }
' "$results")" || {
  echo 'Invalid or incomplete G3 Product A/B timing table' >&2
  exit 10
}
read -r selected_profile selected_cps eligible_reference eligible_adaptive <<< "$selection"
state_sha="$(sha256sum "$out/verilator-5.024-reference.state" | awk '{print $1}')"

{
  printf 'qualification=PRODUCT_G3_SHORT_AB_IDENTICAL_NOT_LINUX_PASS\n'
  printf 'top=%s\nelaborate=%s\n' "$top" "$elaborate"
  printf 'compared_cycles=%s\n' "$cycles"
  printf 'g3_result_sha256=%s\n' "$result_sha_before"
  printf 'firmware_sha256=%s\n' "$firmware_sha_before"
  printf 'state_sha256=%s\n' "$state_sha"
  printf 'ab_script_sha256=%s\n' "$ab_script_sha_before"
  printf 'qualification_script_sha256=%s\n' "$runner_sha_before"
  printf 'eligible_5054_reference=%s\n' "$eligible_reference"
  printf 'eligible_5054_adaptive=%s\n' "$eligible_adaptive"
  printf 'selected_profile=%s\nselected_cps=%s\n' "$selected_profile" "$selected_cps"
  for profile in 5.024:reference 5.054:reference 5.054:adaptive; do
    version="${profile%%:*}"
    mode="${profile#*:}"
    if [[ "$version" == 5.024 ]]; then
      key=5024_baseline
    else
      key="5054_$mode"
    fi
    model="$out/model-verilator-$version-$mode"
    binary="$model/obj/V$top"
    [[ -x "$binary" ]] || {
      echo "Missing reusable G3 model: $binary" >&2
      exit 10
    }
    identity="$(AETHERCORE_VERILATOR_VERSION="$version" SIM_SETTLE_MODE="$mode" \
      bash tools/ci/product_sim_cache.sh fingerprint "$model" "$top" "$elaborate" "$flags")"
    AETHERCORE_VERILATOR_VERSION="$version" SIM_SETTLE_MODE="$mode" \
      bash tools/ci/product_sim_cache.sh verify "$model" "$top" "$elaborate" "$flags" > /dev/null || {
        echo "G3 Product $profile compiled-model identity is missing or stale" >&2
        exit 10
      }
    printf 'model_identity_%s=%s\n' "$key" "$identity"
    printf 'binary_sha_%s=%s\n' "$key" "$(sha256sum "$binary" | awk '{print $1}')"
  done
} > "$evidence.$$.tmp"
mv -f "$evidence.$$.tmp" "$evidence"
rm -f "$out/inprogress"
printf 'PRODUCT_G3_SHORT_AB_QUALIFIED: selected=%s cycles=%s cps=%s; native Linux PID1 not yet tested\n' \
  "$selected_profile" "$cycles" "$selected_cps"
printf 'qualification=%s\n' "$evidence"
