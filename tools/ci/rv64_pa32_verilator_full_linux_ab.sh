#!/usr/bin/env bash
# Full Linux/OpenSBI PID1 STIP SEIP A/B: identical firmware and RTL.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
FW_BIN="${FW_BIN:-build/rv64-isa-fast-boot/opensbi/platform/generic/firmware/fw_payload.bin}"
MODELS="${MODELS:-build/rv64-pa32-verilator-version-ab}"
OUT="${OUT:-build/rv64-pa32-verilator-full-linux-ab}"
MAX_CYCLES="${MAX_CYCLES:-950000000}"
TOP=AetherCoreV2OpenSbiRV64SimTop
mkdir -p "$OUT"
test -s "$FW_BIN"
for version in 5.024 5.054; do
  test -x "$MODELS/obj-$version/V$TOP"
  test -s "$MODELS/snapshot-$version-1.txt"
done
diff -u "$MODELS/snapshot-5.024-1.txt" "$MODELS/snapshot-5.054-1.txt"
sha256sum "$FW_BIN" > "$OUT/firmware.sha256"
printf 'version\tcycles\tcommits\tstip\tseip\tseconds\tcps\n' > "$OUT/results.tsv"
for version in 5.024 5.054; do
  log="$OUT/linux-$version.log"
  sim="$MODELS/obj-$version/V$TOP"
  start="$(date +%s%N)"
  rc=0
  AETHERCORE_ISA_COVERAGE=1 "$sim" "$FW_BIN" "$MAX_CYCLES" \
    'RV64 USER UART IRQ OK' 1 1 '' '' 0 10000000 0 1 0 >"$log" 2>&1 || rc=$?
  finish="$(date +%s%N)"
  if ((rc != 0)); then tail -100 "$log" >&2; exit 5; fi
  for marker in 'OpenSBI v1.6' 'RV64 USER UART IRQ OK' \
    'L32_RUNTIME_MILESTONE_PASS' 'L32_FIRST_SUPERVISOR_TIMER_INTERRUPT' \
    'L32_FIRST_SUPERVISOR_EXTERNAL_INTERRUPT' 'L32_ISA_COVERAGE version=1 '; do
    grep -Fq "$marker" "$log" || { echo "missing $marker in $version" >&2; exit 6; }
  done
  grep '^L32_RUNTIME_MILESTONE_PASS ' "$log" | tail -1 > "$OUT/milestone-$version.txt"
  grep '^L32_ISA_COVERAGE ' "$log" | tail -1 > "$OUT/isa-$version.txt"
  python3 - "$version" "$start" "$finish" "$OUT/milestone-$version.txt" "$OUT/results.tsv" <<'PY'
import pathlib,re,sys
version,start,finish,marker,out=sys.argv[1:]
line=pathlib.Path(marker).read_text()
f=dict(re.findall(r'([\w-]+)=([^\s]+)',line))
for key in ('cycles','commits','stip','seip'):
    if key not in f: raise SystemExit('missing '+key)
seconds=(int(finish)-int(start))/1e9
cycles=int(f['cycles'])
with open(out,'a') as dst:
    dst.write(f"{version}\t{cycles}\t{f['commits']}\t{f['stip']}\t{f['seip']}\t{seconds:.6f}\t{cycles/seconds:.3f}\n")
print(f"AETHERCORE_PA32_FULL_LINUX_PASS version={version} cycles={cycles} commits={f['commits']} stip={f['stip']} seip={f['seip']} host_seconds={seconds:.3f}")
PY
done
diff -u "$OUT/milestone-5.024.txt" "$OUT/milestone-5.054.txt" || { echo "AETHERCORE_PA32_FULL_LINUX_STATE_MISMATCH" >&2; exit 7; }
diff -u "$OUT/isa-5.024.txt" "$OUT/isa-5.054.txt" || { echo "AETHERCORE_PA32_FULL_LINUX_ISA_MISMATCH" >&2; exit 8; }
echo "AETHERCORE_PA32_FULL_LINUX_STATE_MATCH"
python3 - "$OUT/results.tsv" <<'PY'
import csv,sys
with open(sys.argv[1],newline='') as src:
    rows={x['version']:x for x in csv.DictReader(src,delimiter='\t')}
a=float(rows['5.024']['seconds'])
b=float(rows['5.054']['seconds'])
print(f"AETHERCORE_PA32_FULL_LINUX_AB_RESULT baseline_seconds={a:.3f} new_seconds={b:.3f} wall_time_reduction_pct={(1-b/a)*100:.2f} speedup={a/b:.5f}x")
PY
