#!/usr/bin/env bash
set -euo pipefail

# Bounded G4 Linux 6.6.143 *early* smoke on the exact Product G3 GSIM model.
# G3 and G4 use AetherCoreProductOpenSbiRV64SimTop; only the RAM firmware
# changes. No Product RTL re-elaboration, GSIM regeneration, native recompile,
# Verilator G4 same-cycle A/B, Debian, SD card, full PID1, or CI auto-trigger.
#
#   bash tools/ci/product_gsim_g4_early.sh preflight
#   AETHERCORE_ALLOW_PRODUCT_GSIM_G4_EARLY=1 \
#     bash tools/ci/product_gsim_g4_early.sh run
#
# The default 100M-cycle budget fits the existing 285s CI wall cap; an
# explicit diagnostic override remains bounded to 50M..200M cycles.
# Passing means that GSIM executed a Linux-banner-bearing prefix *only*.
# The verified 30M G3 A/B is retained as transport evidence; it is NOT a
# claim that the G4 Linux state matches Verilator at every cycle.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
case "$ACTION" in preflight|run) ;; *)
  echo 'usage: product_gsim_g4_early.sh preflight|run' >&2
  exit 2
  ;;
esac

fail() { echo "PRODUCT_GSIM_G4_EARLY_REJECTED: $*" >&2; exit 3; }
sha_file() { sha256sum "$1" | awk '{print $1}'; }
field() {
  local key="$1" file="$2" value
  [[ -s "$file" ]] || fail "missing evidence file $file"
  value="$(sed -n "s/^${key}=//p" "$file" | head -n 1)"
  [[ -n "$value" ]] || fail "missing $key in $file"
  printf '%s\n' "$value"
}
has_field() { grep -Fxq "$1=$2" "$3" || fail "stale $1 in $3"; }
check_integer() {
  local value="$1" low="$2" high="$3" label="$4"
  [[ "$value" =~ ^[0-9]+$ ]] || fail "$label must be a decimal integer"
  (( value >= low && value <= high )) || fail "$label out of range $low..$high"
}
single_record() {
  local prefix="$1" log="$2"
  local count
  count="$(grep -ac "^${prefix} " "$log" || true)"
  [[ "$count" == 1 ]] || fail "expected exactly one $prefix record, saw $count"
  grep -a "^${prefix} " "$log"
}
token() {
  local line="$1" key="$2" chunk
  chunk="$(printf '%s\n' "$line" | tr ' ' '\n' | sed -n "s/^${key}=//p")"
  [[ -n "$chunk" ]] || fail "terminal record missing $key"
  printf '%s\n' "$chunk"
}

[[ -z "${AETHERCORE_PRODUCT_SD_IMAGE:-}" &&
   -z "${AETHERCORE_PRODUCT_DTB_IMAGE:-}" ]] ||
  fail 'Debian/SD1 media are not valid inputs to the G4 RAM-initramfs proof'

EARLY_CYCLES="${AETHERCORE_PRODUCT_GSIM_G4_EARLY_CYCLES:-100000000}"
WALL_SECONDS="${AETHERCORE_PRODUCT_GSIM_G4_WALL_SECONDS:-285}"
check_integer "$EARLY_CYCLES" 50000000 200000000 AETHERCORE_PRODUCT_GSIM_G4_EARLY_CYCLES
check_integer "$WALL_SECONDS" 30 285 AETHERCORE_PRODUCT_GSIM_G4_WALL_SECONDS

G3_ROOT="$ROOT/build/product-gsim-g3-ab"
G3_EVIDENCE="$G3_ROOT/qualification.txt"
GSIM_MODEL_ROOT="${PRODUCT_GSIM_OUT:-$ROOT/build/product-gsim-bridge}"
GSIM="$GSIM_MODEL_ROOT/product-gsim-prefix"
GSIM_IDENTITY="$GSIM_MODEL_ROOT/build-identity.txt"
FW_RESULT="$ROOT/build/rv64-minimal-init-boot/result.txt"
FW_SHA_LIST="$ROOT/build/rv64-minimal-init-boot/evidence/sha256.txt"
RUN_ROOT="$ROOT/build/product-gsim-g4-early"
MILESTONE='Linux version 6.6.143'

[[ ! -e "$G3_ROOT/inprogress" ]] || fail 'G3 A/B still running or failed'
[[ -s "$G3_EVIDENCE" ]] || fail 'missing genuine G3 GSIM/Verilator comparison ticket'
has_field qualification PRODUCT_GSIM_G3_IDENTICAL_NOT_LINUX_PASS "$G3_EVIDENCE"
has_field top AetherCoreProductOpenSbiRV64SimTop "$G3_EVIDENCE"
has_field cycles 30000000 "$G3_EVIDENCE"
has_field promotion_eligible 1 "$G3_EVIDENCE"
[[ -x "$GSIM" && -s "$GSIM_IDENTITY" ]] ||
  fail 'missing cached Product GSIM binary / generator identity; rebuild separately'
GSIM_SHA="$(sha_file "$GSIM")"
GSIM_ID_SHA="$(sha_file "$GSIM_IDENTITY")"
has_field gsim_sha256 "$GSIM_SHA" "$G3_EVIDENCE"
has_field gsim_identity_sha256 "$GSIM_ID_SHA" "$G3_EVIDENCE"
has_field model_sha256 "$GSIM_SHA" "$GSIM_IDENTITY"
has_field gsim_revision 45a73d6afb9b1893781cfb57ccd54096987bb23d "$GSIM_IDENTITY"
G3_SHA="$(sha_file "$G3_EVIDENCE")"

[[ -s "$FW_RESULT" && -s "$FW_SHA_LIST" ]] ||
  fail 'missing frozen G4 Linux/OpenSBI firmware manifests'
grep -Fxq 'RV64_MINIMAL_INIT_PAYLOAD_BUILD_RESULT: status=PASS' "$FW_RESULT" ||
  fail 'G4 firmware builder did not report PASS'
has_field spi_sd 0 "$FW_RESULT"
has_field linux_image "$ROOT/build/rv64-linux-initramfs/obj/arch/riscv/boot/Image" "$FW_RESULT"
FW_BIN="$(field firmware_bin "$FW_RESULT")"
[[ -s "$FW_BIN" ]] || fail "missing G4 Linux fw_payload $FW_BIN"
sha256sum --check --status "$FW_SHA_LIST" ||
  fail 'frozen Linux/initramfs/OpenSBI bytes differ from manifest'
FW_SHA="$(sha_file "$FW_BIN")"
[[ "$FW_SHA" != "$(field firmware_sha256 "$G3_EVIDENCE")" ]] ||
  fail 'G4 firmware unexpectedly equals the G3 S-mode test payload'

if [[ "$ACTION" == preflight ]]; then
  printf 'PRODUCT_GSIM_G4_PREFLIGHT_PASS top=AetherCoreProductOpenSbiRV64SimTop cycles=%s wall_cap_s=%s gsim_sha256=%s firmware_sha256=%s reuse=G3_model no_debian=1 no_pid1=1\n' \
    "$EARLY_CYCLES" "$WALL_SECONDS" "$GSIM_SHA" "$FW_SHA"
  exit 0
fi
[[ "${AETHERCORE_ALLOW_PRODUCT_GSIM_G4_EARLY:-0}" == 1 ]] ||
  fail 'explicit AETHERCORE_ALLOW_PRODUCT_GSIM_G4_EARLY=1 required for the GSIM G4 guest run'

mkdir -p "$RUN_ROOT"
touch "$RUN_ROOT/inprogress"
# Never leave a former PASS ticket looking valid after a new incomplete run.
rm -f "$RUN_ROOT/qualification.txt"
LOG="$RUN_ROOT/linux-early.run.log"
start="$(date +%s%N)"
set +e
timeout --signal=TERM --kill-after=5s "${WALL_SECONDS}s" \
  "$GSIM" "$FW_BIN" "$EARLY_CYCLES" '__PRODUCT_G4_EARLY_NEVER_MATCH__' \
  > "$LOG" 2>&1
rc=$?
set -e
end="$(date +%s%N)"
[[ "$rc" == 0 ]] || {
  tail -70 "$LOG" >&2
  fail "GSIM G4 early stopped rc=$rc (124/137 means hard wall cap); no Linux qualification"
}

TERMINAL="$(single_record L32_OPENSBI_TIMEOUT "$LOG")"
DETAIL="$(single_record AETHERCORE_PRODUCT_GSIM_PREFIX "$LOG")"
[[ "$(token "$TERMINAL" cycles)" == "$EARLY_CYCLES" ]] ||
  fail 'G4 early did not finish exact requested fixed-cycle window'
[[ "$(token "$DETAIL" guest_cycles)" == "$(( EARLY_CYCLES - 4 ))" ]] ||
  fail 'Product G3/G4 4-tick reset accounting has drifted'
[[ "$(token "$DETAIL" linux_banner)" == 1 &&
   "$(token "$DETAIL" opensbi_banner)" == 1 ]] ||
  fail 'actual OpenSBI/Linux UART output missing in bounded G4 prefix'
[[ "$(token "$TERMINAL" banner)" == 1 ]] || fail 'Verilator-compatible OpenSBI state marker missing'
[[ "$(token "$DETAIL" host_bridge_calls)" =~ ^[1-9][0-9]*$ &&
   "$(token "$DETAIL" host_imem_calls)" =~ ^[1-9][0-9]*$ ]] ||
  fail 'GSIM did not execute Product RAM host bridge and instruction fetch'
[[ "$(token "$TERMINAL" commits)" =~ ^[1-9][0-9]*$ ]] ||
  fail 'no genuine Product CPU retirement in early Linux'
grep -aFq "$MILESTONE" "$LOG" || fail 'unaltered Linux 6.6.143 kernel banner absent'
grep -aFq 'AETHERCORE_PRODUCT_GSIM_FIXED_CYCLE_UNQUALIFIED_REQUIRES_VERILATOR_AB' "$LOG" ||
  fail 'GSIM terminal unexpectedly claims full Linux A/B or PID1 qualification'

[[ "$(sha_file "$GSIM")" == "$GSIM_SHA" &&
   "$(sha_file "$GSIM_IDENTITY")" == "$GSIM_ID_SHA" &&
   "$(sha_file "$FW_BIN")" == "$FW_SHA" &&
   "$(sha_file "$G3_EVIDENCE")" == "$G3_SHA" ]] ||
  fail 'G3 ticket, guest model or frozen firmware changed during bounded run'

elapsed="$(awk -v a="$start" -v b="$end" 'BEGIN{printf "%.6f", (b-a)/1000000000}')"
{
  # Encode the *actual* bounded cycle count so diagnostic overrides cannot
  # masquerade as the routine 100M proof or as G4 Verilator A/B/PID1.
  printf 'qualification=PRODUCT_GSIM_G4_%s_CYCLES_LINUX_BANNER_GSIM_ONLY_NOT_VERILATOR_AB_NOT_PID1_PASS\n' "$EARLY_CYCLES"
  printf 'top=AetherCoreProductOpenSbiRV64SimTop\n'
  printf 'guest_engine=GSIM\n'
  printf 'same_cycle_evidence_scope=G3_OPEN_SBI_UART_30000000_ONLY\n'
  printf 'cycles=%s\nwall_cap_seconds=%s\nwall_seconds=%s\n' "$EARLY_CYCLES" "$WALL_SECONDS" "$elapsed"
  printf 'gsim_sha256=%s\ngsim_identity_sha256=%s\n' "$GSIM_SHA" "$GSIM_ID_SHA"
  printf 'g3_qualification_sha256=%s\nfirmware_sha256=%s\n' "$G3_SHA" "$FW_SHA"
  printf 'linux_banner=1\nopensbi_banner=1\n'
  printf 'terminal_sha256=%s\n' "$(printf '%s\n' "$TERMINAL" | sha256sum | awk '{print $1}')"
  printf 'log_sha256=%s\n' "$(sha_file "$LOG")"
} > "$RUN_ROOT/qualification.txt.$$.tmp"
mv -f "$RUN_ROOT/qualification.txt.$$.tmp" "$RUN_ROOT/qualification.txt"
rm -f "$RUN_ROOT/inprogress"
printf 'PRODUCT_GSIM_G4_LINUX_EARLY_OBSERVED cycles=%s wall_seconds=%s gsim_sha=%s (G3 A/B qualified; bounded G4 GSIM-only, no G4 A/B/PID1/Debian claim)\n' \
  "$EARLY_CYCLES" "$elapsed" "$GSIM_SHA"
