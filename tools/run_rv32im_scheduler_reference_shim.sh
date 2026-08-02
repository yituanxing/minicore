#!/usr/bin/env bash
set -euo pipefail

simulator="${1:?usage: run_rv32im_scheduler_reference_shim.sh SIM IMAGE OUTPUT_DIR}"
image="${2:?usage: run_rv32im_scheduler_reference_shim.sh SIM IMAGE OUTPUT_DIR}"
output_dir="${3:?usage: run_rv32im_scheduler_reference_shim.sh SIM IMAGE OUTPUT_DIR}"
reference="$output_dir/rv32-reference-shim.so"

mkdir -p "$output_dir/logs"

g++ -std=c++20 -O2 -fPIC -shared \
  sim/rv32_reference_shim.cpp \
  -o "$reference"
file "$reference" > "$output_dir/reference-shim.file.txt"
sha256sum "$reference" > "$output_dir/reference-shim.sha256"

for stall in 0 3 4 5 7 11; do
  stall_args=()
  if [[ "$stall" != "0" ]]; then
    stall_args=(--stall-period "$stall")
  fi

  trace="$output_dir/logs/trace-$stall.log"
  "$simulator" "$image" \
    --max-cycles 30000 --self-check-exit --commit-trace \
    "${stall_args[@]}" \
    > "$trace" 2>&1

  mtime_values="$(python3 - "$trace" <<'PY'
import re
import sys

values = []
for line in open(sys.argv[1], encoding="utf-8"):
    if "pc=0x8000010c " in line or "pc=0x80000350 " in line:
        match = re.search(r"data=(0x[0-9a-fA-F]+)", line)
        if match:
            values.append(match.group(1))
if len(values) != 8:
    raise SystemExit(f"expected eight mtime reads, observed {values}")
print(",".join(values))
PY
)"
  printf '%s\n' "$mtime_values" > "$output_dir/logs/mtime-$stall.txt"

  AETHERCORE_SHIM_MTIME_VALUES="$mtime_values" \
    "$simulator" "$image" \
      --max-cycles 30000 --self-check-exit \
      "${stall_args[@]}" \
      --difftest "$reference" \
      > "$output_dir/logs/difftest-$stall.log" 2>&1

done

grep -q '^PASS: self-check exit=0 after 1509 cycles, 1345 committed instructions, difftest=1345, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' "$output_dir/logs/difftest-0.log"
grep -q '^PASS: self-check exit=0 after 1671 cycles, 1252 committed instructions, stall-period=3, difftest=1252, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' "$output_dir/logs/difftest-3.log"
grep -q '^PASS: self-check exit=0 after 1639 cycles, 1263 committed instructions, stall-period=4, difftest=1263, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' "$output_dir/logs/difftest-4.log"
grep -q '^PASS: self-check exit=0 after 1578 cycles, 1278 committed instructions, stall-period=5, difftest=1278, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' "$output_dir/logs/difftest-5.log"
grep -q '^PASS: self-check exit=0 after 1589 cycles, 1326 committed instructions, stall-period=7, difftest=1326, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' "$output_dir/logs/difftest-7.log"
grep -q '^PASS: self-check exit=0 after 1553 cycles, 1334 committed instructions, stall-period=11, difftest=1334, zicsr-shadow=53, mret-shadow=8, interrupt-shadow=8$' "$output_dir/logs/difftest-11.log"

set +e
AETHERCORE_SHIM_MTIME_VALUES="$(cat "$output_dir/logs/mtime-5.txt")" \
AETHERCORE_RV32_DIFFTEST_INJECT_MISMATCH_AT=152 \
  "$simulator" "$image" \
    --max-cycles 30000 --self-check-exit --stall-period 5 \
    --difftest "$reference" \
    > "$output_dir/logs/negative-event-152.log" 2>&1
status=$?
set -e

test "$status" -ne 0
grep -q 'RV32 timer DiffTest mismatch after 152 matched events' \
  "$output_dir/logs/negative-event-152.log"
grep -q 'x31' "$output_dir/logs/negative-event-152.log"

cat > "$output_dir/result.txt" <<'EOF'
status=PASS
reference=independent-rv32im-shim
timer_input=record-and-replay
stall_periods=0,3,4,5,7,11
zicsr_shadow_per_run=53
mret_shadow_per_run=8
interrupt_shadow_per_run=8
negative_event=152
EOF
cat "$output_dir/result.txt"
