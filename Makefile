BUILD_DIR ?= build
RTL_DIR := $(BUILD_DIR)/rtl
OBJ_DIR := $(BUILD_DIR)/obj
SOFTWARE_DIR := $(BUILD_DIR)/software
TOP := AetherCoreSimTop
VERILATOR ?= verilator
PYTHON ?= python3
SIM_MAIN := $(abspath sim/sim_main.cpp)

# Chisel/CIRCT emits the top and child modules as separate SystemVerilog files.
# Keep this recursive so the wildcard is expanded after the `rtl` prerequisite.
RTL_SOURCES = $(wildcard $(RTL_DIR)/*.sv)

.PHONY: all rtl test smoke sim run-smoke python-test clean

all: test run-smoke

rtl:
	./mill aethercore.runMain aethercore.Elaborate --target-dir $(RTL_DIR)

test:
	./mill aethercore.test

smoke:
	mkdir -p $(SOFTWARE_DIR)
	$(PYTHON) tools/make_smoke.py $(SOFTWARE_DIR)/smoke.bin

sim: rtl smoke
	@test -n "$(RTL_SOURCES)" || { echo "ERROR: no generated SystemVerilog files in $(RTL_DIR)"; exit 1; }
	$(VERILATOR) --cc --exe --build --trace -Wall -Wno-fatal \
		--top-module $(TOP) -Mdir $(OBJ_DIR) \
		-CFLAGS "-std=c++20 -O2" \
		$(RTL_SOURCES) $(SIM_MAIN)

run-smoke: sim
	$(OBJ_DIR)/V$(TOP) $(SOFTWARE_DIR)/smoke.bin --max-cycles 200

python-test:
	$(PYTHON) -m unittest discover -s tests_py -v

clean:
	rm -rf $(BUILD_DIR) out
