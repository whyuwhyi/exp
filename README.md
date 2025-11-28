# EXPFP32 - Hardware Exponential Function Implementation

A high-performance hardware implementation of the exponential function (exp and exp2) for single-precision floating-point numbers (FP32), designed using Chisel HDL.

## Overview

This project implements two main designs:

- **EXPFP32**: Computes `exp(x)` for FP32 inputs
- **EXP2FP32**: Computes `2^x` for FP32 inputs

Both designs use a pipelined architecture combining lookup tables (LUT) and polynomial approximation to achieve efficient hardware implementation with high accuracy.

This project reuses the **XiangShan Fudian** floating-point unit library for basic FP32 arithmetic operations (multiplication, fused multiply-add).

## Algorithm

The computation is based on the mathematical transformation:

$$
\begin{equation}
\begin{aligned}
f            &= e^x \\
             &= (2^{\log_2(e)})^x \\
             &= 2^{x \times \log_2(e)} \\
\\
y            &= x \times \log_2(e) \\
             &= y_i + y_f \\
\\
y_f          &= y_{high} + y_{low} \\
\\
2^{y_{high}} &= \text{LUT}[y_{high}] \\
\\
2^{y_{low}}  &= \text{poly}(y_{low})
\end{aligned}
\end{equation}
$$

### Key Components

- **$y_i$**: Integer part, directly used as the output exponent
- **$y_{high}$**: High bits of fractional part (7 bits), used for LUT indexing to compute $2^{y_{high}}$
- **$y_{low}$**: Low bits of fractional part (16 bits), range $[-\frac{1}{2^{m+1}}, \frac{1}{2^{m+1}}]$, approximated using polynomial to compute $2^{y_{low}}$
- **Final result**: $2^y = 2^{y_i} \times 2^{y_{high}} \times 2^{y_{low}}$

## Hardware Design

### Input Filtering

Special value handling and input range validation:

- Special values: NaN, +Inf, -Inf
- Input range: $[-87.3, 88.7]$ (FP32 representation limits)
  - Left overflow: output 0
  - Right overflow: +Inf

### Pipeline Structure

```
S0: Input Filtering and Special Value Handling
    - NaN, Inf detection
    - Range checking [-87.3, 88.7]

S1: Multiply by log₂(e)
    - y = x × log₂(e)
    - Converts exp(x) to 2^y computation

S2: Floating-Point Decomposition
    - Convert FP32 to fixed-point (1, 8, 23) → (sign, integer, fraction)
    - Integer part y_i → output exponent
    - Fraction high 7 bits y_high → LUT index
    - Fraction low 16 bits y_low → convert to FP32 for polynomial

S3-S4: Polynomial Approximation of 2^(y_low)
    - Each stage: one FMAC (Fused Multiply-Add) operation
    - Uses Taylor expansion or Minimax rational approximation
    - Two stages for better accuracy

S5: Result Composition
    - Combine: LUT[y_high] × poly(y_low) × 2^(y_i)
    - Final multiplication and assembly
```

## Performance Results

Verification against NVIDIA GPU reference (RTX 5060) on 1,000,000 test cases:

### EXP2FP32 (2^x implementation)

```
Total: 1,000,000 test cases
Pass:  999,813 (99.98%)
Fail:  187 (0.02%)

Average Error: 5.729434e-08
Maximum Error: 3.106693e-07

Average ULP: 0.69
Maximum ULP: 3

Total Cycles: 1,000,083
Throughput:   1 result/cycle
```

### EXPFP32 (exp(x) implementation)

```
Total: 1,000,000 test cases
Pass:  219,803 (21.98%)
Fail:  780,197 (78.02%)

Average Error: 9.834270e-07
Maximum Error: 3.964582e-06

Average ULP: 11.91
Maximum ULP: 64

Total Cycles: 1,000,089
Throughput:   1 result/cycle
```

## Dependencies

### Required

- **Chisel 6.6.0**: Hardware description language
- **Scala 2.13.15**: Programming language for Chisel
- **Mill**: Build tool for Scala/Chisel projects
- **Verilator**: For simulation and verification
- **XiangShan Fudian**: Floating-point arithmetic library (included as git submodule)

### Optional

- **CUDA/NVCC**: For GPU-accelerated reference implementation (NVIDIA GPU required)
- **Synopsys Design Compiler**: For ASIC synthesis (TSMC N22 process)

## Building

### Initialize Dependencies

```bash
make init
```

This will initialize the XiangShan Fudian submodule.

### Generate SystemVerilog

```bash
# Generate EXPFP32 RTL
./mill --no-server EXPFP32.run

# Generate EXP2FP32 RTL
./mill --no-server EXP2FP32.run
```

The generated SystemVerilog will be placed in `rtl/EXPFP32.sv` or `rtl/EXPFP32.sv`.

### Build and Run Simulation

```bash
make run
```

The build system automatically detects CUDA availability:

- **Without CUDA**: Uses CPU reference only (standard C library `expf()` or `exp2f()`)
- **With CUDA**: Uses both CPU and GPU references simultaneously
  - CPU Reference: Standard C library `expf()` or `exp2f()`
  - GPU Reference: NVIDIA CUDA math library with `-use_fast_math` flag
  - Both error statistics are computed and displayed for comparison

### Clean Build Artifacts

```bash
make clean
```

## Testing and Verification

### Simulation

Verilator-based testbench with:

- Comprehensive test vector generation (1M test cases)
- Random input generation across full FP32 range
- Special value testing (NaN, Inf, zero, negative numbers, subnormals)
- ULP (Unit in Last Place) error measurement
- Waveform generation (FST format) for debugging

### Reference Models

The testbench automatically uses available reference implementations:

- **CPU Reference**: Standard C library (`expf` or `exp2f`) - always available
- **GPU Reference**: NVIDIA CUDA math library with `-use_fast_math` - automatically enabled if CUDA is detected

When both references are available, error statistics are computed against both to provide comprehensive verification.

### Accuracy Metrics

- **ULP Error**: Measures floating-point accuracy in terms of "units in the last place"
- **Relative Error**: Standard floating-point error metrics
- **Pass/Fail**: Bit-exact comparison against reference implementation

## Future Improvements

- [ ] Improve EXP2FP32 accuracy (target < 1 ULP)
- [ ] Optimize polynomial coefficients using Remez algorithm
- [ ] Add configurable rounding mode support
- [ ] Implement denormal number handling

## Credits

- **XiangShan Fudian FPU Library**: Provides high-quality floating-point arithmetic components
  - Repository: <https://github.com/OpenXiangShan/fudian>
  - Used for: FMUL, FCMA_ADD, RawFloat utilities

## References

- IEEE Standard for Floating-Point Arithmetic (IEEE 754-2008)
- XiangShan Fudian FPU: <https://github.com/OpenXiangShan/fudian>
- Chisel/FIRRTL Documentation: <https://www.chisel-lang.org/>
- CUDA Math API: <https://docs.nvidia.com/cuda/cuda-math-api/>
- Handbook of Floating-Point Arithmetic (Muller et al.)

## License

This project reuses the XiangShan Fudian library. Please refer to the respective license files in the `dependencies/fudian` directory for licensing terms.
