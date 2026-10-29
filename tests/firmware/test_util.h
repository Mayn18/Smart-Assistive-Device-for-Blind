// Minimal dependency-free assertion helpers for the host-side firmware tests.
#pragma once

#include <stdio.h>
#include <stdlib.h>

static int g_failures = 0;
static int g_checks = 0;

#define CHECK(cond)                                                          \
  do {                                                                       \
    ++g_checks;                                                              \
    if (!(cond)) {                                                           \
      ++g_failures;                                                          \
      printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);               \
    }                                                                        \
  } while (0)

#define CHECK_EQ(a, b)                                                       \
  do {                                                                       \
    ++g_checks;                                                              \
    const long long _a = static_cast<long long>(a);                          \
    const long long _b = static_cast<long long>(b);                          \
    if (_a != _b) {                                                          \
      ++g_failures;                                                          \
      printf("  FAIL %s:%d: %s == %s  (got %lld, expected %lld)\n", __FILE__, \
             __LINE__, #a, #b, _a, _b);                                      \
    }                                                                        \
  } while (0)

#define CHECK_STR(a, b)                                                      \
  do {                                                                       \
    ++g_checks;                                                              \
    if (strcmp((a), (b)) != 0) {                                             \
      ++g_failures;                                                          \
      printf("  FAIL %s:%d: \"%s\" != \"%s\"\n", __FILE__, __LINE__, (a), (b)); \
    }                                                                        \
  } while (0)

#define RUN(test)                                                            \
  do {                                                                       \
    const int before = g_failures;                                           \
    test();                                                                  \
    printf("%s %s\n", g_failures == before ? "[ ok ]" : "[FAIL]", #test);    \
  } while (0)

static int finish(const char* suite) {
  printf("%s: %d checks, %d failures\n", suite, g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}
