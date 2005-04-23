#ifndef _PARSE_HEADER_FEEP
#define _PARSE_HEADER_FEEP

#include "tinystring.h"

typedef struct arg_s {
  string_t name;
  string_t value;
  //  int pos;
} arg_t;

typedef struct {
  arg_t* arg;
  int num;
} args_t;

args_t arg_parse(const char*);
void arg_done(args_t);
arg_t* arg_get(args_t,int);
arg_t* arg_find(args_t,string_t);

#define AG(a,b) arg_get(a,b)

#endif /* _PARSE_HEADER_FEEP */
