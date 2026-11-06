#!/usr/bin/env bash
set -euo pipefail

# Repeat the original G5 D2 900M golden replay and continue its proven 950M
# checkpoint by only 5M cycles. This MUST NOT build RTL or cold-boot Linux.
# Inputs: the two exact, historical GitHub Actions artifact ZIPs, and a NEW dir.
#   bash tools/ci/product_g5_d2_archive_probe.sh 900m.zip 950m.zip /tmp/new-dir

[[ "$#" == 3 ]] || {
  echo "usage: $0 ORIGINAL_900M_ARTIFACT_ZIP BRIDGE_950M_ARTIFACT_ZIP NEW_OUTPUT_DIR" >&2
  exit 2
}
ORIGINAL="$(realpath -- "$1")"
BRIDGE="$(realpath -- "$2")"
OUT="$3"
[[ -f "$ORIGINAL" && -f "$BRIDGE" && ! -e "$OUT" && ! -L "$OUT" ]] || {
  echo "G5_ARCHIVE_PROBE_REJECTED: missing archive or output path already occupied" >&2
  exit 2
}

SOURCE_SHA=99a5c86e99f52ed12f95b304635d77613c9505c4
ORIGINAL_MODEL_SHA=aa35c80024238420bea3976db677db2e0ef028aad9800482926b926e50155b71
REBUILT_MODEL_SHA=5ed1e31ea3c1a5dc8761e4ffb33a35a48db412e359521efa3e3eb408cfbae43d
FW_SHA=d525de6a58422de93da6de5312d38867611153da94c580bca31f90ebf13eff2c
VMLINUX_SHA=6f5afbdc6fabd14819219d4928289c163d73f3aee9b38c72c4c866b1c50c5c69
SYSTEM_MAP_SHA=ebfdf95aad4a0a16c5665f3ceaef461b0153b9f2f47eab3bf45f2b478217eb64
PREFIX=minicore/minicore/build
fail() { echo "G5_ARCHIVE_PROBE_REJECTED: $*" >&2; exit 3; }
need() { [[ -s "$OUT/$1" ]] || fail "missing extracted $1"; }
extract() { unzip -j -q "$1" "$PREFIX/$2" -d "$OUT"; }

mkdir -p -- "$OUT"
extract "$ORIGINAL" product-g5-long-forensic/persistent.pre.host
extract "$ORIGINAL" product-g5-long-forensic/persistent.pre.rtl
extract "$ORIGINAL" product-g5-long-forensic/persistent.restored.host
extract "$ORIGINAL" product-g5-long-forensic/persistent.restored.rtl
extract "$ORIGINAL" product-g5-long-forensic/pre.sha256
extract "$ORIGINAL" product-g5-long-forensic/restored.state
extract "$BRIDGE" product-g5-long-forensic/persistent.bridge-950m.host
extract "$BRIDGE" product-g5-long-forensic/persistent.bridge-950m.rtl
extract "$BRIDGE" product-g5-long-forensic/bridge-950m.sha256
extract "$BRIDGE" product-g5-long-forensic/bridge-identity.txt
extract "$BRIDGE" product-g5-persistent-checkpoint/product-g5-checkpoint
extract "$BRIDGE" rv64-debian-linux/obj/vmlinux
extract "$BRIDGE" rv64-debian-linux/obj/System.map

for file in pre.sha256 restored.state bridge-950m.sha256 bridge-identity.txt \
    persistent.pre.host persistent.pre.rtl persistent.restored.host \
    persistent.restored.rtl persistent.bridge-950m.host \
    persistent.bridge-950m.rtl product-g5-checkpoint vmlinux System.map; do
  need "$file"
done
(
  cd "$OUT"
  sha256sum --check --status pre.sha256 || fail "900M original state SHA mismatch"
  sha256sum --check --status bridge-950m.sha256 || fail "950M bridge state SHA mismatch"
)
for proof in "source_commit=$SOURCE_SHA" \
    "archived_model_sha256=$ORIGINAL_MODEL_SHA" \
    "rebuilt_model_sha256=$REBUILT_MODEL_SHA" \
    "firmware_sha256=$FW_SHA" \
    'golden_replay_cycles=500000' 'golden_rtl_host_identical=1' \
    'snapshot_start_cycles=900000000' 'snapshot_target_cycles=950000000'; do
  grep -Fxq "$proof" "$OUT/bridge-identity.txt" || fail "bridge identity mismatch: $proof"
done
[[ "$(sha256sum "$OUT/product-g5-checkpoint" | awk '{print $1}')" == "$REBUILT_MODEL_SHA" ]] ||
  fail "archived host program SHA mismatch"
[[ "$(sha256sum "$OUT/vmlinux" | awk '{print $1}')" == "$VMLINUX_SHA" ]] ||
  fail "exact Debian vmlinux SHA mismatch"
[[ "$(sha256sum "$OUT/System.map" | awk '{print $1}')" == "$SYSTEM_MAP_SHA" ]] ||
  fail "exact Debian System.map SHA mismatch"
chmod u+x "$OUT/product-g5-checkpoint"

# Model-ABI gate: on this actual host, a 900M -> 900.5M replay must produce
# bit-identical archived RTL AND host RAM/Observer snapshots. No assumption that
# GitHub's -march=native runner binary works on another host is made.
ln -s persistent.pre.host "$OUT/golden.pre.host"
ln -s persistent.pre.rtl "$OUT/golden.pre.rtl"
SIM="$OUT/product-g5-checkpoint"
run_restore() {
  local id="$1" cycles="$2"
  timeout --signal=TERM --kill-after=10s 180s "$SIM" restore /dev/null \
    "$OUT/$id" "$ORIGINAL_MODEL_SHA" "$FW_SHA" 1800000000 \
    'Linux version 6.6.143' "$cycles" > "$OUT/$id.log" 2>&1 || {
    tail -50 "$OUT/$id.log" >&2
    fail "restore run $id failed"
  }
}
run_restore golden 500000
cmp -s "$OUT/golden.restored.rtl" "$OUT/persistent.restored.rtl" ||
  fail "golden RTL state diverged (host binary incompatible)"
cmp -s "$OUT/golden.restored.host" "$OUT/persistent.restored.host" ||
  fail "golden DDR/Observer state diverged (host binary incompatible)"
golden="$(grep '^PRODUCT_G5_PERSISTENT_CONTINUATION ' "$OUT/golden.log" | tail -1)"
grep -Fxq "$golden" "$OUT/restored.state" ||
  fail "golden counters/digests differ"
echo 'PRODUCT_G5_ARCHIVE_GOLDEN_900M_TO_9005M_PASS rtl=BIT_IDENTICAL host=BIT_IDENTICAL'

# Run ten short independent 500k process restores, not a 0->950M cold boot.
# The ordinary restore mode intentionally has no old-UART silence watchdog.
previous=persistent.bridge-950m
previous_commits=244648576
for number in $(seq 1 10); do
  id="$(printf 'probe-%02d' "$number")"
  ln -s "$previous.host" "$OUT/$id.pre.host"
  ln -s "$previous.rtl" "$OUT/$id.pre.rtl"
  run_restore "$id" 500000
  expected=$((950000000 + number * 500000))
  line="$(grep '^PRODUCT_G5_PERSISTENT_CONTINUATION ' "$OUT/$id.log" | tail -1)"
  [[ " $line " == *" cycles=$expected time=$expected "* ]] ||
    fail "wrong bounded guest cycle or clock at $id"
  commits="$(sed -n 's/.* commits=\([0-9]*\) .*/\1/p' <<< "$line")"
  [[ "$commits" =~ ^[0-9]+$ ]] && (( commits > previous_commits )) ||
    fail "CPU did not retire instructions at $id"
  need "$id.restored.host"
  need "$id.restored.rtl"
  printf 'PRODUCT_G5_ARCHIVE_950M_PROGRESS segment=%d cycles=%d commits=%s\n' \
    "$number" "$expected" "$commits"
  previous_commits="$commits"
  previous="$id.restored"
done

PY_SCRIPT="$(cd "$(dirname "$0")" && pwd)/product_g5_checkpoint_pc_report.py"
need System.map
python3 "$PY_SCRIPT" --system-map "$OUT/System.map" \
  "$OUT/persistent.bridge-950m.host" \
  "$OUT"/probe-{01..10}.restored.host > "$OUT/pc-report.txt"
cat "$OUT/pc-report.txt"
PROGRESS_SCRIPT="$(cd "$(dirname "$0")" && pwd)/product_g5_initramfs_progress.py"
python3 "$PROGRESS_SCRIPT" --vmlinux "$OUT/vmlinux" \
  --system-map "$OUT/System.map" \
  "$OUT/persistent.pre.host" \
  "$OUT/persistent.bridge-950m.host" \
  "$OUT/probe-10.restored.host" > "$OUT/initramfs-progress.txt"
cat "$OUT/initramfs-progress.txt"
printf 'PRODUCT_G5_ARCHIVE_950M_TO_955M_PROBE_PASS original_gold=1 bounded_cycles=5000000 final_commits=%s final_evidence=%s not_Debian_PID1_qualification=1\n' \
  "$previous_commits" "$OUT/pc-report.txt"
