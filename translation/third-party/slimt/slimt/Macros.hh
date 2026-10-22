#pragma once
#include <cstdio>
#include <iostream>
#include <stdexcept>
#include <string>

#define SLIMT_BREAK std::raise(SIGTRAP)

#define SLIMT_TRACE(x)                                         \
  do {                                                         \
    std::cerr << __FILE__ << ":" << __LINE__;                  \
    std::cerr << " " << __FUNCTION__ << " ";                   \
    std::cerr << #x << ": " << std::scientific << (x) << '\n'; \
  } while (0)

#define SLIMT_TRACE_BLOCK(x)                                   \
  do {                                                         \
    std::cerr << __FILE__ << ":" << __LINE__;                  \
    std::cerr << " " << __FUNCTION__ << " \n\n";               \
    std::cerr << #x << ": " << std::scientific << (x) << '\n'; \
    std::cerr << "\n\n";                                       \
  } while (0);

#define SLIMT_TRACE2(x, y) \
  SLIMT_TRACE(x);          \
  SLIMT_TRACE(y)

#define SLIMT_TRACE3(x, y, z) \
  SLIMT_TRACE2(x, y);         \
  SLIMT_TRACE(z);

// These throw rather than std::abort(): an abort takes an Android app's whole
// process down with a SIGABRT that no try/catch can intercept, while a throw
// reaches the JNI's catch, which fails the call and lets the app fall back
// (a shortlist whose header or size check fails then fails its load instead
// of crashing; io::load_items throws the same way on a model's version or
// type).
#define SLIMT_ABORT_IF(condition, error)                           \
  do {                                                             \
    if (condition) {                                               \
      throw std::runtime_error(std::string("[slimt] ") + (error)); \
    }                                                              \
  } while (0)

#define SLIMT_ABORT(message)                                       \
  do {                                                             \
    throw std::runtime_error(std::string("[slimt] ") + (message)); \
  } while (0)

#ifdef SLIMT_ENABLE_LOG
#define LOG(level, ...)              \
  do {                               \
    fprintf(stderr, "[%s]", #level); \
    fprintf(stderr, __VA_ARGS__);    \
    fprintf(stderr, "\n");           \
  } while (0)
#else  // SLIMT_ENABLE_LOGS
#define LOG(...) (void)0
#endif  // SLIMT_ENABLE_LOGS
