#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PHASE="${1:-}"
LOG_DIR="$ROOT/build/ci/logs"
RV32_OPT_DIR="$ROOT/build/ci/rv32-nemu-opt"
RV32_REF_DIR="$ROOT/build/ci/rv32-nemu-single-step"
RV32_PATH_FILE="$ROOT/build/ci/rv32-nemu-so.txt"

mkdir -p "$LOG_DIR"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

rv32_nemu_so() {
  [[ -f "$RV32_PATH_FILE" ]] || die "missing RV32 NEMU path file: $RV32_PATH_FILE"
  local path
  path="$(cat "$RV32_PATH_FILE")"
  [[ -f "$path" ]] || die "missing RV32 NEMU reference: $path"
  printf '%s\n' "$path"
}

phase_toolchain() {
  bash tools/ensure_verilator_5_024.sh
  test "$(stat -f -c %T .)" != "9p"
  test "$(stat -f -c %T .)" != "drvfs"
  java -version
  javac -version
  git --version
  python3 --version
  g++ --version
  test "$(cat "$HOME/.cache/aethercore/toolchains/verilator-5.024/.aethercore-source-revision")" = \
    "522bead374d6b7b2adb316304126e5361b18bcf1"
  test "$(readlink -f "$(command -v verilator)")" = \
    "$HOME/.cache/aethercore/toolchains/verilator-5.024/bin/verilator"
  riscv64-unknown-elf-gcc --version
  riscv64-unknown-elf-objdump --version | head -n 1
  command -v make curl git bison flex zstd file autoconf cmake ninja
  chmod +x mill
  timeout 600 ./mill --version
}

phase_python() {
  set -o pipefail
  make python-test 2>&1 | tee "$LOG_DIR/python-test.log"
}

phase_chisel() {
  set -o pipefail
  make test 2>&1 | tee "$LOG_DIR/chisel-test.log"
}

phase_rv64_smoke() {
  set -o pipefail
  make rtl 2>&1 | tee "$LOG_DIR/rv64-elaboration.log"
  make run-smoke 2>&1 | tee "$LOG_DIR/rv64-smoke.log"
  make run-regressions 2>&1 | tee "$LOG_DIR/rv64-pipeline-regressions.log"
  make run-completion-regressions 2>&1 | tee "$LOG_DIR/rv64-completion-regressions.log"
  make run-fault-regressions 2>&1 | tee "$LOG_DIR/rv64-fault-regressions.log"
}

phase_rv64_difftest() {
  set -o pipefail
  make run-difftest 2>&1 | tee "$LOG_DIR/rv64-nemu-difftest.log"
  make run-difftest-mismatch-probe 2>&1 | tee "$LOG_DIR/rv64-nemu-negative.log"
  make run-generated-difftest 2>&1 | tee "$LOG_DIR/rv64-generated-difftest.log"
  make run-rv64m-regressions 2>&1 | tee "$LOG_DIR/rv64m-directed.log"
  make run-generated-rv64m 2>&1 | tee "$LOG_DIR/rv64m-generated.log"
}

phase_rv64_compiled() {
  local sim="$ROOT/build/obj/VAetherCoreSimTop"
  local nemu="$ROOT/build/nemu/build/riscv64-nemu-interpreter-so"
  [[ -x "$sim" ]] || die "RV64 simulator not found: $sim"
  [[ -f "$nemu" ]] || die "RV64 NEMU reference not found: $nemu"
  set -o pipefail
  bash tools/build_compiled_workloads.sh build/compiled-workloads \
    2>&1 | tee "$LOG_DIR/rv64-compiled-build.log"
  bash tools/run_compiled_workloads.sh "$sim" "$nemu" build/compiled-workloads \
    2>&1 | tee "$LOG_DIR/rv64-compiled-difftest.log"
}

phase_rv64_upstream() {
  local sim="$ROOT/build/obj/VAetherCoreSimTop"
  local nemu="$ROOT/build/nemu/build/riscv64-nemu-interpreter-so"
  [[ -x "$sim" ]] || die "RV64 simulator not found: $sim"
  [[ -f "$nemu" ]] || die "RV64 NEMU reference not found: $nemu"
  set -o pipefail
  bash tools/build_upstream_workloads.sh build/upstream-workloads \
    2>&1 | tee "$LOG_DIR/rv64-upstream-build.log"
  MAX_CYCLES=20000000 bash tools/run_compiled_workloads.sh \
    "$sim" "$nemu" build/upstream-workloads \
    2>&1 | tee "$LOG_DIR/rv64-upstream-difftest.log"
}

phase_rv32_reference() {
  rm -rf "$RV32_OPT_DIR" "$RV32_REF_DIR"
  mkdir -p "$ROOT/build/ci"

  set -o pipefail
  bash tools/probe_rv32_nemu_deterministic.sh "$RV32_OPT_DIR" \
    2>&1 | tee "$LOG_DIR/rv32-nemu-optimized.log"
  grep -q '^status=PASS$' "$RV32_OPT_DIR/evidence/result.txt"
  grep -q '^reproducible=true$' "$RV32_OPT_DIR/evidence/result.txt"
  grep -q '^single_step=0$' "$RV32_OPT_DIR/evidence/result.txt"
  grep -q '^perf_opt=true$' "$RV32_OPT_DIR/evidence/result.txt"
  grep -q '^reference_sha256=0e9dc52aeb2f02c399beaa6c5415ff2f4b6c54cfc9aec84f5be0282fe608cd8a$' \
    "$RV32_OPT_DIR/evidence/result.txt"

  NEMU_SINGLE_STEP=1 bash tools/probe_rv32_nemu_deterministic.sh "$RV32_REF_DIR" \
    2>&1 | tee "$LOG_DIR/rv32-nemu-single-step.log"
  grep -q '^status=PASS$' "$RV32_REF_DIR/evidence/result.txt"
  grep -q '^revision=8601834e4889e6bf3b6113eb5f824ba7689126f5$' \
    "$RV32_REF_DIR/evidence/result.txt"
  grep -q '^reference_sha256=e1e18bec22a1e6a19dbb300b43063ed5d3216a8d9f6ccf6400355d4fb897de9e$' \
    "$RV32_REF_DIR/evidence/result.txt"
  grep -q '^reproducible=true$' "$RV32_REF_DIR/evidence/result.txt"
  grep -q '^single_step=1$' "$RV32_REF_DIR/evidence/result.txt"
  grep -q '^perf_opt=false$' "$RV32_REF_DIR/evidence/result.txt"
  grep -q '^CONFIG_ENABLE_INSTR_CNT=y$' "$RV32_REF_DIR/evidence/generated.config"
  grep -q '^# CONFIG_PERF_OPT is not set$' "$RV32_REF_DIR/evidence/generated.config"

  local reference_so
  reference_so="$(find "$RV32_REF_DIR/nemu/build" -maxdepth 1 -type f \
    -name 'riscv32-nemu-interpreter-so*' -print -quit)"
  [[ -n "$reference_so" && -f "$reference_so" ]] || die "RV32 NEMU shared object missing"
  printf '%s\n' "$reference_so" > "$RV32_PATH_FILE"
}

phase_rv32_gcc() {
  local so
  so="$(rv32_nemu_so)"
  mkdir -p build/rv32
  set -o pipefail
  make -f Makefile.rv32 run 2>&1 | tee "$LOG_DIR/rv32-gcc-workload.log"
  make -f Makefile.rv32 run-difftest RV32_NEMU_SO="$so" \
    2>&1 | tee "$LOG_DIR/rv32-585-difftest.log"
  grep -Fq '585 committed instructions' "$LOG_DIR/rv32-585-difftest.log"
  grep -Fq 'difftest=585' "$LOG_DIR/rv32-585-difftest.log"

  set +e
  AETHERCORE_RV32_DIFFTEST_INJECT_MISMATCH_AT=0 \
    make -f Makefile.rv32 run-difftest RV32_NEMU_SO="$so" \
    > build/rv32/rv32-negative-difftest.log 2>&1
  local status=$?
  set -e
  [[ $status -ne 0 ]] || die "deliberate RV32 mismatch unexpectedly passed"
  grep -Fq 'RV32 DiffTest mismatch after 0 matched events' \
    build/rv32/rv32-negative-difftest.log
}

phase_rv32_csr() {
  local so
  so="$(rv32_nemu_so)"
  make -f Makefile.rv32im-csr workload
  local manifest=build/rv32im-csr/software/manifest.txt
  grep -q '^march=rv32im_zicsr$' "$manifest"
  grep -q '^mabi=ilp32$' "$manifest"
  grep -q '^binary_bytes=388$' "$manifest"
  grep -q '^binary_words=97$' "$manifest"
  grep -q '^binary_sha256=84b5279e4e077fe5e6a8cf06bff9a177a2a774822b7f533c5df663e79468346f$' "$manifest"
  grep -q '<main>:' build/rv32im-csr/software/rv32im-csr.dis
  grep -q 'csrrw' build/rv32im-csr/software/rv32im-csr.dis
  grep -q 'csrrs' build/rv32im-csr/software/rv32im-csr.dis
  grep -q 'csrrc' build/rv32im-csr/software/rv32im-csr.dis
  grep -q 'csrrwi' build/rv32im-csr/software/rv32im-csr.dis
  grep -q 'csrrsi' build/rv32im-csr/software/rv32im-csr.dis
  grep -q 'csrrci' build/rv32im-csr/software/rv32im-csr.dis

  set -o pipefail
  make -f Makefile.rv32im-csr run RV32_NEMU_SO="$so" \
    2>&1 | tee "$LOG_DIR/rv32im-csr.log"
  grep -q '^PASS: self-check exit=0 after 80 cycles, 65 committed instructions, stall-period=5, difftest=65, zicsr-shadow=19$' \
    "$LOG_DIR/rv32im-csr.log"

  set +e
  AETHERCORE_RV32_DIFFTEST_INJECT_MISMATCH_AT=9 \
    build/rv32im-csr/obj/VAetherCoreRV32IMSimTop \
    build/rv32im-csr/software/rv32im-csr.bin \
    --max-cycles 20000 --self-check-exit --stall-period 5 \
    --difftest "$so" > build/rv32im-csr/csr-negative.log 2>&1
  local status=$?
  set -e
  [[ $status -ne 0 ]] || die "deliberate CSR mismatch unexpectedly passed"
  grep -q 'RV32 DiffTest mismatch after 9 matched events' build/rv32im-csr/csr-negative.log
  grep -q 'x31' build/rv32im-csr/csr-negative.log
}

phase_rv32_traps() {
  local so
  so="$(rv32_nemu_so)"
  make -f Makefile.rv32im-traps workloads
  local manifest=build/rv32im-traps/software/manifest.txt
  diff -u <(cat <<'EOF'
ecall 256 64 0b7d27beeb7f1b515dcbfdc1de0cc0a4efc07d6e4e41b83e51f4d64dd186b127
ebreak 260 65 24fd161632ab8b734326ba912628a84eaa5ca9c9a48e12a30918e7dd7416d5d5
illegal 256 64 7ba0bd080db3689597603ec0ad2bf287eedcb55141bb0a111f984ebaeeb291d8
load-fault 260 65 54d93e5f79730171dd938f6be9a4b780c293595530783cf6a43dbeb2b7b8abb2
store-fault 260 65 f5d23a68afc0c7c29b594ce52cf1130356a4e476c877edaafd7418b3b4fc7189
fetch-fault 256 64 84c81e012b857ead633f6063743014294790f969b0e3b13f52c00125ed892122
EOF
  ) "$manifest"
  local name
  for name in ecall ebreak illegal load-fault store-fault fetch-fault; do
    grep -q '<trap_handler>:' "build/rv32im-traps/software/$name.dis"
    grep -q 'csrr.*mcause' "build/rv32im-traps/software/$name.dis"
    grep -q 'csrr.*mepc' "build/rv32im-traps/software/$name.dis"
    grep -q 'csrr.*mtval' "build/rv32im-traps/software/$name.dis"
    grep -q 'csrr.*mstatus' "build/rv32im-traps/software/$name.dis"
  done

  set -o pipefail
  make -f Makefile.rv32im-traps run RV32_NEMU_SO="$so" \
    2>&1 | tee "$LOG_DIR/rv32im-traps.log"
  grep -q '^PASS: self-check exit=0 after 61 cycles, 41 committed instructions, stall-period=5, difftest=41, zicsr-shadow=6, trap-shadow=1$' build/rv32im-traps/logs/ecall.log
  grep -q '^PASS: self-check exit=0 after 62 cycles, 42 committed instructions, stall-period=5, difftest=42, zicsr-shadow=6, trap-shadow=1$' build/rv32im-traps/logs/ebreak.log
  grep -q '^PASS: self-check exit=0 after 61 cycles, 41 committed instructions, stall-period=5, difftest=41, zicsr-shadow=6, trap-shadow=1$' build/rv32im-traps/logs/illegal.log
  grep -q '^PASS: self-check exit=0 after 62 cycles, 42 committed instructions, stall-period=5, difftest=42, zicsr-shadow=6, trap-shadow=1$' build/rv32im-traps/logs/load-fault.log
  grep -q '^PASS: self-check exit=0 after 62 cycles, 42 committed instructions, stall-period=5, difftest=42, zicsr-shadow=6, trap-shadow=1$' build/rv32im-traps/logs/store-fault.log
  grep -q '^PASS: self-check exit=0 after 64 cycles, 42 committed instructions, stall-period=5, difftest=42, zicsr-shadow=6, trap-shadow=1$' build/rv32im-traps/logs/fetch-fault.log

  set +e
  AETHERCORE_RV32_DIFFTEST_INJECT_MISMATCH_AT=18 \
    build/rv32im-traps/obj/VAetherCoreRV32IMTrapSimTop \
    build/rv32im-traps/software/ecall.bin \
    --max-cycles 2000 --self-check-exit --stall-period 5 \
    --difftest "$so" > build/rv32im-traps/trap-negative.log 2>&1
  local status=$?
  set -e
  [[ $status -ne 0 ]] || die "deliberate trap mismatch unexpectedly passed"
  grep -q 'RV32 DiffTest mismatch after 18 matched events' build/rv32im-traps/trap-negative.log
  grep -q 'x31' build/rv32im-traps/trap-negative.log
}

phase_rv32_mret() {
  local so
  so="$(rv32_nemu_so)"
  make -f Makefile.rv32im-mret workloads
  local manifest=build/rv32im-mret/software/manifest.txt
  diff -u <(cat <<'EOF'
ecall-next 352 88 6ddc9d73287798e8dd173221eaaa8866d668acedf004da0ce4b25538b2727702
ebreak-rewrite 368 92 6847a2fc4601317b4fd6348124d1b8c3462207b239d1bcf8bd361672b7b72e3b
load-fault 360 90 03bfdc5ce7f38a6029213c0b986d641fcc5266fd326a3a1c04524b47b0f8a567
double-ecall 392 98 307119e8576defdfc0804d3ff2176e0728b8b1887dad0f643b488b0bc3044042
EOF
  ) "$manifest"
  local name
  for name in ecall-next ebreak-rewrite load-fault double-ecall; do
    grep -q '<trap_handler>:' "build/rv32im-mret/software/$name.dis"
    grep -q '30200073.*mret' "build/rv32im-mret/software/$name.dis"
    grep -q 'csrr.*mstatus' "build/rv32im-mret/software/$name.dis"
    grep -q 'csrw.*mepc' "build/rv32im-mret/software/$name.dis"
  done

  set -o pipefail
  make -f Makefile.rv32im-mret run RV32_NEMU_SO="$so" \
    2>&1 | tee "$LOG_DIR/rv32im-mret.log"
  grep -q '^PASS: self-check exit=0 after 81 cycles, 57 committed instructions, stall-period=5, difftest=57, zicsr-shadow=13, trap-shadow=1, mret-shadow=1$' build/rv32im-mret/logs/ecall-next.log
  grep -q '^PASS: self-check exit=0 after 82 cycles, 58 committed instructions, stall-period=5, difftest=58, zicsr-shadow=13, trap-shadow=1, mret-shadow=1$' build/rv32im-mret/logs/ebreak-rewrite.log
  grep -q '^PASS: self-check exit=0 after 84 cycles, 59 committed instructions, stall-period=5, difftest=59, zicsr-shadow=13, trap-shadow=1, mret-shadow=1$' build/rv32im-mret/logs/load-fault.log
  grep -q '^PASS: self-check exit=0 after 121 cycles, 85 committed instructions, stall-period=5, difftest=85, zicsr-shadow=20, trap-shadow=2, mret-shadow=2$' build/rv32im-mret/logs/double-ecall.log

  set +e
  AETHERCORE_RV32_DIFFTEST_INJECT_MISMATCH_AT=39 \
    build/rv32im-mret/obj/VAetherCoreRV32IMTrapSimTop \
    build/rv32im-mret/software/ecall-next.bin \
    --max-cycles 4000 --self-check-exit --stall-period 5 \
    --difftest "$so" > build/rv32im-mret/mret-negative.log 2>&1
  local status=$?
  set -e
  [[ $status -ne 0 ]] || die "deliberate MRET mismatch unexpectedly passed"
  grep -q 'RV32 DiffTest mismatch after 39 matched events' build/rv32im-mret/mret-negative.log
  grep -q 'x31' build/rv32im-mret/mret-negative.log
}

phase_rv32_timer() {
  local so
  so="$(rv32_nemu_so)"
  make -f Makefile.rv32im-timer workloads
  local manifest=build/rv32im-timer/software/manifest.txt
  diff -u <(cat <<'EOF'
basic 452 113 511071123de99c792c16a934b6e92af9325443c3e8ad3456f5f5db23e1f54f4b
global-mask 472 118 c58fec4382886f409f81f8df5347bee33a129e934571a308195e61e985c22a85
source-mask 472 118 f96f1214aaa25f2e2ed6d920707b8db948deb0c17ba748c5108a224ad8ab76a0
double 480 120 7935973f61c7a8daf47b9864ba304a7acb4581a0dd9f8b2140713fb0ceb7c28e
EOF
  ) "$manifest"
  local name
  for name in basic global-mask source-mask double; do
    grep -q '<trap_handler>:' "build/rv32im-timer/software/$name.dis"
    grep -q '30200073.*mret' "build/rv32im-timer/software/$name.dis"
    grep -q '304.*csrw.*mie' "build/rv32im-timer/software/$name.dis"
    grep -q '300.*csrw.*mstatus' "build/rv32im-timer/software/$name.dis"
    grep -q '344.*csrr.*mip' "build/rv32im-timer/software/$name.dis"
  done

  set -o pipefail
  make -f Makefile.rv32im-timer run RV32_NEMU_SO="$so" \
    2>&1 | tee "$LOG_DIR/rv32im-timer.log"
  grep -q '^PASS: self-check exit=0 after 311 cycles, 150 committed instructions, stall-period=5, difftest=150, zicsr-shadow=16, mret-shadow=1, interrupt-shadow=1$' build/rv32im-timer/logs/basic.log
  grep -q '^PASS: self-check exit=0 after 622 cycles, 341 committed instructions, stall-period=5, difftest=341, zicsr-shadow=17, mret-shadow=1, interrupt-shadow=1$' build/rv32im-timer/logs/global-mask.log
  grep -q '^PASS: self-check exit=0 after 622 cycles, 341 committed instructions, stall-period=5, difftest=341, zicsr-shadow=17, mret-shadow=1, interrupt-shadow=1$' build/rv32im-timer/logs/source-mask.log
  grep -q '^PASS: self-check exit=0 after 600 cycles, 341 committed instructions, stall-period=5, difftest=341, zicsr-shadow=23, mret-shadow=2, interrupt-shadow=2$' build/rv32im-timer/logs/double.log

  mkdir -p build/rv32im-timer/evidence
  build/rv32im-timer/obj/VAetherCoreRV32IMTrapSimTop \
    build/rv32im-timer/software/basic.bin \
    --max-cycles 12000 --self-check-exit --stall-period 5 \
    --difftest "$so" --trace > build/rv32im-timer/basic-trace.log 2>&1
  python3 tools/check_rv32im_timer_vcd.py \
    build/aethercore.vcd build/rv32im-timer/evidence/first-interrupt.txt
  mv build/aethercore.vcd build/rv32im-timer/evidence/basic-interrupt.vcd
  grep -q '^event_index=94$' build/rv32im-timer/evidence/first-interrupt.txt
  grep -q '^retiring_pc=0x80000074$' build/rv32im-timer/evidence/first-interrupt.txt
  grep -q '^interrupt_cause=0x80000007$' build/rv32im-timer/evidence/first-interrupt.txt
  grep -q '^interrupt_pc=0x80000074$' build/rv32im-timer/evidence/first-interrupt.txt
  grep -q '^committed_events=150$' build/rv32im-timer/evidence/first-interrupt.txt

  set +e
  AETHERCORE_RV32_DIFFTEST_INJECT_MISMATCH_AT=94 \
    build/rv32im-timer/obj/VAetherCoreRV32IMTrapSimTop \
    build/rv32im-timer/software/basic.bin \
    --max-cycles 12000 --self-check-exit --stall-period 5 \
    --difftest "$so" > build/rv32im-timer/timer-negative.log 2>&1
  local status=$?
  set -e
  [[ $status -ne 0 ]] || die "deliberate timer mismatch unexpectedly passed"
  grep -q 'RV32 timer DiffTest mismatch after 94 matched events' \
    build/rv32im-timer/timer-negative.log
  grep -q 'x31' build/rv32im-timer/timer-negative.log
}

phase_rv32_coremark() {
  local so
  so="$(rv32_nemu_so)"
  make -f Makefile.rv32im-coremark workload
  local manifest=build/rv32im-coremark/software/manifest.txt
  local disassembly=build/rv32im-coremark/software/coremark_rv32im_O2.dis
  grep -q '^source=coremark@1f483d5b8316753a742cbf5590caf5bd0a4e4777$' "$manifest"
  grep -q '^march=rv32im$' "$manifest"
  grep -q '^mabi=ilp32$' "$manifest"
  grep -q '^bytes=10244$' "$manifest"
  grep -q '^sha256=6e094fad601d16ca8279065ab01083583749d2fb654e4a75bf4d0427df8c5c59$' "$manifest"
  local mul_count divu_count
  mul_count="$(awk '$3 == "mul" { count++ } END { print count + 0 }' "$disassembly")"
  divu_count="$(awk '$3 == "divu" { count++ } END { print count + 0 }' "$disassembly")"
  [[ "$mul_count" -eq 12 ]]
  [[ "$divu_count" -eq 5 ]]
  if grep -Eq '\<(mulh|mulhu|mulhsu|div|rem|remu)\>' "$disassembly"; then
    die "unexpected RV32IM M-opcode mix changed"
  fi
  printf 'mul=%s\ndivu=%s\n' "$mul_count" "$divu_count" \
    | tee build/rv32im-coremark/software/m-opcode-counts.txt

  set -o pipefail
  make -f Makefile.rv32im-coremark run RV32_NEMU_SO="$so" \
    2>&1 | tee "$LOG_DIR/rv32im-coremark.log"
  grep -Fq 'PASS: self-check exit=0 after 883272 cycles, 646301 committed instructions, stall-period=5, difftest=646301' \
    "$LOG_DIR/rv32im-coremark.log"
}

phase_rv32_embench1() {
  local so
  so="$(rv32_nemu_so)"
  make -f Makefile.rv32im-embench workload
  local batch=build/rv32im-embench/software/batch.txt
  local manifest=build/rv32im-embench/software/manifest.tsv
  grep -q '^source=embench-iot@09c2ed8c3b7008c95d08b038de4a3f6dc103ed70$' "$batch"
  grep -q '^benchmarks=crc32 edn matmult-int statemate$' "$batch"
  grep -q '^march=rv32im$' "$batch"
  grep -q '^mabi=ilp32$' "$batch"
  grep -q '^optimization=O2$' "$batch"
  grep -q '^warmup_heat=0$' "$batch"
  grep -q '^global_scale_factor=1$' "$batch"
  grep -q '^local_scale_factor=1$' "$batch"
  test "$(grep -h '^#define LOCAL_SCALE_FACTOR 1$' \
    build/rv32im-embench/software/scaled-src/*/*.c | wc -l)" -eq 4
  grep -Fq $'crc32\t1472\t368\t4c126e8244b5d05b74824d4c1c927d5db44eb8078b4353e8be5a42bc52588aca\t1' "$manifest"
  grep -Fq $'edn\t3972\t993\td521ef81684801adde928cbcf843083ce36c3aab14cf108995fe8d921006aac3\t1' "$manifest"
  grep -Fq $'matmult-int\t2492\t623\t33d3f7ada07f51198589424a17d9e2d5203a620da3dc18489eb88ded1742615a\t1' "$manifest"
  grep -Fq $'statemate\t7356\t1839\t25c74615a0215731111ecf54c5477eb8f89291e768423eeee5c8dfd0dd2aaf4b\t1' "$manifest"

  set -o pipefail
  make -f Makefile.rv32im-embench run RV32_NEMU_SO="$so" \
    2>&1 | tee "$LOG_DIR/rv32im-embench1.log"
  local results=build/rv32im-embench/results.tsv
  grep -Fq $'crc32\tPASS: self-check exit=0 after 33442 cycles, 25703 committed instructions, stall-period=5, difftest=25703' "$results"
  grep -Fq $'edn\tPASS: self-check exit=0 after 61875 cycles, 47338 committed instructions, stall-period=5, difftest=47338' "$results"
  grep -Fq $'matmult-int\tPASS: self-check exit=0 after 143851 cycles, 109152 committed instructions, stall-period=5, difftest=109152' "$results"
  grep -Fq $'statemate\tPASS: self-check exit=0 after 2716 cycles, 1992 committed instructions, stall-period=5, difftest=1992' "$results"
  test "$(grep -c 'PASS: self-check exit=0' "$results")" -eq 4
}

phase_rv32_embench2() {
  local so
  so="$(rv32_nemu_so)"
  make -f Makefile.rv32im-embench-batch2 workload
  local metadata=build/rv32im-embench-batch2/software/batch.txt
  local manifest=build/rv32im-embench-batch2/software/manifest.tsv
  grep -q '^source=embench-iot@09c2ed8c3b7008c95d08b038de4a3f6dc103ed70$' "$metadata"
  grep -q '^benchmarks=aha-mont64 huffbench slre wikisort$' "$metadata"
  grep -q '^march=rv32im$' "$metadata"
  grep -q '^mabi=ilp32$' "$metadata"
  grep -q '^optimization=O2$' "$metadata"
  grep -q '^warmup_heat=0$' "$metadata"
  grep -q '^global_scale_factor=1$' "$metadata"
  grep -q '^local_scale_factor=1$' "$metadata"
  test "$(grep -h '^#define LOCAL_SCALE_FACTOR 1$' \
    build/rv32im-embench-batch2/software/scaled-src/*/*.c | wc -l)" -eq 4
  grep -Fx $'aha-mont64\t2392\t598\t4e3013930725f8b6338b644ba7f08bac70bc4502a7129a22212e58162c8e617f\t1' "$manifest"
  grep -Fx $'huffbench\t3512\t878\ta5bed4cce883ffc0ebc4f2496e3fa10d36386122ffee1f269ce72a30a4bdd8f0\t1' "$manifest"
  grep -Fx $'slre\t4770\t1193\tfe902482d8596165198fbce4bbe6f63f61507df607015700b1ecffe4ed61b929\t1' "$manifest"
  grep -Fx $'wikisort\t12592\t3148\t02f68287d47eaa7e1a00ba7ced3ceda0ef74096e5014d6d842992f5e51aa668a\t1' "$manifest"
  grep -q '<sqrt>:' build/rv32im-embench-batch2/software/wikisort.dis

  set -o pipefail
  make -f Makefile.rv32im-embench-batch2 run RV32_NEMU_SO="$so" \
    2>&1 | tee "$LOG_DIR/rv32im-embench2.log"
  local results=build/rv32im-embench-batch2/results.tsv
  test "$(grep -c 'PASS: self-check exit=0' "$results")" -eq 4
  grep -Fx $'aha-mont64\tPASS: self-check exit=0 after 12667 cycles, 10885 committed instructions, stall-period=5, difftest=10885' "$results"
  grep -Fx $'huffbench\tPASS: self-check exit=0 after 357646 cycles, 257617 committed instructions, stall-period=5, difftest=257617' "$results"
  grep -Fx $'slre\tPASS: self-check exit=0 after 31361 cycles, 23049 committed instructions, stall-period=5, difftest=23049' "$results"
  grep -Fx $'wikisort\tPASS: self-check exit=0 after 1213171 cycles, 853344 committed instructions, stall-period=5, difftest=853344' "$results"
}

phase_rv32_littlefs() {
  local so
  so="$(rv32_nemu_so)"
  make -f Makefile.rv32im-littlefs workload
  local manifest=build/rv32im-littlefs/software/manifest.txt
  local symbols=build/rv32im-littlefs/software/littlefs-basic.nm
  grep -q '^source=littlefs@6cb4e86540eca0d9ba62500a298385c9d863c8be$' "$manifest"
  grep -q '^march=rv32im$' "$manifest"
  grep -q '^mabi=ilp32$' "$manifest"
  grep -q '^optimization=O2$' "$manifest"
  grep -q '^lfs_no_malloc=1$' "$manifest"
  grep -q '^lfs_no_assert=1$' "$manifest"
  grep -q '^lfs_no_debug=1$' "$manifest"
  grep -q '^lfs_no_warn=1$' "$manifest"
  grep -q '^lfs_no_error=1$' "$manifest"
  grep -q '^flash_block_size=256$' "$manifest"
  grep -q '^flash_block_count=128$' "$manifest"
  grep -q '^flash_bytes=32768$' "$manifest"
  grep -q '^read_size=16$' "$manifest"
  grep -q '^prog_size=16$' "$manifest"
  grep -q '^cache_size=64$' "$manifest"
  grep -q '^lookahead_size=16$' "$manifest"
  grep -q '^inline_files=disabled$' "$manifest"
  grep -q '^binary_bytes=36140$' "$manifest"
  grep -q '^binary_words=9035$' "$manifest"
  grep -q '^binary_sha256=0d49f8ac86a400c8e54831e9418c51c0d5093f66edf9bfa2c628a5b38f0e230c$' "$manifest"
  local symbol
  for symbol in lfs_format lfs_mount lfs_file_write lfs_file_truncate lfs_rename lfs_dir_read lfs_unmount; do
    grep -Eq "[[:space:]]${symbol}$" "$symbols"
  done
  ! grep -Eq '[[:space:]](malloc|calloc|realloc|free)$' "$symbols"

  set -o pipefail
  make -f Makefile.rv32im-littlefs run RV32_NEMU_SO="$so" \
    2>&1 | tee "$LOG_DIR/rv32im-littlefs.log"
  local log=build/rv32im-littlefs/littlefs.log
  grep -Fx 'LFS_PASS read_ops=528 prog_ops=26 erase_ops=9 sync_ops=13 read_bytes=14304 prog_bytes=1392 used_blocks=8 image_crc32=ccb1e5e1' "$log"
  grep -Fx 'PASS: self-check exit=0 after 6253575 cycles, 4819485 committed instructions, stall-period=5, difftest=4819485' "$log"
}

case "$PHASE" in
  toolchain) phase_toolchain ;;
  python) phase_python ;;
  chisel) phase_chisel ;;
  rv64-smoke) phase_rv64_smoke ;;
  rv64-difftest) phase_rv64_difftest ;;
  rv64-compiled) phase_rv64_compiled ;;
  rv64-upstream) phase_rv64_upstream ;;
  rv32-reference) phase_rv32_reference ;;
  rv32-gcc) phase_rv32_gcc ;;
  rv32-csr) phase_rv32_csr ;;
  rv32-traps) phase_rv32_traps ;;
  rv32-mret) phase_rv32_mret ;;
  rv32-timer) phase_rv32_timer ;;
  rv32-coremark) phase_rv32_coremark ;;
  rv32-embench1) phase_rv32_embench1 ;;
  rv32-embench2) phase_rv32_embench2 ;;
  rv32-littlefs) phase_rv32_littlefs ;;
  *)
    echo "usage: $0 {toolchain|python|chisel|rv64-smoke|rv64-difftest|rv64-compiled|rv64-upstream|rv32-reference|rv32-gcc|rv32-csr|rv32-traps|rv32-mret|rv32-timer|rv32-coremark|rv32-embench1|rv32-embench2|rv32-littlefs}" >&2
    exit 2
    ;;
esac
