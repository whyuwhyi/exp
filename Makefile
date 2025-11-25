VERILATOR = verilator
VERILATOR_FLAGS = -MMD --build -cc --x-assign fast --x-initial fast --noassert --quiet-exit --trace --trace-fst

TOPNAME = EXPFP32

BUILD_DIR = build
OBJ_DIR = $(BUILD_DIR)/obj_dir
TARGET = $(BUILD_DIR)/$(TOPNAME)_sim

VSRC = rtl/$(TOPNAME).sv
CSRC = sim-verilator/$(TOPNAME).cpp

$(shell mkdir -p $(BUILD_DIR))

$(TARGET): $(VSRC) $(CSRC)
	$(VERILATOR) $(VERILATOR_FLAGS) $(VSRC) $(CSRC) -Mdir $(OBJ_DIR) --exe -o $(abspath $(TARGET))

run: $(TARGET)
	./$(TARGET)

init:
	git submodule update --init --recursive --progress
