#!/usr/bin/env bash
set -euo pipefail

# No RTL generation and no guest execution: test only the fail-closed
# same-cycle Debian timeout comparator used before enabling 5.054 long runs.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT

line='L32_OPENSBI_TIMEOUT cycles=30000000 commits=314159 uart-bytes=21 uart-fingerprint=0xa5 banner=1 milestone=0 interrupts=2 stip=1 seip=0 ram-fingerprint=0xc3 recent-pcs=0x80200000,0x80200004'
printf '%s\n' "$line" > "$tmp/baseline.log"
printf '%s\n' "$line" > "$tmp/candidate.log"
env AETHERCORE_G5_AB_ROOT="$tmp/qual" \
  bash "$ROOT/tools/ci/product_g5_short_ab.sh" compare "$tmp/baseline.log" "$tmp/candidate.log" \
  > "$tmp/positive.txt"
grep -Fq 'PRODUCT_G5_SHORT_AB_STATE_IDENTICAL cycles=30000000' "$tmp/positive.txt"

printf '%s\n' "${line/ram-fingerprint=0xc3/ram-fingerprint=0xc4}" > "$tmp/candidate.log"
if env AETHERCORE_G5_AB_ROOT="$tmp/qual" \
  bash "$ROOT/tools/ci/product_g5_short_ab.sh" compare "$tmp/baseline.log" "$tmp/candidate.log" \
  > "$tmp/divergent.txt" 2>&1; then
  echo 'PRODUCT_G5_AB_SELFTEST_FAIL divergent RAM state was accepted' >&2
  exit 1
fi
grep -Fq 'Debian Top diverged at the same RTL cycle' "$tmp/divergent.txt"

printf '%s\n' "${line/cycles=30000000/cycles=29999999}" > "$tmp/candidate.log"
if env AETHERCORE_G5_AB_ROOT="$tmp/qual" \
  bash "$ROOT/tools/ci/product_g5_short_ab.sh" compare "$tmp/baseline.log" "$tmp/candidate.log" \
  > "$tmp/wrong-cycle.txt" 2>&1; then
  echo 'PRODUCT_G5_AB_SELFTEST_FAIL wrong cycle was accepted' >&2
  exit 1
fi
grep -Fq 'missing exact-cycle Debian RTL timeout state' "$tmp/wrong-cycle.txt"

printf '%s\n' "${line/ uart-fingerprint=0xa5/}" > "$tmp/candidate.log"
if env AETHERCORE_G5_AB_ROOT="$tmp/qual" \
  bash "$ROOT/tools/ci/product_g5_short_ab.sh" compare "$tmp/baseline.log" "$tmp/candidate.log" \
  > "$tmp/uart-diff.txt" 2>&1; then
  echo 'PRODUCT_G5_AB_SELFTEST_FAIL missing UART fingerprint was accepted' >&2
  exit 1
fi

echo 'PRODUCT_G5_AB_SELFTEST_PASS positive=1 divergent_ram=1 wrong_cycle=1 missing_uart=1 guest_cycles=0'
