#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PROBE_DIR="$ROOT/build/rv32-nemu-probe"
OPT_ARCHIVE_DIR="$ROOT/build/rv32-nemu-abi"
LOG_DIR="$ROOT/build/ci/logs"
PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"
COMPOSITION_SHA="a218e0ee1b15a461ff27e1bda133d43bf21ccf14977463faf4b872f071c788fa"
OPT_DERIVED_SHA="7f27aa6b6bb4125f0fedd0d481e89503252977bb34c52891ba4c5e983cf18759"
OPT_GENERATED_SHA="c828336eb50ae4bc9a34d264ef2c37779726c550796992b7b3fcde9048b94ddf"
STEP_DERIVED_SHA="9221c1979f056b978179d36404ab3801aa474b67560efcb8093d2da0fef4791a"
STEP_GENERATED_SHA="52ed03a1c6e9c57b6fac319d245c5e0af31589f7d305519cea6eabee0e68ca56"

rm -rf "$PROBE_DIR" "$OPT_ARCHIVE_DIR"
mkdir -p "$LOG_DIR"

set -o pipefail
bash tools/probe_rv32_nemu_deterministic.sh "$PROBE_DIR" \
  2>&1 | tee "$LOG_DIR/rv32-nemu-optimized.log"
grep -q '^status=PASS$' "$PROBE_DIR/evidence/result.txt"
grep -q '^reproducible=true$' "$PROBE_DIR/evidence/result.txt"
grep -q '^single_step=0$' "$PROBE_DIR/evidence/result.txt"
grep -q '^perf_opt=true$' "$PROBE_DIR/evidence/result.txt"
grep -q "^derived_defconfig_sha256=$OPT_DERIVED_SHA$" "$PROBE_DIR/evidence/result.txt"
grep -q "^generated_config_sha256=$OPT_GENERATED_SHA$" "$PROBE_DIR/evidence/result.txt"
grep -q "^build_composition_sha256=$COMPOSITION_SHA$" "$PROBE_DIR/evidence/result.txt"
mv "$PROBE_DIR" "$OPT_ARCHIVE_DIR"

NEMU_SINGLE_STEP=1 bash tools/probe_rv32_nemu_deterministic.sh "$PROBE_DIR" \
  2>&1 | tee "$LOG_DIR/rv32-nemu-single-step.log"
grep -q '^status=PASS$' "$PROBE_DIR/evidence/result.txt"
grep -q '^revision=8601834e4889e6bf3b6113eb5f824ba7689126f5$' \
  "$PROBE_DIR/evidence/result.txt"
grep -q '^softfloat_revision=a0c6494cdc11865811dec815d5c0049fba9d82a8$' \
  "$PROBE_DIR/evidence/result.txt"
grep -q '^reproducible=true$' "$PROBE_DIR/evidence/result.txt"
grep -q '^single_step=1$' "$PROBE_DIR/evidence/result.txt"
grep -q '^perf_opt=false$' "$PROBE_DIR/evidence/result.txt"
grep -q "^derived_defconfig_sha256=$STEP_DERIVED_SHA$" "$PROBE_DIR/evidence/result.txt"
grep -q "^generated_config_sha256=$STEP_GENERATED_SHA$" "$PROBE_DIR/evidence/result.txt"
grep -q "^build_composition_sha256=$COMPOSITION_SHA$" "$PROBE_DIR/evidence/result.txt"
grep -q '^CONFIG_ENABLE_INSTR_CNT=y$' "$PROBE_DIR/evidence/generated.config"
grep -q '^# CONFIG_PERF_OPT is not set$' "$PROBE_DIR/evidence/generated.config"
grep -q '^prefix_matches=true$' "$PROBE_DIR/evidence/abi-probe.txt"
grep -q '^guard_matches=true$' "$PROBE_DIR/evidence/abi-probe.txt"
grep -q '^memory_roundtrip_matches=true$' "$PROBE_DIR/evidence/abi-probe.txt"

reference_so="$(find "$PROBE_DIR/nemu/build" -maxdepth 1 -type f \
  -name 'riscv32-nemu-interpreter-so*' -print -quit)"
test -n "$reference_so"
test -f "$reference_so"

cached_reference="$(
  AETHERCORE_RV32_NEMU_CANDIDATE="$reference_so" \
  AETHERCORE_RV32_NEMU_CANDIDATE_EVIDENCE="$PROBE_DIR/evidence" \
    bash tools/ensure_rv32_nemu_single_step.sh
)"
test -f "$cached_reference"
printf '%s\n' "$cached_reference" > "$PATH_FILE"