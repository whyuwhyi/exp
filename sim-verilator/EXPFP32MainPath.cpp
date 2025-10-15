#include <VEXPFP32MainPath.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <verilated.h>
#include <verilated_fst_c.h>

#define CONFIG_WAVE_TRACE

static VerilatedContext *contextp = NULL;
static VerilatedFstC *tfp = NULL;
static VEXPFP32MainPath *top = NULL;

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
  top = new VEXPFP32MainPath{contextp};
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
  const int PIPE_DELAY = 18 - 1;

  int pass = 0, fail = 0;
  double total_err = 0.0, max_err = 0.0;

  float *vin = (float *)malloc(sizeof(float) * N);
  float *golden = (float *)malloc(sizeof(float) * N);
  float *vout = (float *)malloc(sizeof(float) * N);

  for (int i = 0; i < N; i++) {
    float in = ((float)rand() / RAND_MAX) * 100.0f - 50.0f;
    vin[i] = in;
    golden[i] = expf(in);
  }

  printf("=== Random EXP Tests ===\n");
  printf("%13s %13s %13s %13s\n", "Input", "Golden", "Hardware", "Error");
  printf("---------------------------------------------------------------------"
         "-----\n");

  for (int i = 0; i < N + PIPE_DELAY; i++) {
    if (i < N) {
      union {
        float f;
        uint32_t u;
      } a;
      a.f = vin[i];
      top->io_in_in = a.u;
      top->io_in_rm = 0;
    }

    single_cycle();

    if (i >= PIPE_DELAY) {
      int idx = i - PIPE_DELAY;
      union {
        float f;
        uint32_t u;
      } y;
      y.u = top->io_out_out;
      vout[idx] = y.f;

      double g = (double)golden[idx];
      double h = (double)vout[idx];
      double denom = (g == 0.0 ? 1.0 : fabs(g));
      double err = fabs(h - g) / denom;

      total_err += err;
      if (err < 1e-4)
        pass++;
      else
        fail++;
      if (err > max_err)
        max_err = err;

      printf("%+13.6e %+13.6e %+13.6e %13.6e\n", vin[idx], golden[idx],
             vout[idx], err);
    }
  }
  printf("\nTotal=%d, Pass=%d (%.2f%%), Fail=%d (%.2f%%)\n", N, pass,
         (pass * 100.0 / N), fail, (fail * 100.0 / N));
  printf("AvgErr=%e, MaxErr=%e\n", total_err / N, max_err);
  printf("Total cycles: %llu\n", cycle_count);

  free(vin);
  free(golden);
  free(vout);
}

int main() {
  printf("Initializing EXP simulation...\n\n");
  sim_init();
  srand(time(NULL));

  test_random_cases();

  printf("\nSimulation complete.\n");
  sim_exit();
  return 0;
}
