#include <VEXPFP32.h>
#include <cfloat>
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
    float in = ((float)rand() / RAND_MAX) * 175.9f - 87.2f;
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
      top->io_in_bits_rm = 0;
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
        double err;
        if (std::isnan(g) && std::isnan(h)) {
          err = 0.0;
          pass++;
        } else if (std::isinf(g) && std::isinf(h)) {
          err = 0.0;
          pass++;
        } else {
          err = fabs((h - g) / ((g == 0.0 || h == 0.0) ? 1.0 : g));
          if (err < 1e-4)
            pass++;
          else {
            fail++;
            printf("%+13.6e %+13.6e %+13.6e %13.6e\n", vin[received],
                   golden[received], vout[received], err);
          }
        }

        total_err += err;
        if (err > max_err)
          max_err = err;
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

static void test_special_cases() {
  printf("\n=== Special EXP Tests (Extended) ===\n");
  printf("%13s %13s %13s %13s\n", "Input", "Golden", "Hardware", "Error");
  printf("-------------------------------------------------------------\n");

  const int N = 43;
  float vin[N] = {// --- Basic ---
                  0.0f, -0.0f, 1.0f, -1.0f, 10.0f, -10.0f, 50.0f, -50.0f,
                  88.699999f, 88.7f, 88.700001f, -87.300001f, -87.3f,
                  -87.299999f, 100.0f, -100.0f,

                  // --- Extremes ---
                  INFINITY, -INFINITY, NAN, 1e-37f, -1e-37f, 1e+38f, -1e+38f,

                  // --- Subnormals & boundaries ---
                  1e-45f, -1e-45f, FLT_MIN, -FLT_MIN, FLT_MAX, -FLT_MAX,

                  // --- Mathematical constants ---
                  (float)M_PI, (float)-M_PI, (float)M_E, (float)-M_E,
                  (float)M_LN2, (float)-M_LN2, (float)M_LN10, (float)-M_LN10,

                  // --- Borderline overflow/underflow region ---
                  88.0f, 89.0f, 90.0f, -87.0f, -88.0f, -89.0f};

  float golden[N];
  float vout[N];

  for (int i = 0; i < N; i++) {
    golden[i] = expf(vin[i]);
  }

  int issued = 0, received = 0;
  int pass = 0, fail = 0;
  double total_err = 0.0, max_err = 0.0;

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
      top->io_in_bits_rm = 0;
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

      double g = (double)golden[received];
      double h = (double)vout[received];

      double err;
      if (std::isnan(g) && std::isnan(h)) {
        err = 0.0;
        pass++;
      } else if (std::isinf(g) && std::isinf(h)) {
        err = 0.0;
        pass++;
      } else {
        err = fabs((h - g) / ((g == 0.0 || h == 0.0) ? 1.0 : g));
        if (err < 1e-4)
          pass++;
        else
          fail++;
      }

      total_err += err;
      if (err > max_err)
        max_err = err;

      printf("%+13.6e %+13.6e %+13.6e %13.6e\n", vin[received],
             golden[received], vout[received], err);
      received++;
    }
  }

  printf("\nTotal=%d, Pass=%d (%.2f%%), Fail=%d (%.2f%%)\n", N, pass,
         (pass * 100.0 / N), fail, (fail * 100.0 / N));
  printf("AvgErr=%e, MaxErr=%e\n", total_err / N, max_err);
  printf("Total cycles: %llu\n", cycle_count);
}

static inline uint32_t f32_to_order_key(uint32_t b) {
  return (b & 0x80000000u) ? (b ^ 0xFFFFFFFFu) : (b ^ 0x80000000u);
}
static inline uint32_t order_key_to_f32(uint32_t k) {
  return (k & 0x80000000u) ? (k ^ 0x80000000u) : (~k);
}

static void test_valid_range_cases() {
  const uint32_t MIN_BITS = 0xC2AE999A; // -87.3
  const uint32_t MAX_BITS = 0x42B16666; // +88.7

  const uint32_t start_key = f32_to_order_key(MIN_BITS);
  const uint32_t end_key = f32_to_order_key(MAX_BITS);

  uint64_t pass = 0, fail = 0;
  double total_err = 0.0, max_err = 0.0;

  printf("=== EXP Tests in Valid Range [-87.3, +88.7] ===\n");
  printf("%13s %13s %13s %13s\n", "Input", "Golden", "Hardware", "Error");
  printf("-------------------------------------------------------------\n");

  // 驱动/采样模式与其他测试一致
  top->io_out_ready = 1;
  top->io_in_valid = 0;

  for (uint32_t k = start_key; k <= end_key; ++k) {
    union {
      uint32_t u;
      float f;
    } in, out;
    in.u = order_key_to_f32(k);

    while (!top->io_in_ready)
      single_cycle();
    top->io_in_valid = 1;
    top->io_in_bits_in = in.u;
    top->io_in_bits_rm = 0;
    single_cycle();
    top->io_in_valid = 0;

    while (!top->io_out_valid)
      single_cycle();
    out.u = top->io_out_bits_out;

    const double g = (double)expf(in.f);
    const double h = (double)out.f;

    double err = fabs((h - g) / g);

    if (err < 1e-4)
      pass++;
    else {
      fail++;

      printf("%+13.6e %+13.6e %+13.6e %13.6e\n", in.f, (float)g, (float)h, err);
    }
    total_err += err;
    if (err > max_err)
      max_err = err;
  }

  const double total = (double)(end_key - start_key + 1ull);
  printf("\nTotal=%.0f, Pass=%llu (%.2f%%), Fail=%llu (%.2f%%)\n", total, pass,
         100.0 * pass / total, fail, 100.0 * fail / total);
  printf("AvgErr=%e, MaxErr=%e\n", total_err / total, max_err);
  printf("Total cycles: %llu\n", cycle_count);
}

int main() {
  printf("Initializing EXP simulation...\n\n");
  sim_init();
  srand(time(NULL));

  // test_random_cases();
  // test_special_cases();
  test_valid_range_cases();

  printf("\nSimulation complete.\n");
  sim_exit();
  return 0;
}
