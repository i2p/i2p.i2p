#include "either.hpp"

namespace neither {

  template<class L, class R>
  constexpr bool hasValue(Either<L,R> const& e) {
    return e;
  }


  template<class T>
  constexpr bool hasValue(Maybe<T> const& m) {
    return m;
  }

  template<class T>
  constexpr bool hasValue(T) {
    return true;
  }



  template<class L, class R>
  constexpr R unpack(Either<L, R> const& e) {
    return e.rightValue;
  }


  template<class T>
  constexpr T unpack(Maybe<T> const& m) {
    return m.value;
  }

  template<class T>
  constexpr T unpack(T const& x) {
    return x;
  }

  constexpr auto allTrue(bool x=true, bool y=true) {
    return x && y;
  }


  template<class X, class...Xs>
  auto allTrue(X x, Xs...xs) {
    return allTrue(x, allTrue(xs...));
  }

  template<class F>
  auto lift(F const& f) {
    return [f](auto...x) -> decltype(maybe(f(unpack(x)...))) {
      if ( allTrue(hasValue(x)...) ) {
        return f(unpack(x)...);
      }

      return maybe();
    };
  }

}
