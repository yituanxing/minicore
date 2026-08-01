#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <verilator-sim> <nemu-so> <compiled-workload-dir>" >&2
  exit 2
fi

sim="$1"
nemu_so="$2"
workload_dir="$3"
manifest="$workload_dir/manifest.txt"
max_cycles="${MAX_CYCLES:-1000000}"

[[ -x "$sim" ]] || { echo "ERROR: simulator not executable: $sim" >&2; exit 1; }
[[ -f "$nemu_so" ]] || { echo "ERROR: NEMU shared object missing: $nemu_so" >&2; exit 1; }
[[ -f "$manifest" ]] || { echo "ERROR: workload manifest missing: $manifest" >&2; exit 1; }
[[ "$max_cycles" =~ ^[1-9][0-9]*$ ]] || {
  echo "ERROR: MAX_CYCLES must be a positive integer: $max_cycles" >&2
  exit 1
}

while read -r name source optimization stall words bytes digest; do
  [[ -z "$name" || "$name" == \#* ]] && continue
  echo "== compiled RV64IM NEMU DiffTest: $name source=$source optimization=-$optimization words=$words bytes=$bytes sha256=$digest max-cycles=$max_cycles =="
  stall_args=()
  if [[ "$stall" != "0" ]]; then
    stall_args=(--stall-period "$stall")
  fi
  "$sim" "$workload_dir/$name.bin" \
    --max-cycles "$max_cycles" \
    --self-check-exit \
    --difftest "$nemu_so" \
    "${stall_args[@]}"
done < "$manifest"
