BUILD_DIR ?= build
RTL_DIR := $(BUILD_DIR)/rtl
OBJ_DIR := $(BUILD_DIR)/obj
SOFTWARE_DIR := $(BUILD_DIR)/software
REGRESSION_DIR := $(BUILD_DIR)/regressions
COMPLETION_DIR := $(BUILD_DIR)/completion-regressions
FAULT_DIR := $(BUILD_DIR)/fault-regressions
GENERATED_DIR := $(BUILD_DIR)/generated-difftest
NEMU_DIR := $(BUILD_DIR)/nemu
NEMU_HOME := $(abspath $(NEMU_DIR))
NEMU_COMMIT := ad6bfde6241f2fc1e864b1efb2bed99b3670eb73
NEMU_SO := $(NEMU_DIR)/build/riscv64-nemu-interpreter-so
DIFFTEST_PROBE := $(BUILD_DIR)/nemu_difftest_mismatch_probe
TOP := AetherCoreSimTop
VERILATOR ?= verilator
PYTHON ?= python3
CXX ?= g++
SIM_SOURCES := $(abspath sim/sim_main.cpp) $(abspath sim/nemu_difftest.cpp)

# Chisel/CIRCT emits the top and child modules as separate SystemVerilog files.
# Keep this recursive so the wildcard is expanded after the `rtl` prerequisite.
RTL_SOURCES = $(wildcard $(RTL_DIR)/*.sv)

.PHONY: all rtl test smoke regressions completion-regressions fault-regressions generated-difftest nemu sim run-smoke run-regressions run-completion-regressions run-fault-regressions run-difftest run-difftest-mismatch-probe run-generated-difftest python-test clean

all: test run-smoke run-regressions run-completion-regressions run-fault-regressions

rtl:
	./mill aethercore.runMain aethercore.Elaborate --target-dir $(RTL_DIR)

test:
	./mill aethercore.test

smoke:
	mkdir -p $(SOFTWARE_DIR)
	$(PYTHON) tools/make_smoke.py $(SOFTWARE_DIR)/smoke.bin

regressions:
	$(PYTHON) tools/make_regressions.py $(REGRESSION_DIR)

completion-regressions:
	$(PYTHON) tools/make_completion_regressions.py $(COMPLETION_DIR)

fault-regressions:
	$(PYTHON) tools/make_fault_regressions.py $(FAULT_DIR)

generated-difftest:
	$(PYTHON) tools/make_generated_difftest.py $(GENERATED_DIR)

$(NEMU_SO):
	rm -rf $(NEMU_DIR)
	mkdir -p $(NEMU_DIR)
	git -C $(NEMU_DIR) init
	git -C $(NEMU_DIR) remote add origin https://github.com/OpenXiangShan/NEMU.git
	git -C $(NEMU_DIR) fetch --depth=1 origin $(NEMU_COMMIT)
	git -C $(NEMU_DIR) checkout --detach FETCH_HEAD
	NEMU_HOME=$(NEMU_HOME) $(MAKE) -C $(NEMU_DIR) riscv64-nutshell-ref_defconfig
	NEMU_HOME=$(NEMU_HOME) $(MAKE) -C $(NEMU_DIR) -j$$(nproc)
	@test -f $@ || { echo "ERROR: NEMU shared object was not produced"; exit 1; }

nemu: $(NEMU_SO)

$(DIFFTEST_PROBE): sim/nemu_difftest_mismatch_probe.cpp sim/nemu_difftest.cpp sim/nemu_difftest.h
	mkdir -p $(BUILD_DIR)
	$(CXX) -std=c++20 -O2 -Wall -Wextra \
		sim/nemu_difftest_mismatch_probe.cpp sim/nemu_difftest.cpp \
		-ldl -o $@

sim: rtl smoke
	@test -n "$(RTL_SOURCES)" || { echo "ERROR: no generated SystemVerilog files in $(RTL_DIR)"; exit 1; }
	$(VERILATOR) --cc --exe --build --trace -Wall -Wno-fatal \
		--top-module $(TOP) -Mdir $(OBJ_DIR) \
		-CFLAGS "-std=c++20 -O2" -LDFLAGS "-ldl" \
		$(RTL_SOURCES) $(SIM_SOURCES)

run-smoke: sim
	$(OBJ_DIR)/V$(TOP) $(SOFTWARE_DIR)/smoke.bin --max-cycles 200

run-regressions: sim regressions
	@set -e; \
	while read name stall; do \
		echo "== regression: $$name =="; \
		stall_args=""; \
		if [ "$$stall" != "0" ]; then stall_args="--stall-period $$stall"; fi; \
		$(OBJ_DIR)/V$(TOP) $(REGRESSION_DIR)/$$name.bin \
			--max-cycles 500 --self-check-exit $$stall_args; \
	done < $(REGRESSION_DIR)/manifest.txt

run-completion-regressions: sim completion-regressions
	@set -e; \
	while read name; do \
		echo "== completion regression: $$name =="; \
		$(OBJ_DIR)/V$(TOP) $(COMPLETION_DIR)/$$name.bin \
			--max-cycles 500 --self-check-exit; \
	done < $(COMPLETION_DIR)/manifest.txt

run-fault-regressions: sim fault-regressions
	@set -e; \
	while read name stall pc inst commits forbidden memaddr memval; do \
		echo "== precise fault regression: $$name =="; \
		args="--max-cycles 500 --expect-exception-pc $$pc --expect-exception-inst $$inst --expected-commits $$commits"; \
		if [ "$$stall" != "0" ]; then args="$$args --stall-period $$stall"; fi; \
		if [ "$$forbidden" != "-" ]; then args="$$args --forbid-rd $$forbidden"; fi; \
		if [ "$$memaddr" != "-" ]; then args="$$args --expect-memory64 $$memaddr $$memval"; fi; \
		$(OBJ_DIR)/V$(TOP) $(FAULT_DIR)/$$name.bin $$args; \
	done < $(FAULT_DIR)/manifest.txt

run-difftest: sim regressions completion-regressions $(NEMU_SO)
	@set -e; \
	so="$(abspath $(NEMU_SO))"; \
	while read name stall; do \
		echo "== NEMU DiffTest: $$name =="; \
		stall_args=""; \
		if [ "$$stall" != "0" ]; then stall_args="--stall-period $$stall"; fi; \
		$(OBJ_DIR)/V$(TOP) $(REGRESSION_DIR)/$$name.bin \
			--max-cycles 500 --self-check-exit --difftest "$$so" $$stall_args; \
	done < $(REGRESSION_DIR)/manifest.txt; \
	while read name; do \
		echo "== NEMU DiffTest: $$name =="; \
		$(OBJ_DIR)/V$(TOP) $(COMPLETION_DIR)/$$name.bin \
			--max-cycles 500 --self-check-exit --difftest "$$so"; \
	done < $(COMPLETION_DIR)/manifest.txt

run-difftest-mismatch-probe: regressions $(NEMU_SO) $(DIFFTEST_PROBE)
	$(DIFFTEST_PROBE) "$(abspath $(NEMU_SO))" "$(REGRESSION_DIR)/forwarding.bin"

run-generated-difftest: sim generated-difftest $(NEMU_SO)
	@set -e; \
	so="$(abspath $(NEMU_SO))"; \
	while read name seed stall operations words; do \
		echo "== generated NEMU DiffTest: $$name seed=$$seed operations=$$operations words=$$words =="; \
		stall_args=""; \
		if [ "$$stall" != "0" ]; then stall_args="--stall-period $$stall"; fi; \
		$(OBJ_DIR)/V$(TOP) $(GENERATED_DIR)/$$name.bin \
			--max-cycles 5000 --self-check-exit --difftest "$$so" $$stall_args; \
	done < $(GENERATED_DIR)/manifest.txt

python-test:
	$(PYTHON) -m unittest discover -s tests_py -v

clean:
	rm -rf $(BUILD_DIR) out
