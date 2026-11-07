/* Supplementary D2 RV64GC/LP64D workload. The distro shell/coreutils/dpkg
 * remain genuine Debian ELF files; this separately exercises architectural
 * F/D, glibc threads/futex, signals and Linux VM contracts before D3.
 */
#define _GNU_SOURCE
#pragma STDC FENV_ACCESS ON
#include <errno.h>
#include <fenv.h>
#include <linux/futex.h>
#include <math.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static _Atomic int increments;
static volatile sig_atomic_t signal_seen;

static void on_signal(int signal_number) {
  if (signal_number == SIGUSR1) signal_seen = 1;
}

static void *worker(void *unused) {
  (void)unused;
  volatile double lhs = 1.25;
  volatile double rhs = 2.5;
  for (int n = 0; n < 64; ++n) {
    volatile double product = lhs * rhs;
    if (product != 3.125) return (void *)(uintptr_t)1;
    atomic_fetch_add_explicit(&increments, 1, memory_order_seq_cst);
    sched_yield();
  }
  return NULL;
}

int main(void) {
  pthread_t first, second;
  void *result = NULL;
  volatile double nine = 9.0;
  volatile double zero = 0.0;
  volatile double one = 1.0;
  struct sigaction action = {.sa_handler = on_signal};

  if (sigaction(SIGUSR1, &action, NULL) != 0 || raise(SIGUSR1) != 0 ||
      signal_seen != 1) return 11;
  if (feclearexcept(FE_ALL_EXCEPT) != 0) return 12;
  volatile double divided = one / zero;
  if (!isinf(divided) || !fetestexcept(FE_DIVBYZERO)) return 13;
  if (sqrt(nine) != 3.0) return 14;

  /* Force the kernel to service genuine user page faults and remaps. */
  const size_t page_size = (size_t)sysconf(_SC_PAGESIZE);
  if (page_size == 0 || page_size == (size_t)-1) return 15;
  char *mapping = mmap(NULL, page_size, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (mapping == MAP_FAILED) return 16;
  mapping[page_size - 1] = 41;
  if (mprotect(mapping, page_size, PROT_READ) != 0 ||
      mapping[page_size - 1] != 41 || munmap(mapping, page_size) != 0) return 17;

  /* FUTEX_WAIT on a mismatched value must return EAGAIN, proving the native
   * Linux futex syscall rather than a userland no-op was reached. */
  int futex_word = 0;
  errno = 0;
  if (syscall(SYS_futex, &futex_word, FUTEX_WAIT | FUTEX_PRIVATE_FLAG,
              1, NULL, NULL, 0) != -1 || errno != EAGAIN) return 18;

  if (pthread_create(&first, NULL, worker, NULL) != 0) return 19;
  if (pthread_create(&second, NULL, worker, NULL) != 0) return 20;
  if (pthread_join(first, &result) != 0 || result != NULL) return 21;
  if (pthread_join(second, &result) != 0 || result != NULL) return 22;
  if (atomic_load_explicit(&increments, memory_order_seq_cst) != 128) return 23;

  puts("AETHERCORE_D2_NATIVE_FP_THREADS_FUTEX_VM_OK");
  return 0;
}
