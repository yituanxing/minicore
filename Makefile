BUILD_DIR ?= build
RTL_DIR := $(BUILD_DIR)/rtl
OBJ_DIR := $(BUILD_DIR)/obj
SOFTWARE_DIR := $(BUILD_DIR)/software
TOP := AetherCoreSimTop
VERILATOR ?= verilator
PYTHON ?= python3

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
	$(VERILATOR) --cc --exe --build --trace -Wall -Wno-fatal \
		--top-module $(TOP) -Mdir $(OBJ_DIR) \
		-CFLAGS "-std=c++20 -O2" \
		$(RTL_DIR)/$(TOP).sv sim/sim_main.cpp

run-smoke: sim
	$(OBJ_DIR)/V$(TOP) $(SOFTWARE_DIR)/smoke.bin --max-cycles 200

python-test:
	$(PYTHON) -m unittest discover -s tests_py -v

clean:
	rm -rf $(BUILD_DIR) out
