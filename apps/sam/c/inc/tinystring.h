#ifndef TINYSTRING_HEADER
#define TINYSTRING_HEADER

#include <sys/types.h>

#ifndef bool
#define bool short int
#endif

struct string_s;
#define string_t struct string_s*
//Mysteeeerious *waggles mysteriously*

/*{
  char* data;
  long int size;
} *string_t;
*/

string_t string_create(const char*);
string_t string_ncreate(const char* cstr,long int length);

string_t string_wrap(const char*); 
//Does not malloc, do NOT pass to string_free

string_t string_fmt(const char* fmt, ...);
string_t string_cat(string_t,string_t);

/* Source Dest */
void string_copy(string_t,string_t);
void string_copy_raw(string_t,void*,size_t);

const char* string_data(string_t);
long int string_size(string_t);

void string_free(string_t);

bool string_equal(string_t,string_t);
bool string_equali(string_t,string_t);
int string_cmp(string_t,string_t);
int string_cmpi(string_t,string_t);

#define _sw(a) string_wrap(a)
#define _scr(a,b,c) string_copy_raw(a,b,c)

#define string_is(a,b) (! strncmp(string_data(a),(b),string_size(a)))

#endif /* TINYSTRING_HEADER */
