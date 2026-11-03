#!/usr/bin/env bash
# CHIRRTL -> GSIM C++ compilation feasibility only (not a Linux correctness gate).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OUT="${OUT:-build/rv64-pa32-gsim-feasibility}"
GSIM_TAG="master-2026-10-30-45a73d6"
GSIM_SHA256="c4af0822567e15a2d7322653b36b310206dc51c6c6c89e1ad2ff9c148d19e919"
mkdir -p "$OUT"
# The mill launcher is stored without an executable bit in this repository.
test -f ./mill || { echo "missing Mill launcher" >&2; exit 2; }
chmod +x ./mill
./mill aethercore.runMain aethercore.ElaborateV2VirtualFpgaBoardPA32CHIRRTL \
  "$ROOT/$OUT/pa32.fir" 2>&1 | tee "$OUT/chirrtl-export.log"
test -s "$OUT/pa32.fir"
head -1 "$OUT/pa32.fir" | grep -Eq '^(FIRRTL version|circuit )'
sha256sum "$OUT/pa32.fir" > "$OUT/chirrtl.sha256"
printf '%s\n' "GSIM tag=$GSIM_TAG sha256=$GSIM_SHA256" > "$OUT/gsim-provenance.txt"
URL="https://github.com/OpenXiangShan/gsim/releases/download/$GSIM_TAG/gsim-clang19.1.7-X64"
curl --fail --location --retry 3 --silent --show-error "$URL" -o "$OUT/gsim"
printf '%s  %s\n' "$GSIM_SHA256" "$OUT/gsim" | sha256sum -c -
chmod +x "$OUT/gsim"
mkdir -p "$OUT/generated"
# Prevent an unsupported large-SoC frontend from exhausting a hosted worker.
set +e
timeout --signal=TERM --kill-after=10s 900s \
  "$OUT/gsim" --dir "$OUT/generated" --log-level=1 "$OUT/pa32.fir" \
  > "$OUT/gsim-compile.log" 2>&1
rc=$?
set -e
echo "gsim-exit=$rc" | tee "$OUT/result.txt"
if ((rc!=0)); then
  tail -100 "$OUT/gsim-compile.log" >&2
  echo "AETHERCORE_PA32_GSIM_FEASIBILITY_FAIL stage=chirrtl-to-cpp rc=$rc" >&2
  exit "$rc"
fi
find "$OUT/generated" -type f -name '*.cpp' -size +0c | sort > "$OUT/cpp-files.txt"
test -s "$OUT/cpp-files.txt" || {
  echo "AETHERCORE_PA32_GSIM_FEASIBILITY_FAIL stage=no-generated-cpp" >&2; exit 12;
}
echo "AETHERCORE_PA32_GSIM_FRONTEND_PASS"
echo "NOTE: GSIM emitted C++; no execution, timing equivalence or Linux PASS is implied"
