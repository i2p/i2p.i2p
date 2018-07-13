#ifndef NEITHER_TRAITS_HPP
#define NEITHER_TRAITS_HPP

namespace neither {

template<class L, class R>
struct Either;

template<class T>
struct Maybe;

template<class L,class...Xs>
auto isCopyable (L l, Xs...) -> L {
  return l;
}

template<class L, class R>
auto ensureEither ( Either<L,R> const& e) -> Either<L,R> {
  return e;
}

template<class L, class R>
auto ensureEither ( Either<L,R> && e) -> Either<L,R> {
  return e;
}

template<class L, class R>
auto ensureEitherRight ( Either<L,R> const& e, R) -> Either<L, R> {
  return e;
}


template<class L, class R>
auto ensureEitherRight ( Either<L,R>&& e, R&&) -> Either<L, R> {
  return e;
}


template<class L, class R>
auto ensureEitherLeft ( Either<L,R> const& e, L) -> Either<L, R> {
  return e;
}

template<class L, class R>
auto ensureEitherLeft ( Either<L,R>&& e, L&&  ) -> Either<L, R> {
 return e;
}


template<class T>
auto ensureMaybe ( Maybe<T> const& e) -> Maybe<T> {
  return e;
}

}

#endif
