#include <VEXPFP32.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <verilated.h>
#include <verilated_fst_c.h>

#define CONFIG_WAVE_TRACE

static VerilatedContext *contextp = NULL;
static VerilatedFstC *tfp = NULL;
static VEXPFP32 *top = NULL;

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
  top = new VEXPFP32{contextp};
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
  int pass = 0, fail = 0;
  double total_err = 0.0, max_err = 0.0;

  float *vin = (float *)malloc(sizeof(float) * N);
  float *golden = (float *)malloc(sizeof(float) * N);
  float *vout = (float *)malloc(sizeof(float) * N);

  int i;
  for (i = 0; i < N; i++) {
    float in = ((float)rand() / RAND_MAX) * 100.0f - 50.0f;
    vin[i] = in;
    golden[i] = expf(in);
  }

  printf("=== Random EXP Tests ===\n");
  printf("%13s %13s %13s %13s\n", "Input", "Golden", "Hardware", "Error");
  printf("-------------------------------------------------------------\n");

  int issued = 0;
  int received = 0;

  top->io_out_ready = 1;
  top->io_in_valid = 0;

  while (received < N) {
    if (issued < N && top->io_in_ready) {
      union {
        float f;
        uint32_t u;
      } conv;
      conv.f = vin[issued];
      top->io_in_valid = 1;
      top->io_in_bits_in = conv.u;
      issued++;
    } else {
      top->io_in_valid = 0;
    }

    single_cycle();

    if (top->io_out_valid) {
      union {
        float f;
        uint32_t u;
      } out;
      out.u = top->io_out_bits_out;
      vout[received] = out.f;

      {
        double g = (double)golden[received];
        double h = (double)vout[received];
        double err = fabs((h - g) / (g == 0.0 ? 1.0 : g));
        total_err += err;
        if (err < 1e-4)
          pass++;
        else
          fail++;
        if (err > max_err)
          max_err = err;

        printf("%+13.3e %+13.3e %+13.3e %13.3e\n", vin[received],
               golden[received], vout[received], err);
      }

      received++;
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
