#ifndef LLSC_PAR_HEADERS
#define LLSC_PAR_HEADERS

/* Thread pool */

#ifdef USE_TP

#include "thread_pool.hpp"
inline thread_pool tp;

#endif

/* Async */

inline std::mutex m;

inline bool can_par_async() {
  return num_async < max_par_num;
}

template <typename F, typename... Ts>
inline auto really_async(F&& f, Ts&&... params) {
  return std::async(std::launch::async, std::forward<F>(f), std::forward<Ts>(params)...);
}

template<class T>
auto create_async(std::function<T()> f) -> std::future<T> {
  std::unique_lock<std::mutex> lk(m);
  num_async++;
  tt_num_async++;
  lk.unlock();

  std::future<T> fu = std::async(std::launch::async, [&]{
    T t = f();
    std::unique_lock<std::mutex> lk(m);
    num_async--;
    lk.unlock();
    return t;
  });
  return fu;
}

#endif
