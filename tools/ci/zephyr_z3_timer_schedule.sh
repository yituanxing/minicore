#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_ROOT="${AETHERCORE_ZEPHYR_BUILD_DIR:-$ROOT/build/zephyr-stage/host-build}"
IMAGE="$BUILD_ROOT/zephyr/zephyr.bin"
Z2_BUILD="${AETHERCORE_ZEPHYR_SIM_BUILD_DIR:-$ROOT/build/zephyr-z2}"
RUNNER="$Z2_BUILD/obj/VAetherCoreSimTop"
Z3_BUILD="${AETHERCORE_ZEPHYR_Z3_BUILD_DIR:-$ROOT/build/zephyr-z3}"
LOG_DIR="$Z3_BUILD/evidence"
MAX_CYCLES="${AETHERCORE_ZEPHYR_Z3_MAX_CYCLES:-12000000}"
STALL_PERIODS="${AETHERCORE_ZEPHYR_Z3_STALL_PERIODS:-0 2 3 5 7}"
NEGATIVE_MAX_CYCLES="${AETHERCORE_ZEPHYR_Z3_NEGATIVE_MAX_CYCLES:-256}"
HANDOFFS=4

mkdir -p "$LOG_DIR"
test -s "$IMAGE"
test -x "$RUNNER"

matrix_file="$LOG_DIR/matrix.tsv"
printf 'stall_period\tcycles\tcommitted_instructions\n' > "$matrix_file"
reference_commits=""

for stall_period in $STALL_PERIODS; do
  log_file="$LOG_DIR/stall-${stall_period}.log"
  args=("$IMAGE" --max-cycles "$MAX_CYCLES" --self-check-exit)
  if [[ "$stall_period" != "0" ]]; then
    args+=(--stall-period "$stall_period")
  fi

  set +e
  "$RUNNER" "${args[@]}" 2>&1 | tee "$log_file"
  rc=${PIPESTATUS[0]}
  set -e

  if [[ $rc -ne 0 ]]; then
    echo "ERROR: Zephyr Z3 stall-period $stall_period failed with exit code $rc" >&2
    exit "$rc"
  fi

  grep -Fq 'AETHERCORE ZEPHYR BOOT' "$log_file"
  grep -Fq 'AETHERCORE ZEPHYR WORKER READY' "$log_file"
  grep -Fq 'AETHERCORE ZEPHYR PASS handoffs=4' "$log_file"
  grep -Fq 'PASS: self-check exit=0' "$log_file"

  for ((step = 0; step < HANDOFFS; ++step)); do
    main_count="$(grep -Fc "AETHERCORE ZEPHYR MAIN give=$step" "$log_file")"
    worker_count="$(grep -Fc "AETHERCORE ZEPHYR WORKER step=$step" "$log_file")"
    if [[ "$main_count" != "1" || "$worker_count" != "1" ]]; then
      echo "ERROR: stall-period $stall_period lost or duplicated handoff $step" >&2
      exit 30
    fi
  done

  summary="$(grep -E 'PASS: self-check exit=0 after [0-9]+ cycles, [0-9]+ committed instructions' "$log_file" | tail -n 1)"
  if [[ -z "$summary" ]]; then
    echo "ERROR: stall-period $stall_period is missing the deterministic completion summary" >&2
    exit 31
  fi
  cycles="$(sed -E 's/.* after ([0-9]+) cycles,.*/\1/' <<<"$summary")"
  commits="$(sed -E 's/.*, ([0-9]+) committed instructions.*/\1/' <<<"$summary")"

  if [[ -z "$reference_commits" ]]; then
    reference_commits="$commits"
  elif [[ "$commits" != "$reference_commits" ]]; then
    echo "ERROR: committed instruction count changed under stall-period $stall_period: $commits != $reference_commits" >&2
    exit 32
  fi

  printf '%s\t%s\t%s\n' "$stall_period" "$cycles" "$commits" >> "$matrix_file"
done

negative_log="$LOG_DIR/negative-timeout.log"
set +e
"$RUNNER" "$IMAGE" --max-cycles "$NEGATIVE_MAX_CYCLES" --self-check-exit >"$negative_log" 2>&1
negative_rc=$?
set -e
if [[ $negative_rc -eq 0 ]]; then
  echo "ERROR: truncated Z3 negative probe unexpectedly passed" >&2
  exit 40
fi
if grep -Fq 'AETHERCORE ZEPHYR PASS handoffs=4' "$negative_log" || \
   grep -Fq 'PASS: self-check exit=0' "$negative_log"; then
  echo "ERROR: truncated Z3 negative probe emitted a positive signature" >&2
  exit 41
fi
grep -Fq 'FAIL: timeout after' "$negative_log"

cat > "$LOG_DIR/result.txt" <<EOF
status=PASS
contract=zephyr-v3.7.2-aethercore-z3-timer-scheduling-v1
image=$IMAGE
runner=$RUNNER
max_cycles=$MAX_CYCLES
stall_periods=$STALL_PERIODS
handoffs=$HANDOFFS
committed_instructions=$reference_commits
negative_probe=max-cycles-$NEGATIVE_MAX_CYCLES-rejected
profile=rv32im_zicsr
stop_on_trap=false
stop_on_wfi=false
exit_code=0
EOF

find "$LOG_DIR" -maxdepth 1 -type f ! -name artifacts.sha256 -print0 \
  | sort -z \
  | xargs -0 sha256sum > "$LOG_DIR/artifacts.sha256"

cat "$LOG_DIR/result.txt"
cat "$matrix_file"
cat "$LOG_DIR/artifacts.sha256"
