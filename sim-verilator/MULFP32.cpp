#include <VMULFP32.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <verilated.h>
#include <verilated_fst_c.h>

#define CONFIG_WAVE_TRACE

static VerilatedContext *contextp = NULL;
static VerilatedFstC *tfp = NULL;
static VMULFP32 *top = NULL;

static uint64_t cycle_count = 0;

#define RESET (top->reset)
#define CLOCK (top->clock)

void single_cycle() {
  CLOCK = 0;
  top->eval();
#ifdef CONFIG_WAVE_TRACE
  tfp->dump(contextp->time());
  contextp->timeInc(1);
#endif
  CLOCK = 1;
  top->eval();
#ifdef CONFIG_WAVE_TRACE
  tfp->dump(contextp->time());
  contextp->timeInc(1);
#endif
  cycle_count++;
}

void reset(int n) {
  RESET = 1;
  while (n-- > 0)
    single_cycle();
  RESET = 0;
}

void sim_init() {
  contextp = new VerilatedContext;
  top = new VMULFP32{contextp};
#ifdef CONFIG_WAVE_TRACE
  tfp = new VerilatedFstC;
  contextp->traceEverOn(true);
  top->trace(tfp, 0);
  tfp->open("build/wave.fst");
#endif
  reset(10);
}

void sim_exit() {
#ifdef CONFIG_WAVE_TRACE
  tfp->close();
  delete tfp;
#endif
  delete top;
  delete contextp;
}

float run_mul_hw(float in1, float in2) {
  union {
    float f;
    uint32_t u;
  } conv1, conv2, out;
  conv1.f = in1;
  conv2.f = in2;

  top->io_in_bits_in1 = conv1.u;
  top->io_in_bits_in2 = conv2.u;
  top->io_out_ready = 1;
  top->io_in_valid = 1;

  while (!top->io_in_ready)
    single_cycle();

  single_cycle();
  top->io_in_valid = 0;

  while (!top->io_out_valid)
    single_cycle();

  out.u = top->io_out_bits_out;

  return out.f;
}

static void test_random_cases() {
  const int N = 100000;
  int pass = 0, fail = 0;
  double total_err = 0.0, max_err = 0.0;

  float *vin1 = (float *)malloc(sizeof(float) * N);
  float *vin2 = (float *)malloc(sizeof(float) * N);
  float *golden = (float *)malloc(sizeof(float) * N);
  float *vout = (float *)malloc(sizeof(float) * N);

  for (int i = 0; i < N; i++) {
    float a = ((float)rand() / RAND_MAX) * 200.0f - 100.0f;
    float b = ((float)rand() / RAND_MAX) * 200.0f - 100.0f;
    vin1[i] = a;
    vin2[i] = b;
    golden[i] = a * b;
  }

  printf("=== Random MUL Tests ===\n");
  printf("%13s %13s %13s %13s %13s\n", "InputA", "InputB", "Golden", "Hardware",
         "Error");
  printf("---------------------------------------------------------------------"
         "-----\n");

  int issued = 0;
  int received = 0;

  top->io_out_ready = 1;
  top->io_in_valid = 0;

  while (received < N) {
    if (issued < N && top->io_in_ready) {
      union {
        float f;
        uint32_t u;
      } a, b;
      a.f = vin1[issued];
      b.f = vin2[issued];
      top->io_in_bits_in1 = a.u;
      top->io_in_bits_in2 = b.u;
      top->io_in_valid = 1;
      issued++;
    } else {
      top->io_in_valid = 0;
    }

    single_cycle();

    if (top->io_out_valid) {
      union {
        float f;
        uint32_t u;
      } y;
      y.u = top->io_out_bits_out;
      vout[received] = y.f;

      double g = (double)golden[received];
      double h = (double)vout[received];
      double denom = (g == 0.0 ? 1.0 : (g > 0 ? g : -g));
      double err = ((h - g) >= 0 ? (h - g) : -(h - g)) / denom;

      total_err += err;
      if (err < 1e-6)
        pass++;
      else
        fail++;

      if (err > max_err)
        max_err = err;

      printf("%+13.3e %+13.3e %+13.3e %+13.3e %13.3e\n", vin1[received],
             vin2[received], golden[received], vout[received], err);

      received++;
    }
  }

  printf("\nTotal=%d, Pass=%d (%.2f%%), Fail=%d (%.2f%%)\n", N, pass,
         (pass * 100.0 / N), fail, (fail * 100.0 / N));
  printf("AvgErr=%e, MaxErr=%e\n", total_err / N, max_err);
  printf("Total cycles: %llu\n", cycle_count);

  free(vin1);
  free(vin2);
  free(golden);
  free(vout);
}

int main() {
  printf("Initializing MUL simulation...\n\n");
  sim_init();
  srand(time(NULL));

  test_random_cases();

  printf("\nSimulation complete.\n");
  sim_exit();
  return 0;
}
