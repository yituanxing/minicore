#!/usr/bin/env bash
# Bounded, zero-guest-cycle C1 Host/RTL inspection with disposable corruption
# probes. Run only after a fresh, SHA-sealed C1 prefix was created on this
# runner. Never alter the signed source media or the original C1 checkpoint.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
[[ $# == 1 ]] || { echo 'usage: product_sd1_c1_inspect_smoke.sh CHECKPOINT_DIR' >&2; exit 2; }
SOURCE="$(realpath "$1")"
[[ -d "$SOURCE" && -s "$SOURCE/.c1-ready" &&
   -s "$SOURCE/persistent.pre.host" && -s "$SOURCE/persistent.pre.rtl" ]] || {
  echo 'C1_INSPECT_SMOKE_REJECTED: complete source checkpoint required' >&2
  exit 3
}

inspected="$(bash tools/ci/product_sd1_c1_checkpoint.sh inspect "$SOURCE")" || {
  echo 'C1_INSPECT_SMOKE_REJECTED: zero-cycle live Host/RTL inspector failed' >&2
  exit 3
}
[[ "$(grep -Ec '^PRODUCT_SD1_C1_HOST_RTL_INSPECTED ' <<< "$inspected")" == 1 &&
   "$inspected" == *' no_guest_cycles=1 not_debian_pass=1'* ]] || {
  echo 'C1_INSPECT_SMOKE_REJECTED: no authenticated no-cycle observation' >&2
  exit 3
}

# Use a fresh, precisely scoped temporary directory. Corruption applies only
# to copies; never truncate the source that may be needed for later relay.
TMP_ROOT="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/c1-inspect-smoke.XXXXXXXX")"
cleanup() { [[ -d "$TMP_ROOT" ]] && rm -r -- "$TMP_ROOT"; }
trap cleanup EXIT
for corrupt in host rtl; do
  clone="$TMP_ROOT/$corrupt"
  mkdir "$clone"
  cp "$SOURCE/.c1-ready" "$clone/.c1-ready"
  cp --reflink=auto "$SOURCE/persistent.pre.host" "$clone/persistent.pre.host"
  cp --reflink=auto "$SOURCE/persistent.pre.rtl" "$clone/persistent.pre.rtl"
  target="$clone/persistent.pre.$corrupt"
  old_size="$(stat -c%s "$target")"
  (( old_size > 1 )) || { echo 'C1_INSPECT_SMOKE_REJECTED: empty clone' >&2; exit 3; }
  truncate -s "$((old_size - 1))" "$target"
  if bash tools/ci/product_sd1_c1_checkpoint.sh inspect "$clone" > "$clone/reject.log" 2>&1; then
    echo "C1_INSPECT_SMOKE_REJECTED: corrupted $corrupt copy was accepted" >&2
    exit 3
  fi
  grep -Fq 'SHA256/regular file identity differs' "$clone/reject.log" || {
    echo "C1_INSPECT_SMOKE_REJECTED: wrong rejection reason for $corrupt" >&2
    exit 3
  }
done

# The source is still independently verified against its manifest after both
# disposable negative tests; this is NOT a Debian service/login claim.
bash tools/ci/product_sd1_c1_checkpoint.sh verify "$SOURCE" >/dev/null
echo 'PRODUCT_SD1_C1_INSPECT_SMOKE_PASS zero_guest_cycles=1 corrupted_host_rejected=1 corrupted_rtl_rejected=1 source_unchanged=1 not_debian_pass=1'
