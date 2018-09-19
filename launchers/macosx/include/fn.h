#ifndef FN_H
#define FN_H

#include <functional>
#include <algorithm>

/*
 * higher-order functions
 *
 * Read
 * http://blog.madhukaraphatak.com/functional-programming-in-c++/
 *
 *
 * Lamda fingerprint:
namespace {
  struct f {
    void operator()(int) {
      // do something
    }
  };
}
 *
 *
 *
template <typename Collection,typename unop>
void for_each(Collection col, unop op){
  std::for_each(col.begin(),col.end(),op);
}

Usage:

auto lambda_echo = [](int i ) { std::cout << i << std::endl; };
 std::vector<int> col{20,24,37,42,23,45,37};
 for_each(col,lambda_echo);

*/
template <typename Collection,typename unop>
void for_each(Collection col, unop op){
  std::for_each(col.begin(),col.end(),op);
}

/**
 * map
 *
 * Usage example:
 auto addOne = [](int i) { return i+1;};
 auto returnCol = map(col,addOne);
 for_each(returnCol,lambda_echo);
 *
 *
 *
 */
template <typename Collection,typename unop>
Collection map(Collection col,unop op) {
  std::transform(col.begin(),col.end(),col.begin(),op);
  return col;
}



/*
Filter usage:

auto filteredCol = filter(col,[](int value){ return value > 30;});
 for_each(filteredCol,lambda_echo);
*/

template <typename Collection,typename Predicate>
Collection filterNot(Collection col,Predicate predicate ) {
  auto returnIterator = std::remove_if(col.begin(),col.end(),predicate);
  col.erase(returnIterator,std::end(col));
  return col;
}

template <typename Collection,typename Predicate>
Collection filter(Collection col,Predicate predicate) {
 //capture the predicate in order to be used inside function
 auto fnCol = filterNot(col,[predicate](typename Collection::value_type i) { return !predicate(i);});
 return fnCol;
}

/**
  *
  * Alternative map implementations
  *
  **/
template<class F, class T, class U=decltype(std::declval<F>()(std::declval<T>()))>
std::vector<U> fmap(F f, const std::vector<T>& vec)
{
    std::vector<U> result;
    std::transform(vec.begin(), vec.end(), std::back_inserter(result), f);
    return result;
}

template<class F, class T, class U=decltype(std::declval<F>()(std::declval<T>()))>
std::shared_ptr<U> fmap(F f, const std::shared_ptr<T>& p)
{
    if (p == nullptr) return nullptr;
    else return std::shared_ptr<U>(new U(f(*p)));
}


/**
 * Experimental code - should not be in production
 */

namespace Experimental {
    template <typename T>
    T min3(const T& a, const T& b, const T& c)
    {
       return std::min(std::min(a, b), c);
    }

    class LevenshteinDistance
    {
        mutable std::vector<std::vector<unsigned int> > matrix_;

    public:
        explicit LevenshteinDistance(size_t initial_size = 8)
            : matrix_(initial_size, std::vector<unsigned int>(initial_size))
        {
        }

        unsigned int operator()(const std::string& s, const std::string& t) const
        {
            const size_t m = s.size();
            const size_t n = t.size();
            // The distance between a string and the empty string is the string's length
            if (m == 0) {
                return (unsigned int)n;
            }
            if (n == 0) {
                return (unsigned int)m;
            }
            // Size the matrix as necessary
            if (matrix_.size() < m + 1) {
                matrix_.resize(m + 1, matrix_[0]);
            }
            if (matrix_[0].size() < n + 1) {
                for (auto& mat : matrix_) {
                    mat.resize(n + 1);
                }
            }
            // The top row and left column are prefixes that can be reached by
            // insertions and deletions alone
            unsigned int i, j;
            for (i = 1;  i <= m; ++i) {
                matrix_[i][0] = i;
            }
            for (j = 1; j <= n; ++j) {
                matrix_[0][j] = j;
            }
            // Fill in the rest of the matrix
            for (j = 1; j <= n; ++j) {
                for (i = 1; i <= m; ++i) {
                    unsigned int substitution_cost = s[i - 1] == t[j - 1] ? 0 : 1;
                    matrix_[i][j] =
                        min3(matrix_[i - 1][j] + 1,                 // Deletion
                        matrix_[i][j - 1] + 1,                      // Insertion
                        matrix_[i - 1][j - 1] + substitution_cost); // Substitution
                }
            }
            return matrix_[m][n];
        }
    };
}

#endif // FN_H
