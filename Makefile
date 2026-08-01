BUILD_DIR ?= build
RTL_DIR := $(BUILD_DIR)/rtl
OBJ_DIR := $(BUILD_DIR)/obj
SOFTWARE_DIR := $(BUILD_DIR)/software
REGRESSION_DIR := $(BUILD_DIR)/regressions
COMPLETION_DIR := $(BUILD_DIR)/completion-regressions
FAULT_DIR := $(BUILD_DIR)/fault-regressions
TOP := AetherCoreSimTop
VERILATOR ?= verilator
PYTHON ?= python3
SIM_MAIN := $(abspath sim/sim_main.cpp)

# Chisel/CIRCT emits the top and child modules as separate SystemVerilog files.
# Keep this recursive so the wildcard is expanded after the `rtl` prerequisite.
RTL_SOURCES = $(wildcard $(RTL_DIR)/*.sv)

.PHONY: all rtl test smoke regressions completion-regressions fault-regressions sim run-smoke run-regressions run-completion-regressions run-fault-regressions python-test clean

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

sim: rtl smoke
	@test -n "$(RTL_SOURCES)" || { echo "ERROR: no generated SystemVerilog files in $(RTL_DIR)"; exit 1; }
	$(VERILATOR) --cc --exe --build --trace -Wall -Wno-fatal \
		--top-module $(TOP) -Mdir $(OBJ_DIR) \
		-CFLAGS "-std=c++20 -O2" \
		$(RTL_SOURCES) $(SIM_MAIN)

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

python-test:
	$(PYTHON) -m unittest discover -s tests_py -v

clean:
	rm -rf $(BUILD_DIR) out
