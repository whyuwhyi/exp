#include <VCMAFP32.h>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <verilated.h>
#include <verilated_fst_c.h>

#define CONFIG_WAVE_TRACE

static VerilatedContext *contextp = NULL;
static VerilatedFstC *tfp = NULL;
static VCMAFP32 *top = NULL;

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
  top = new VCMAFP32{contextp};
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

static void test_random_cases() {
  const int N = 100000;
  const int PIPE_DELAY = 5 - 1;

  int pass = 0, fail = 0;
  double total_err = 0.0, max_err = 0.0;

  float *vin1 = (float *)malloc(sizeof(float) * N);
  float *vin2 = (float *)malloc(sizeof(float) * N);
  float *vin3 = (float *)malloc(sizeof(float) * N);
  float *golden = (float *)malloc(sizeof(float) * N);
  float *vout = (float *)malloc(sizeof(float) * N);

  for (int i = 0; i < N; i++) {
    float a = ((float)rand() / RAND_MAX) * 200.0f - 100.0f;
    float b = ((float)rand() / RAND_MAX) * 200.0f - 100.0f;
    float c = ((float)rand() / RAND_MAX) * 200.0f - 100.0f;
    vin1[i] = a;
    vin2[i] = b;
    vin3[i] = c;
    golden[i] = a * b + c;
  }

  printf("=== Random CMA Tests ===\n");
  printf("%13s %13s %13s %13s %13s %13s\n", "InputA", "InputB", "InputC",
         "Golden", "Hardware", "Error");
  printf("---------------------------------------------------------------------"
         "----------------\n");

  for (int cycle = 0; cycle < N + PIPE_DELAY; cycle++) {
    if (cycle < N) {
      union {
        float f;
        uint32_t u;
      } a, b, c;
      a.f = vin1[cycle];
      b.f = vin2[cycle];
      c.f = vin3[cycle];
      top->io_in_a = a.u;
      top->io_in_b = b.u;
      top->io_in_c = c.u;
      top->io_in_rm = 0;
    }

    single_cycle();

    int out_idx = cycle - PIPE_DELAY;
    if (out_idx >= 0 && out_idx < N) {
      union {
        float f;
        uint32_t u;
      } y;
      y.u = top->io_out_out;
      vout[out_idx] = y.f;

      double g = (double)golden[out_idx];
      double h = (double)vout[out_idx];
      double denom = (g == 0.0 ? 1.0 : fabs(g));
      double err = fabs(h - g) / denom;

      total_err += err;
      if (err < 1e-6)
        pass++;
      else {
        fail++;

        printf("%+13.6e %+13.6e %+13.6e %+13.6e %+13.6e %13.6e\n",
               vin1[out_idx], vin2[out_idx], vin3[out_idx], golden[out_idx],
               vout[out_idx], err);
      }

      if (err > max_err)
        max_err = err;
    }
  }

  printf("\nTotal=%d, Pass=%d (%.2f%%), Fail=%d (%.2f%%)\n", N, pass,
         (pass * 100.0 / N), fail, (fail * 100.0 / N));
  printf("AvgErr=%e, MaxErr=%e\n", total_err / N, max_err);
  printf("Total cycles: %llu\n", cycle_count);

  free(vin1);
  free(vin2);
  free(vin3);
  free(golden);
  free(vout);
}

int main() {
  printf("Initializing CMA simulation...\n\n");
  sim_init();
  srand(time(NULL));

  test_random_cases();

  printf("\nSimulation complete.\n");
  sim_exit();
  return 0;
}
